import Foundation
import OnnxRuntimeBindings

/// File conversion with the exported ONNX models: a port of the Android app's Rvc.kt (itself a port of
/// phone-vc/pipeline.py). 48 kHz mono in, 48 kHz mono out.
///
///   16 kHz audio -> HuBERT features (50 fps, repeated to 100 fps) and RMVPE pitch (100 fps)
///   -> pitch shaping (unvoiced fill, range guards, intonation stretch, key shift) -> coarse pitch
///   -> enc -> latent z, stretched by the formant factor -> dec, driven by the NSF excitation built here
///   -> 48 kHz audio, resampled back when the formant moved -> the input's loudness envelope.
/// Long audio goes in 15 s chunks with 1 s of context each side, cross-faded over 20 ms.
final class Rvc {
    /// Realtime pitch, as the RVC realtime GUI does it: RMVPE runs only over the newest audio and the older frames
    /// are kept from earlier steps. The newest stretch is 32k - 1 frames long, so RMVPE pads nothing. Over the whole
    /// window (padded up to a multiple of 32 frames) the played frames matched whole-file pitch less often (94.5% vs
    /// 97.7% within 50 cents, voicing differing in 8.6% vs 2.8% of frames, on the PC) and cost about five times more.
    final class PitchCache {
        /// Raw RMVPE pitch (Hz, 0 = unvoiced) for every frame of the window, before any shaping.
        fileprivate(set) var f0: [Double]
        /// 16 kHz samples RMVPE gets each step.
        let samples: Int

        init(frames: Int, block: Int) {
            f0 = [Double](repeating: 0, count: frames)
            samples = 5120 * ((block * 160 + 800 + 5119) / 5120) - 160
        }

        /// After skipping ahead (or restarting) the kept frames no longer line up: start over.
        func reset() {
            for i in 0..<f0.count { f0[i] = 0 }
        }

        fileprivate func advance(_ frames: Int) {
            let n = min(frames, f0.count)
            if n <= 0 { return }
            f0.removeFirst(n)
            f0 += [Double](repeating: 0, count: n)
        }
    }

    /// Where the decoder (the vocoder, which makes the sound) runs.
    /// split: its first three upsampling stages (88% of the work, which survive 16 bits) on the Neural Engine and the
    /// last stage with the output layer (which do not) on the GPU in 32 bits, from model.dec_a/dec_b.onnx. On the PC,
    /// 16 bits up to there moved the spectrum by 0.07-0.17 dB; the output layer alone in 16 bits by 3.4 dB.
    enum DecoderMode: String, CaseIterable {
        case cpu, gpu, split, npu

        var label: String {
            switch self {
            case .cpu: return "CPU"
            case .gpu: return "GPU"
            case .split: return "NPU+GPU"
            case .npu: return "NPU"
            }
        }

        var hint: String {
            switch self {
            case .cpu: return "32비트. 가장 느려요"
            case .gpu: return "32비트. CPU보다 4~5배 빠르고 소리는 같아요"
            case .split: return "앞부분(계산의 88%)은 NPU 16비트, 소리에 민감한 끝부분은 GPU 32비트. 모델 폴더에 dec_a·dec_b 파일이 있어야 해요 (변환 도구가 만들어요)"
            case .npu: return "전부 NPU 16비트. 비교용: 소리가 거칠어질 수 있어요"
            }
        }
    }

    struct Settings {
        var pitch = 0.0
        var formant = 0.0
        var lowRatio = 1.0
        var lowKnee = 120.0
        var highRatio = 1.0
        var highKnee = 260.0
        var unvoicedKeep = true
        var expand = false
        var expandRatio = 1.15
        var rmsMixRate = 0.0
    }

    static let sr = 48000
    static let sr16 = 16000
    static let unit = 320  // one HuBERT frame at 16 kHz
    static let chunkS = 15.0
    static let contextS = 1.0
    static let fadeS = 0.02
    static let unvoicedFillFrames = 4
    static let intonationCenterHz = 120.0
    /// The decoder always runs in pieces: in one go it needs over 1 GB for 17 s of audio, which gets the app killed.
    /// 4.3 s pieces on the CPU (0.2 GB, 8% extra work); 2 s on Core ML, which likes small fixed shapes.
    static let decFramesCPU = 432
    static let decFramesGPU = 200
    static let decContext = 16
    /// The formant slider's top (+2 semitones): realtime sizes its decoder input for it.
    static let maxFormantFactor = pow(2.0, 2.0 / 12)

    /// Where the decoder actually runs: what was asked for, unless Core ML could not load it (then the CPU) or the
    /// split pieces are missing (then the GPU).
    let decoderUsed: DecoderMode
    /// Whether HuBERT, RMVPE and enc run through Core ML with the Neural Engine allowed (16-bit there).
    let usesNPU: Bool
    /// Whether HuBERT, RMVPE and enc run through Core ML at all: on the Neural Engine, or (NPU off) Core ML on the
    /// CPU in 32 bits. ORT's own CPU kernels for them kept memory on the iPhone (about 40 MB a realtime step, and
    /// file conversions piled up until they ran out), so they are only the fallback when Core ML cannot load.
    let frontCoreML: Bool
    /// Seconds spent per stage since the engine was made: hubert, rmvpe, enc, dec, other.
    private(set) var timing = [Double](repeating: 0, count: 5)
    /// Bytes the app's footprint grew during each stage, same order: finds a stage that keeps memory.
    private(set) var growth = [Double](repeating: 0, count: 5)
    private let env: ORTEnv
    private let hubert: ORTSession
    private let rmvpe: ORTSession
    private let enc: ORTSession
    private let dec: ORTSession?
    private let decA: ORTSession?  // split: z, excitation -> hidden (Neural Engine)
    private let decB: ORTSession?  // split: hidden, excitation -> audio (GPU, 32 bits)
    private let rng = Gaussian(seed: 0)
    /// File conversion only: ORT returns the CPU arena's free blocks after every run instead of keeping them for the
    /// app's lifetime (CPU-only conversions were growing the footprint run after run).
    private var shrink: ORTRunOptions?
    private var runOptions: ORTRunOptions?

    /// decoder: see DecoderMode (the whole decoder on the Neural Engine, which computes in 16 bits, roughens the voice).
    /// npu: HuBERT, RMVPE and enc on Core ML with every unit allowed, the Neural Engine included (they only extract
    /// features, so 16 bits is fine there); off, Core ML on the CPU. Anything Core ML cannot take stays on the CPU.
    init(commonDir: URL, modelDir: URL, threads: Int, decoder: DecoderMode, npu: Bool) throws {
        let env = try ORTEnv(loggingLevel: .warning)
        self.env = env
        // coreML: 0 ORT on the CPU, 1 Core ML on CPU and GPU, 2 Core ML on any unit, 3 Core ML on the CPU only
        func options(_ coreML: Int) throws -> ORTSessionOptions {
            let o = try ORTSessionOptions()
            try o.setIntraOpNumThreads(Int32(threads))
            try o.setGraphOptimizationLevel(.all)
            if coreML > 0 {
                let c = ORTCoreMLExecutionProviderOptions()
                c.useCPUAndGPU = coreML == 1
                c.useCPUOnly = coreML == 3
                c.createMLProgram = true
                try o.appendCoreMLExecutionProvider(with: c)
            }
            return o
        }
        let plain = try options(0)
        func load(_ url: URL, _ coreML: Int) throws -> (session: ORTSession, accelerated: Bool) {
            if coreML > 0 && ORTIsCoreMLExecutionProviderAvailable(),
               let s = try? ORTSession(env: env, modelPath: url.path, sessionOptions: try options(coreML)) {
                return (s, true)
            }
            return (try ORTSession(env: env, modelPath: url.path, sessionOptions: plain), false)
        }
        let front = npu ? 2 : 3
        let h = try load(commonDir.appendingPathComponent("hubert.onnx"), front)
        let r = try load(commonDir.appendingPathComponent("rmvpe.onnx"), front)
        let e = try load(modelDir.appendingPathComponent("model.enc.onnx"), front)
        hubert = h.session
        rmvpe = r.session
        enc = e.session
        let pieceA = modelDir.appendingPathComponent("model.dec_a.onnx")
        let pieceB = modelDir.appendingPathComponent("model.dec_b.onnx")
        let fm = FileManager.default
        var mode = decoder
        if mode == .split && !(fm.fileExists(atPath: pieceA.path) && fm.fileExists(atPath: pieceB.path)) { mode = .gpu }
        if mode == .split {
            let a = try load(pieceA, 2)
            let b = try load(pieceB, 1)
            decA = a.session
            decB = b.session
            dec = nil
            decoderUsed = a.accelerated || b.accelerated ? .split : .cpu
        } else {
            let units: Int
            switch mode {
            case .gpu: units = 1
            case .npu: units = 2
            default: units = 0
            }
            let d = try load(modelDir.appendingPathComponent("model.dec.onnx"), units)
            dec = d.session
            decA = nil
            decB = nil
            decoderUsed = d.accelerated ? mode : .cpu
        }
        frontCoreML = h.accelerated || r.accelerated || e.accelerated
        usesNPU = npu && frontCoreML
        let o = try? ORTRunOptions()
        if let o, (try? o.addConfigEntry(withKey: "memory.enable_memory_arena_shrinkage", value: "cpu:0")) != nil {
            shrink = o
        } else {
            shrink = nil
        }
    }

    /// progress(done, total) is called after each chunk.
    func convert(_ x48: [Float], _ st: Settings, progress: (Int, Int) -> Void) throws -> [Float] {
        runOptions = shrink
        defer { runOptions = nil }
        let startMemory = Memory.footprint().now
        defer {
            Diagnostics.log(String(format: "convert done: memory %.2f -> %.2f GB", Double(startMemory) / 1e9,
                                   Double(Memory.footprint().now) / 1e9))
        }
        var x16 = Dsp.resample(x48, Rvc.sr, Rvc.sr16)
        x16 += [Float](repeating: 0, count: (Rvc.unit - x16.count % Rvc.unit) % Rvc.unit)
        // with the feature models on Core ML the windows stay short: preparing a 17 s input there takes more memory
        // than iOS allows (as QNN did on Android); 4 s of new audio per window is plenty fast
        let chunk = frontCoreML ? 4 * Rvc.sr16 : Int(Rvc.chunkS * Double(Rvc.sr16)) / Rvc.unit * Rvc.unit
        let ctx = Int(Rvc.contextS * Double(Rvc.sr16)) / Rvc.unit * Rvc.unit
        let fade = Int(Rvc.fadeS * Double(Rvc.sr16)) / Rvc.unit * Rvc.unit
        var out = [Float](repeating: 0, count: x16.count * 3)
        var weight = [Float](repeating: 0, count: out.count)
        let starts = Array(stride(from: 0, to: x16.count, by: chunk))
        Diagnostics.log(String(format: "convert: %.1f s in %ld windows, front %@, decoder %@", Double(x48.count) / Double(Rvc.sr),
                               starts.count, usesNPU ? "NPU" : frontCoreML ? "Core ML CPU" : "ORT CPU", decoderUsed.label))
        // Core ML builds and keeps an execution plan per input shape: with the feature models there, every window has
        // the same size (the last one reaches further back for real audio; only clips shorter than one are padded)
        let fixed = frontCoreML ? chunk + fade + 2 * ctx : 0
        for (i, start) in starts.enumerated() {
            let coreEnd = min(start + chunk + fade, x16.count)
            var lo = max(0, start - ctx)
            var hi = min(x16.count, coreEnd + ctx)
            if fixed > 0 {
                lo = max(0, hi - fixed)
                hi = min(x16.count, lo + fixed)
            }
            let segment = fixed > 0 ? Rvc.fit(Array(x16[lo..<hi]), fixed) : Array(x16[lo..<hi])
            let y = try autoreleasepool { try window(segment, st) }
            let memory = Memory.footprint()
            Diagnostics.log(String(format: "  window %ld/%ld done, memory %.2f GB (peak %.2f), %.2f GB left", i + 1, starts.count,
                                   Double(memory.now) / 1e9, Double(memory.peak) / 1e9, Double(Memory.available) / 1e9))
            // stop with a message rather than be ended by iOS
            if Memory.available < 300_000_000 {
                throw RvcError(String(format: "메모리가 부족해서 멈췄어요 (남은 메모리 %.0f MB)", Double(Memory.available) / 1e6))
            }
            let a = (start - lo) * 3
            let n = (coreEnd - lo) * 3 - a
            let f = fade * 3
            for k in 0..<n {
                var w: Float = 1
                if start > 0 && k < f { w = Float(k) / Float(f - 1) }
                if coreEnd < x16.count && k >= n - f { w = min(w, Float(n - 1 - k) / Float(f - 1)) }
                out[start * 3 + k] += y[a + k] * w
                weight[start * 3 + k] += w
            }
            progress(i + 1, starts.count)
        }
        var y = [Float](repeating: 0, count: x48.count)
        for k in 0..<min(y.count, out.count) { y[k] = out[k] / max(weight[k], 1e-6) }
        if st.rmsMixRate < 1 {  // follow the input's loudness envelope, as the studio does
            let rIn = Dsp.frameRms(x48)
            let rOut = Dsp.frameRms(y)
            let power = Float(1 - st.rmsMixRate)
            for k in 0..<y.count { y[k] *= pow(rIn[k] / max(rOut[k], 1e-3), power) }
        }
        for k in 0..<y.count { y[k] = y[k].isNaN ? 0 : min(max(y[k], -1), 1) }
        return y
    }

    /// One window of 16 kHz audio (a multiple of 320 samples) -> 48 kHz audio of the same duration.
    private func window(_ x16: [Float], _ st: Settings) throws -> [Float] {
        var t0 = DispatchTime.now().uptimeNanoseconds
        var m0 = Double(Memory.footprint().now)
        func lap(_ stage: Int) {
            let t = DispatchTime.now().uptimeNanoseconds
            timing[stage] += Double(t - t0) / 1e9
            t0 = t
            let m = Double(Memory.footprint().now)
            growth[stage] += m - m0
            m0 = m
        }
        let (z, f0, pLen) = try analyze(x16, st, lap: lap)
        let factor = pow(2.0, st.formant / 12)
        let n = Int(ceil(Double(pLen) * factor))
        let zn = Dsp.stretchLinear(z, channels: 192, t: pLen, n: n)
        let f0n = Dsp.stretchNearest(f0, n: n)  // no n/pLen factor: the excitation is built at n frames directly
        let sine = Dsp.nsfSource(f0n, rng: rng)
        lap(4)
        Diagnostics.setStage("decoder")
        var audio = try decode(zn, n: n, sine: sine)
        lap(3)
        let upp = Int(floor(factor * 480))
        if upp != 480 { audio = Dsp.resample(Array(audio.prefix(pLen * upp)), upp, 480) }
        audio = Rvc.fit(audio, pLen * 480)
        lap(4)
        return audio
    }

    /// Realtime: one 16 kHz window (past context plus the newest audio, a multiple of 320 samples) -> 48 kHz audio for
    /// its last `tail` frames. Only those frames, with decContext frames before them, go through the decoder.
    /// `phase` carries the excitation's phase to the next window, whose decoder starts `advance` frames later, so the
    /// harmonics run on without a jump.
    func streamWindow(_ x16: [Float], _ st: Settings, tail: Int, advance: Int, phase: inout Double,
                      pitch: PitchCache? = nil) throws -> [Float] {
        var t0 = DispatchTime.now().uptimeNanoseconds
        var m0 = Double(Memory.footprint().now)
        func lap(_ stage: Int) {
            let t = DispatchTime.now().uptimeNanoseconds
            timing[stage] += Double(t - t0) / 1e9
            t0 = t
            let m = Double(Memory.footprint().now)
            growth[stage] += m - m0
            m0 = m
        }
        pitch?.advance(advance)
        let (z, f0, pLen) = try analyze(x16, st, pitch: pitch, lap: lap)
        // the decoder always gets the same number of frames, whatever the formant: Core ML prepares a new plan for
        // every new shape, which stalled the stream for several blocks each time the formant slider moved. A lower
        // formant simply takes more left context (it is cut off again below).
        let fixed = Int(ceil(Double(tail + Rvc.decContext) * Rvc.maxFormantFactor))
        let factor = pow(2.0, st.formant / 12)
        let d = min(pLen, max(tail, Int(floor(Double(fixed) / factor))))
        var zd = [Float](repeating: 0, count: 192 * d)
        for c in 0..<192 {
            zd.replaceSubrange(c * d..<(c + 1) * d, with: z[c * pLen + pLen - d..<c * pLen + pLen])
        }
        let n = Int(ceil(Double(d) * factor))
        let zn = Dsp.stretchLinear(zd, channels: 192, t: d, n: n)
        let f0n = Dsp.stretchNearest(Array(f0[(pLen - d)...]), n: n)
        let mark = Int((Double(advance) * factor * 480).rounded())
        let source = Dsp.nsfSource(f0n, rng: rng, startPhase: phase, markAt: mark)
        phase = source.phaseAtMark
        // pad on the left up to the fixed size (that part is context and gets dropped)
        let pad = max(0, fixed - n)
        var zp = zn
        var sp = source.samples
        if pad > 0 {
            zp = [Float](repeating: 0, count: 192 * (n + pad))
            for c in 0..<192 {
                zp.replaceSubrange(c * (n + pad) + pad..<(c + 1) * (n + pad), with: zn[c * n..<(c + 1) * n])
            }
            sp = [Float](repeating: 0, count: pad * 480) + source.samples
        }
        lap(4)
        Diagnostics.setStage("decoder (realtime)")
        var audio = try runDec(zp, frames: n + pad, sine: sp)
        if pad > 0 { audio = Array(audio[(pad * 480)...]) }
        lap(3)
        let upp = Int(floor(factor * 480))
        if upp != 480 { audio = Dsp.resample(Array(audio.prefix(d * upp)), upp, 480) }
        audio = Rvc.fit(audio, d * 480)
        lap(4)
        let keep = (d - min(tail, d)) * 480  // drop the decoder's left context
        return Array(audio[keep...])
    }

    static func fit(_ x: [Float], _ count: Int) -> [Float] {
        x.count >= count ? Array(x.prefix(count)) : x + [Float](repeating: 0, count: count - x.count)
    }

    /// HuBERT, RMVPE, pitch shaping and enc over one 16 kHz window: the latent z [192, pLen] and the shifted f0.
    private func analyze(_ x16: [Float], _ st: Settings, pitch cache: PitchCache? = nil,
                         lap: (Int) -> Void) throws -> (z: [Float], f0: [Double], pLen: Int) {
        let pLen = x16.count / 160
        // content: 50 fps, last frame repeated, each frame doubled to 100 fps
        Diagnostics.setStage("HuBERT")
        let feats50 = try run(hubert, ["audio": tensor(x16, [1, x16.count])])
        lap(0)
        let f50 = feats50.count / 768
        var phone = [Float](repeating: 0, count: pLen * 768)
        for t in 0..<pLen {
            let src = min(min(t / 2, f50), f50 - 1)  // index f50 is the appended copy of the last frame
            phone.replaceSubrange(t * 768..<(t + 1) * 768, with: feats50[src * 768..<(src + 1) * 768])
        }
        // pitch
        Diagnostics.setStage("RMVPE")
        var f0: [Double]
        if let cache, cache.f0.count == pLen, cache.samples < x16.count {
            // only the newest audio: its frame j is window frame pLen - k + j; the first 3 and the very last lack
            // context and are left to the neighbouring steps (RVC's cache_pitch[4 - n:] = pitch[3:-1])
            let k = cache.samples / 160
            let newest = Array(x16[(x16.count - cache.samples)...])
            let salience = try run(rmvpe, ["audio": tensor(newest, [1, newest.count])])
            let part = Dsp.decodeF0(salience, frames: salience.count / 360)
            for j in stride(from: 3, to: min(k, part.count), by: 1) { cache.f0[pLen - k + j] = part[j] }
            f0 = cache.f0
        } else {
            let salience = try run(rmvpe, ["audio": tensor(x16, [1, x16.count])])
            f0 = Dsp.decodeF0(salience, frames: salience.count / 360)
            f0 = f0.count >= pLen ? Array(f0.prefix(pLen)) : f0 + [Double](repeating: 0, count: pLen - f0.count)
        }
        lap(1)
        f0 = Dsp.fillUnvoiced(f0, maxGap: st.unvoicedKeep ? Rvc.unvoicedFillFrames : nil)
        f0 = Dsp.shapePitch(f0, lowKnee: st.lowKnee, lowRatio: st.lowRatio, highKnee: st.highKnee, highRatio: st.highRatio,
                            center: Rvc.intonationCenterHz, expand: st.expand ? st.expandRatio : 1)
        let key = pow(2.0, (st.pitch - st.formant) / 12)  // the formant moves by time stretch; pitch compensates
        f0 = f0.map { $0 * key }
        let coarse = f0.map { Dsp.coarse($0) }
        let noise = (0..<(192 * pLen)).map { _ in Float(rng.next()) }
        lap(4)
        Diagnostics.setStage("enc")
        let z = try run(enc, ["phone": tensor(phone, [1, pLen, 768]), "pitch": tensor(coarse, [1, pLen]),
                              "noise": tensor(noise, [1, 192, pLen])])
        lap(2)
        return (z, f0, pLen)
    }

    private func runDec(_ z: [Float], frames: Int, sine: [Float]) throws -> [Float] {
        if let decA, let decB {
            let hidden = try run(decA, ["z": tensor(z, [1, 192, frames]), "sine": tensor(sine, [1, sine.count, 1])])
            let length = frames * 240  // the hidden signal runs at 24 kHz: 240 samples a frame
            return try run(decB, ["hidden": tensor(hidden, [1, hidden.count / length, length]),
                                  "sine": tensor(sine, [1, sine.count, 1])])
        }
        guard let dec else { throw RvcError("디코더가 없어요") }
        return try run(dec, ["z": tensor(z, [1, 192, frames]), "sine": tensor(sine, [1, sine.count, 1])])
    }

    /// The decoder over n frames in fixed windows with decContext frames of context each side. The decoder is
    /// local, so the pieces join exactly (checked on the PC: >100 dB against a one-shot decode).
    private func decode(_ z: [Float], n: Int, sine: [Float]) throws -> [Float] {
        // Core ML keeps one fixed shape; the CPU can shrink the window for short clips
        let frames = decoderUsed != .cpu ? Rvc.decFramesGPU : min(Rvc.decFramesCPU, n + 2 * Rvc.decContext)
        let context = Rvc.decContext
        let hop = frames - 2 * context
        var out = [Float](repeating: 0, count: n * 480)
        var start = 0
        while start < n {
            let lo = start - context
            var zz = [Float](repeating: 0, count: 192 * frames)
            var ss = [Float](repeating: 0, count: frames * 480)
            let a = max(lo, 0)
            let b = min(lo + frames, n)
            for c in 0..<192 {
                zz.replaceSubrange(c * frames + (a - lo)..<c * frames + (b - lo), with: z[c * n + a..<c * n + b])
            }
            ss.replaceSubrange((a - lo) * 480..<(b - lo) * 480, with: sine[a * 480..<b * 480])
            let y = try runDec(zz, frames: frames, sine: ss)
            let take = min(hop, n - start)
            out.replaceSubrange(start * 480..<(start + take) * 480, with: y[context * 480..<(context + take) * 480])
            start += hop
        }
        return out
    }

    private func tensor(_ x: [Float], _ shape: [Int]) throws -> ORTValue {
        let data = x.withUnsafeBufferPointer { NSMutableData(bytes: $0.baseAddress, length: $0.count * MemoryLayout<Float>.size) }
        return try ORTValue(tensorData: data, elementType: .float, shape: shape.map { NSNumber(value: $0) })
    }

    private func tensor(_ x: [Int64], _ shape: [Int]) throws -> ORTValue {
        let data = x.withUnsafeBufferPointer { NSMutableData(bytes: $0.baseAddress, length: $0.count * MemoryLayout<Int64>.size) }
        return try ORTValue(tensorData: data, elementType: .int64, shape: shape.map { NSNumber(value: $0) })
    }

    /// Runs a session and returns its first output as floats.
    /// The pool frees ORT's output tensors right away instead of when the whole conversion ends.
    private func run(_ session: ORTSession, _ inputs: [String: ORTValue]) throws -> [Float] {
        try autoreleasepool {
            guard let name = try session.outputNames().first else { throw RvcError("출력이 없는 모델이에요") }
            let outputs: [String: ORTValue]
            do {
                outputs = try session.run(withInputs: inputs, outputNames: [name], runOptions: runOptions)
            } catch where runOptions != nil {
                // this build of ORT cannot shrink its arena: run without asking from now on
                Diagnostics.log("arena shrinking off: " + error.localizedDescription)
                runOptions = nil
                shrink = nil
                outputs = try session.run(withInputs: inputs, outputNames: [name], runOptions: nil)
            }
            guard let value = outputs[name] else { throw RvcError("모델이 결과를 내지 않았어요") }
            let data = try value.tensorData()
            var out = [Float](repeating: 0, count: data.length / MemoryLayout<Float>.size)
            out.withUnsafeMutableBytes { raw in
                if let p = raw.baseAddress { data.getBytes(p, length: raw.count) }
            }
            return out
        }
    }
}

struct RvcError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}
