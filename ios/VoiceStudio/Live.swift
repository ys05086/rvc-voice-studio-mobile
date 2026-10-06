import AVFoundation
import Foundation
import os

/// Realtime settings, phone-wide. Frames are 10 ms.
struct LiveParams {
    var block = 20          // new audio per step: the converted voice comes out in pieces this long
    var context = 100       // past audio the content and pitch models see with each step
    var crossfade = 8       // overlap blended between steps
    var search = 1          // how far SOLA may shift a step to line its waveform up with the last one
    var cushion = 0.06      // seconds of output kept queued, against uneven compute times
    var thresholdDb = -60.0 // quieter input than this comes out silent
    var gainDb = 12.0       // output boost; the input follows a quiet, unprocessed microphone
    var inputID = ""        // the chosen microphone (port uid, "#data source id" for one of the iPhone's own); "" = iOS picks
    var speaker = false     // out through the loudspeaker instead of the earpiece
    var bluetoothMic = false // allow Bluetooth headset microphones (the hands-free profile: 16 kHz both ways)
    var bypass = false      // diagnosis: skip the models and pass the microphone through

    /// The audio session for realtime use, and for listing the microphones it offers.
    static func configureSession(bluetoothMic: Bool) throws {
        var options: AVAudioSession.CategoryOptions = [.allowBluetoothA2DP]
        if bluetoothMic { options.insert(.allowBluetooth) }
        // measurement: no automatic gain or noise suppression
        try AVAudioSession.sharedInstance().setCategory(.playAndRecord, mode: .measurement, options: options)
    }

    /// Microphones to choose from; the iPhone's own come once per direction (front, back, bottom).
    static func inputChoices() -> [(id: String, name: String)] {
        var list: [(id: String, name: String)] = [("", "자동 (iOS가 고름)")]
        for port in AVAudioSession.sharedInstance().availableInputs ?? [] {
            if port.portType == .builtInMic, let sources = port.dataSources, !sources.isEmpty {
                for source in sources {
                    list.append((port.uid + "#" + source.dataSourceID.stringValue, "아이폰 마이크 · " + side(source.dataSourceName)))
                }
            } else {
                list.append((port.uid, port.portName + (port.portType == .bluetoothHFP ? " (통화 음질)" : "")))
            }
        }
        return list
    }

    private static func side(_ name: String) -> String {
        switch name.lowercased() {
        case "front": return "앞"
        case "back": return "뒤"
        case "bottom": return "아래"
        case "top": return "위"
        default: return name
        }
    }

    /// Prefers the chosen microphone (and direction) for the active session; a missing one leaves iOS's choice.
    func applyInput() {
        guard !inputID.isEmpty else { return }
        let session = AVAudioSession.sharedInstance()
        let parts = inputID.split(separator: "#", maxSplits: 1).map(String.init)
        guard let port = session.availableInputs?.first(where: { $0.uid == parts[0] }) else { return }
        try? session.setPreferredInput(port)
        if parts.count > 1, let source = port.dataSources?.first(where: { $0.dataSourceID.stringValue == parts[1] }) {
            try? port.setPreferredDataSource(source)
        }
    }
}

/// Samples shared between an audio thread and the worker: a circular store addressed by absolute position, also
/// readable first-in first-out. Short critical sections under an unfair lock, so the audio threads never wait long.
final class SampleRing {
    let capacity: Int
    private let data: UnsafeMutablePointer<Float>
    private let lock: UnsafeMutablePointer<os_unfair_lock>
    private var written = 0
    private var readPos = 0

    init(capacity: Int) {
        self.capacity = capacity
        data = UnsafeMutablePointer<Float>.allocate(capacity: capacity)
        data.initialize(repeating: 0, count: capacity)
        lock = UnsafeMutablePointer<os_unfair_lock>.allocate(capacity: 1)
        lock.initialize(to: os_unfair_lock())
    }

    deinit {
        data.deallocate()
        lock.deallocate()
    }

    func write(_ p: UnsafePointer<Float>, _ n: Int) {
        os_unfair_lock_lock(lock)
        for i in 0..<n { data[(written + i) % capacity] = p[i] }
        written += n
        if written - readPos > capacity { readPos = written - capacity }
        os_unfair_lock_unlock(lock)
    }

    func write(_ x: [Float]) {
        x.withUnsafeBufferPointer { if let p = $0.baseAddress { write(p, $0.count) } }
    }

    /// First-in first-out: up to n samples into p; returns how many there were.
    func read(_ p: UnsafeMutablePointer<Float>, _ n: Int) -> Int {
        os_unfair_lock_lock(lock)
        let k = min(n, written - readPos)
        for i in 0..<k { p[i] = data[(readPos + i) % capacity] }
        readPos += k
        os_unfair_lock_unlock(lock)
        return k
    }

    /// Samples [start, start + n) by absolute position; positions not stored (yet, or any more) read as zeros.
    func copy(from start: Int, count n: Int) -> [Float] {
        var out = [Float](repeating: 0, count: n)
        os_unfair_lock_lock(lock)
        let oldest = max(0, written - capacity)
        for i in 0..<n {
            let pos = start + i
            if pos >= oldest && pos < written { out[i] = data[pos % capacity] }
        }
        os_unfair_lock_unlock(lock)
        return out
    }

    var total: Int {
        os_unfair_lock_lock(lock)
        defer { os_unfair_lock_unlock(lock) }
        return written
    }

    var queued: Int {
        os_unfair_lock_lock(lock)
        defer { os_unfair_lock_unlock(lock) }
        return written - readPos
    }

    /// Drops the oldest queued samples so at most `keep` remain; returns how many went.
    @discardableResult
    func trim(keeping keep: Int) -> Int {
        os_unfair_lock_lock(lock)
        defer { os_unfair_lock_unlock(lock) }
        let cut = max(0, written - readPos - keep)
        readPos += cut
        return cut
    }
}

/// What the earphones missed. The audio thread adds to it, so it has a tiny lock of its own.
final class UnderrunMeter {
    private let lock: UnsafeMutablePointer<os_unfair_lock>
    private var armed = false
    private var events = 0
    private var samples = 0

    init() {
        lock = UnsafeMutablePointer<os_unfair_lock>.allocate(capacity: 1)
        lock.initialize(to: os_unfair_lock())
    }

    deinit { lock.deallocate() }

    /// Counts only once converted audio was queued: the silence before that is expected.
    func arm() {
        os_unfair_lock_lock(lock)
        armed = true
        os_unfair_lock_unlock(lock)
    }

    func add(missing n: Int) {
        os_unfair_lock_lock(lock)
        if armed {
            events += 1
            samples += n
        }
        os_unfair_lock_unlock(lock)
    }

    /// Times the earphones ran dry, and the samples of silence they got instead.
    var totals: (events: Int, samples: Int) {
        os_unfair_lock_lock(lock)
        defer { os_unfair_lock_unlock(lock) }
        return (events, samples)
    }
}

/// Realtime conversion: the microphone in, the converted voice out to the earphones, in steps of `block` frames.
///
/// Each step takes the newest block plus `context` frames of past audio, runs HuBERT, RMVPE and enc over all of it,
/// and decodes only the last block + crossfade + search frames (Rvc.streamWindow). The result is lined up with the
/// previous step by SOLA and cross-faded over `crossfade` frames, as the RVC realtime GUI does. Output follows the
/// input's loudness (rms_mix_rate) and goes quiet under the threshold.
///
/// Every step leaves a line in Documents/live_blocks.csv (the previous session's in live_blocks_prev.csv), and the
/// session ends with a summary in live_log.txt: which stage was slow, when the earphones ran dry, what was skipped.
final class LiveEngine {
    struct Stats {
        var block = 0.0         // seconds of audio per step
        var compute = 0.0       // seconds per step, smoothed
        var computeMax = 0.0    // over the last ~5 s
        var latency = 0.0       // input to output, smoothed
        var level: Float = 0    // input peak of the last step
        var steps = 0
        var drops = 0           // times the worker fell too far behind and skipped ahead
        var underruns = 0       // times the earphones ran out of audio
        var clips = 0           // steps whose output had to be bent down by the soft clip (too loud)
        var restarts = 0        // times iOS stopped the audio and it was started again
        var extraCushion = 0.0  // seconds of queue added after underruns
        var memory = 0.0        // the app's footprint, GB
        var memoryLimit = 0.0   // footprint at which iOS would end the app, GB
        var device = ""         // where the models run
        var thermal = 0         // ProcessInfo.ThermalState: 0 nominal, 1 fair, 2 serious, 3 critical
        var inPeaks: [Float] = []   // the last few seconds, one peak per 20 ms, for the scrolling waveforms
        var outPeaks: [Float] = []
        var recording = -1.0    // seconds recorded so far; -1 when not recording
    }

    static let blocksURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("live_blocks.csv")
    static let previousBlocksURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("live_blocks_prev.csv")

    let params: LiveParams
    private let engine = AVAudioEngine()
    private var sink: AVAudioSinkNode?
    private let mic = SampleRing(capacity: 48000 * 8)
    private let out = SampleRing(capacity: 48000 * 4)
    private let underruns = UnderrunMeter()
    private let wake = DispatchSemaphore(value: 0)
    private let state = NSLock()
    private var running = false
    private var restartNow = false  // iOS changed the audio configuration or ended an interruption
    private var observers: [NSObjectProtocol] = []
    private var stats = Stats()
    private var settings: Rvc.Settings
    private var gain: Float = 1
    private var rate = 48000  // the microphone's sample rate (16 kHz for a Bluetooth headset)
    private(set) var failure: String?
    // Files are written on their own queue, so a slow flash write never holds up a step.
    private let io = DispatchQueue(label: "live-io", qos: .utility)
    private var recIn: WavWriter?      // only touched on io
    private var recOut: WavWriter?     // only touched on io
    private var blockLog: FileHandle?  // only touched on io
    private var recording = false      // under state
    private var recSamples = 0         // under state

    init(params: LiveParams, settings: Rvc.Settings) {
        self.params = params
        self.settings = settings
        self.gain = Float(pow(10, params.gainDb / 20))
    }

    var isRunning: Bool {
        state.lock()
        defer { state.unlock() }
        return running
    }

    func snapshot() -> Stats {
        state.lock()
        defer { state.unlock() }
        return stats
    }

    /// Output boost in dB, from the next step.
    func setGain(_ db: Double) {
        state.lock()
        gain = Float(pow(10, db / 20))
        state.unlock()
    }

    /// Above 0.8 the peaks bend smoothly towards 1 instead of clipping.
    static func softClip(_ v: Float) -> Float {
        let a = abs(v)
        if !(a > 0.8) { return v }
        return (v < 0 ? -1 : 1) * (0.8 + 0.2 * tanh((a - 0.8) / 0.2))
    }

    /// Voice settings apply from the next step.
    func update(_ s: Rvc.Settings) {
        state.lock()
        settings = s
        state.unlock()
    }

    /// Starts writing the microphone and the converted voice to two WAV files, from the next step.
    func startRecording(input: URL, output: URL) throws {
        let a = try WavWriter(url: input)
        let b = try WavWriter(url: output)
        io.sync {
            recIn = a
            recOut = b
        }
        state.lock()
        recording = true
        recSamples = 0
        state.unlock()
    }

    /// Closes the files once everything queued is written; nil when nothing was being recorded.
    func stopRecording() -> (input: URL, output: URL, seconds: Double)? {
        state.lock()
        recording = false
        state.unlock()
        return io.sync { () -> (input: URL, output: URL, seconds: Double)? in
            guard let a = recIn, let b = recOut else { return nil }
            recIn = nil
            recOut = nil
            a.finish()
            b.finish()
            return (a.url, b.url, Double(b.samples) / Double(Rvc.sr))
        }
    }

    func start() throws {
        let session = AVAudioSession.sharedInstance()
        try LiveParams.configureSession(bluetoothMic: params.bluetoothMic)
        try? session.setPreferredSampleRate(48000)
        try? session.setPreferredIOBufferDuration(0.005)
        try session.setActive(true)
        params.applyInput()
        try? session.overrideOutputAudioPort(params.speaker ? .speaker : .none)
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        rate = try LiveEngine.micRate(format)
        let mic = self.mic
        let wake = self.wake
        let sink = AVAudioSinkNode { _, frames, list in
            let buffers = UnsafeMutableAudioBufferListPointer(UnsafeMutablePointer(mutating: list))
            if let p = buffers.first?.mData?.assumingMemoryBound(to: Float.self) { mic.write(p, Int(frames)) }
            wake.signal()
            return noErr
        }
        let mono = AVAudioFormat(standardFormatWithSampleRate: 48000, channels: 1)!
        let out = self.out
        let meter = underruns
        let source = AVAudioSourceNode(format: mono) { _, _, frames, list in
            let buffers = UnsafeMutableAudioBufferListPointer(list)
            guard let p = buffers.first?.mData?.assumingMemoryBound(to: Float.self) else { return noErr }
            let n = Int(frames)
            let got = out.read(p, n)
            if got < n {
                for i in got..<n { p[i] = 0 }
                meter.add(missing: n - got)
            }
            return noErr
        }
        engine.attach(sink)
        engine.attach(source)
        engine.connect(input, to: sink, format: format)
        engine.connect(source, to: engine.mainMixerNode, format: mono)
        self.sink = sink
        engine.prepare()
        state.lock()
        running = true
        state.unlock()
        try engine.start()
        observe()
    }

    private static func micRate(_ format: AVAudioFormat) throws -> Int {
        let r = Int(format.sampleRate.rounded())
        guard r > 0, r % 100 == 0 else { throw RvcError("마이크가 \(r) Hz로 열렸어요. 이 샘플레이트는 아직 안 돼요") }
        return r
    }

    /// iOS stops the engine when the route or the sample rate changes, and pauses it for calls, Siri and alarms.
    private func observe() {
        let center = NotificationCenter.default
        let changed = center.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: nil) { [weak self] _ in
            Diagnostics.log("audio configuration changed (route or sample rate)")
            self?.askRestart()
        }
        let interrupted = center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: nil) { [weak self] note in
            let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            let began = raw.flatMap { AVAudioSession.InterruptionType(rawValue: $0) } == .began
            Diagnostics.log(began ? "audio interruption began (a call, Siri, an alarm...)" : "audio interruption ended")
            if !began { self?.askRestart() }
        }
        state.lock()
        observers = [changed, interrupted]
        state.unlock()
    }

    private func askRestart() {
        state.lock()
        restartNow = true
        state.unlock()
        wake.signal()
    }

    /// Starts the audio again after iOS stopped it; returns the microphone's (possibly new) sample rate.
    private func restartAudio() throws -> Int {
        let session = AVAudioSession.sharedInstance()
        engine.stop()
        try session.setActive(true)
        params.applyInput()
        try? session.overrideOutputAudioPort(params.speaker ? .speaker : .none)
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        let r = try LiveEngine.micRate(format)
        if let sink {
            engine.disconnectNodeInput(sink)
            engine.connect(input, to: sink, format: format)
        }
        engine.prepare()
        try engine.start()
        return r
    }

    func stop() {
        state.lock()
        running = false
        let gone = observers
        observers = []
        state.unlock()
        gone.forEach { NotificationCenter.default.removeObserver($0) }
        wake.signal()
        engine.stop()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    /// Runs a few silent steps before the audio starts: Core ML prepares each new input shape on its first run, which
    /// takes several block lengths and would otherwise make the session open with drops.
    func warmUp(_ rvc: Rvc) {
        var c = params.context
        if (c + params.crossfade + params.search + params.block) % 2 == 1 { c += 1 }
        let w = c + params.crossfade + params.search + params.block
        var phase = 0.0
        let silence = [Float](repeating: 0, count: w * 160)
        let pitch = Rvc.PitchCache(frames: w, block: params.block)  // also prepares RMVPE's short input shape
        state.lock()
        let st = settings
        state.unlock()
        for i in 0..<3 {
            let t0 = DispatchTime.now().uptimeNanoseconds
            _ = try? autoreleasepool {
                try rvc.streamWindow(silence, st, tail: params.crossfade + params.search + params.block,
                                     advance: params.block, phase: &phase, pitch: pitch)
            }
            Diagnostics.log(String(format: "warm-up %ld: %.3f s", i + 1, Double(DispatchTime.now().uptimeNanoseconds - t0) / 1e9))
        }
    }

    private static let csvHeader = "t_s,step,backlog_ms,compute_ms,prep_ms,hubert_ms,rmvpe_ms,enc_ms,dec_ms,other_ms,post_ms,"
        + "queued_ms,underruns,underrun_ms,drops,trimmed_ms,extra_ms,sola,thermal,memory_mb"

    /// The previous session's block log moves aside; a new one starts with the settings and the column names.
    private func openBlockLog(_ first: String) {
        io.sync {
            let fm = FileManager.default
            try? fm.removeItem(at: LiveEngine.previousBlocksURL)
            try? fm.moveItem(at: LiveEngine.blocksURL, to: LiveEngine.previousBlocksURL)
            fm.createFile(atPath: LiveEngine.blocksURL.path, contents: Data(("# " + first + "\n" + LiveEngine.csvHeader + "\n").utf8))
            blockLog = try? FileHandle(forWritingTo: LiveEngine.blocksURL)
            _ = try? blockLog?.seekToEnd()
        }
    }

    private func writeBlockLog(_ text: String, close: Bool = false) {
        io.async { [self] in
            if !text.isEmpty { blockLog?.write(Data(text.utf8)) }
            if close {
                try? blockLog?.close()
                blockLog = nil
            }
        }
    }

    /// The worker loop; returns once stopped. Runs on the queue that owns `rvc`.
    func run(_ rvc: Rvc) {
        let b = params.block
        let x = params.crossfade
        let s = params.search
        var c = params.context
        if (c + x + s + b) % 2 == 1 { c += 1 }  // HuBERT steps in 20 ms
        let w = c + x + s + b                    // frames the front models see
        let tail = x + s + b                     // frames decoded
        let b48 = b * 480
        let w48 = w * 480
        let sola = x * 480
        let fadeIn: [Float] = (0..<sola).map { i in
            let v = sin(0.5 * Double.pi * Double(i) / Double(max(sola, 1)))
            return Float(v * v)
        }
        let cushion = Int(params.cushion * 48000)
        let threshold = Float(pow(10, params.thresholdDb / 20))
        // the microphone's rate can change when the audio restarts (a Bluetooth headset connected, say)
        var micRate = rate
        var bIn = b * micRate / 100
        var wIn = w * micRate / 100
        var inPeaks: [Float] = []
        var outPeaks: [Float] = []
        var consumed = mic.total
        var phase = 0.0
        let pitch = Rvc.PitchCache(frames: w, block: b)
        var previous: [Float]?
        var gate: Float = 0
        var clippedNow = false
        var primed = false
        var lastUnder = underruns.totals
        var extraCushion = 0
        var holdLeft = 0
        let gateHold = 48000 * 3 / 10
        let gateAttack = Float(1) / Float(48000 * 5 / 1000)
        let gateRelease = Float(1) / Float(48000 * 50 / 1000)
        var recent: [Double] = []
        var drops = 0
        var restarts = 0
        var trimmed = 0
        var computeAll: [Double] = []  // every step, for the summary
        var worst: [(took: Double, text: String)] = []
        var csv = ""
        let front: String = rvc.usesNPU ? "NPU" : "CPU"
        let back: String = rvc.decoderUsed.label
        let device: String = params.bypass ? "통과 (모델 안 씀)" : "앞 단계 \(front) · 디코더 \(back)"
        state.lock()
        stats.block = Double(b) / 100
        stats.device = device
        state.unlock()
        let begun = DispatchTime.now().uptimeNanoseconds
        var lastLog = begun
        var lastMicTotal = consumed
        var lastMicAt = begun
        var lastRestart: UInt64 = 0
        var lastGrowth = rvc.growth
        var lastTiming = rvc.timing
        var lastTotal = Double(Memory.footprint().now)
        let setup = "block \(b) context \(c) crossfade \(x) search \(s) cushion \(params.cushion) threshold \(params.thresholdDb) "
            + "gain \(params.gainDb) decoder \(rvc.decoderUsed.rawValue) npu \(rvc.usesNPU) bypass \(params.bypass) mic \(micRate) Hz "
            + "pitch \(pitch.samples) samples"
        Diagnostics.log("live start: " + setup)
        openBlockLog(setup)
        func ms(_ ns: UInt64) -> Double { Double(ns) / 1e6 }
        func f1(_ v: Double) -> String { String(format: "%.1f", v) }
        defer {
            writeBlockLog(csv, close: true)
            let seconds = Double(DispatchTime.now().uptimeNanoseconds - begun) / 1e9
            let sorted = computeAll.sorted()
            let mean = sorted.isEmpty ? 0 : sorted.reduce(0, +) / Double(sorted.count)
            let p95 = sorted.isEmpty ? 0 : sorted[min(sorted.count - 1, sorted.count * 95 / 100)]
            let u = underruns.totals
            Diagnostics.log(String(format: "live summary: %.0f s, %ld steps, compute mean %.3f p95 %.3f max %.3f for a %.2f s block (load %.0f%%)",
                                   seconds, sorted.count, mean, p95, sorted.last ?? 0, Double(b) / 100, mean / (Double(b) / 100) * 100))
            Diagnostics.log(String(format: "  earphones ran dry %ld times (%.2f s of silence), skipped ahead %ld times, dropped %.2f s of queued output, audio restarted %ld times",
                                   u.events, Double(u.samples) / 48000, drops, Double(trimmed) / 48000, restarts))
            for (i, item) in worst.enumerated() { Diagnostics.log("  slowest \(i + 1): " + item.text) }
            Diagnostics.log("live stop" + (failure.map { ": " + $0 } ?? ""))
        }
        while isRunning {
            _ = wake.wait(timeout: .now() + .milliseconds(100))
            // the microphone went quiet or iOS said the audio changed: start the audio again, skipping what was missed
            let checked = DispatchTime.now().uptimeNanoseconds
            let total = mic.total
            if total != lastMicTotal {
                lastMicTotal = total
                lastMicAt = checked
            }
            state.lock()
            let asked = restartNow
            restartNow = false
            state.unlock()
            if isRunning && (asked || checked - lastMicAt > 500_000_000) && checked - lastRestart > 1_000_000_000 {
                lastRestart = checked
                restarts += 1
                Diagnostics.log(String(format: "restarting the audio (%@; microphone silent for %.2f s)",
                                       asked ? "iOS changed the audio" : "watchdog", Double(checked - lastMicAt) / 1e9))
                do {
                    let r = try restartAudio()
                    if r != micRate {
                        Diagnostics.log("microphone rate \(micRate) -> \(r) Hz")
                        micRate = r
                        bIn = b * r / 100
                        wIn = w * r / 100
                    }
                } catch {
                    Diagnostics.log("audio restart failed: \(error.localizedDescription)")
                }
                consumed = mic.total
                pitch.reset()
                lastMicTotal = consumed
                lastMicAt = DispatchTime.now().uptimeNanoseconds
                state.lock()
                stats.restarts = restarts
                state.unlock()
                continue
            }
            while isRunning {
                let have = mic.total
                if have - consumed < bIn { break }
                state.lock()
                let recordingNow = recording
                state.unlock()
                // fell too far behind: skip to the newest step. While recording, wait up to 2 s first so the recording
                // stays whole; the earphones meanwhile catch up by dropping queued output instead.
                let limit = recordingNow ? max(3 * bIn, 2 * micRate) : 3 * bIn
                if have - consumed > limit {
                    let skipped = have - bIn - consumed
                    // the recorded original runs (x + s) frames behind the newest audio (see `heard` below)
                    let missed = mic.copy(from: consumed - (tail - b) * micRate / 100, count: skipped)
                    consumed = have - bIn
                    pitch.reset()
                    drops += 1
                    state.lock()
                    stats.drops = drops
                    state.unlock()
                    if recordingNow {  // keep the recording in time: the original keeps the audio, the converted side gets silence
                        let raw = micRate == 48000 ? missed : Dsp.resample(missed, micRate, 48000)
                        let gap = [Float](repeating: 0, count: raw.count)
                        state.lock()
                        recSamples += raw.count
                        state.unlock()
                        io.async { [self] in
                            recIn?.append(raw)
                            recOut?.append(gap)
                        }
                    }
                    Diagnostics.log(String(format: "drop: fell %.2f s behind, skipped ahead", Double(skipped) / Double(micRate)))
                }
                let backlog = have - consumed - bIn  // what waits behind this step
                let end = consumed + bIn
                consumed = end
                let started = DispatchTime.now().uptimeNanoseconds
                let before = rvc.timing
                state.lock()
                let st = settings
                let boost = gain
                state.unlock()
                let xIn = mic.copy(from: end - wIn, count: wIn)
                let x16 = Rvc.fit(Dsp.resample(xIn, micRate, 16000), w * 160)
                let x48 = micRate == 48000 ? xIn : Rvc.fit(Dsp.resample(xIn, micRate, 48000), w48)  // for loudness and the gate
                let prepared = DispatchTime.now().uptimeNanoseconds
                let y: [Float]
                do {
                    // its own pool: this loop never returns to GCD, which would otherwise drain it only at the end
                    if params.bypass {
                        y = Array(x48[(w48 - tail * 480)...])
                    } else {
                        y = try autoreleasepool {
                            try rvc.streamWindow(x16, st, tail: tail, advance: b, phase: &phase, pitch: pitch)
                        }
                    }
                } catch {
                    failure = error.localizedDescription
                    stop()
                    return
                }
                let modelled = DispatchTime.now().uptimeNanoseconds
                Diagnostics.setStage("realtime: SOLA, loudness, output")
                // line up with the previous step and blend over the crossfade
                let off = previous.map { Dsp.solaOffset(y, $0, search: s * 480) } ?? 0
                var piece = Array(y[off..<min(off + b48 + sola, y.count)])
                piece = Rvc.fit(piece, b48 + sola)
                if let prev = previous {
                    for i in 0..<sola { piece[i] = piece[i] * fadeIn[i] + prev[i] * (1 - fadeIn[i]) }
                }
                previous = Array(piece[b48..<(b48 + sola)])
                var block = Array(piece[0..<b48])
                // the matching stretch of input, for loudness and the gate
                let inStart = w48 - tail * 480 + off
                let input = Array(x48[inStart..<(inStart + b48)])
                if st.rmsMixRate < 1 {
                    let rIn = Dsp.frameRms(input)
                    let rOut = Dsp.frameRms(block)
                    let power = Float(1 - st.rmsMixRate)
                    for k in 0..<b48 { block[k] *= pow(rIn[k] / max(rOut[k], 1e-3), power) }
                }
                let peak = input.reduce(Float(0)) { max($0, abs($1)) }
                // the recorded original: the same stretch without the SOLA shift, so consecutive steps join exactly
                // (shifted, the original skipped or repeated up to `search` frames at every block boundary)
                let heard = Array(x48[(w48 - tail * 480)..<(w48 - tail * 480 + b48)])
                // the gate decides per 10 ms: opens within 5 ms of a loud frame, stays open 0.3 s after the last one
                // (so word ends and soft consonants survive), then closes over 50 ms
                for f in 0..<b {
                    let a = f * 480
                    var energy: Float = 0
                    for k in a..<(a + 480) { energy += input[k] * input[k] }
                    if (energy / 480).squareRoot() >= threshold { holdLeft = gateHold }
                    for k in a..<(a + 480) {
                        gate = holdLeft > 0 ? min(1, gate + gateAttack) : max(0, gate - gateRelease)
                        if holdLeft > 0 { holdLeft -= 1 }
                        let raw = block[k] * gate * boost
                        if abs(raw) > 1 { clippedNow = true }  // the soft clip had to bend it: too loud
                        let v = LiveEngine.softClip(raw)
                        block[k] = v.isNaN ? 0 : v
                    }
                }
                // queue for the earphones, after an initial cushion; never let the queue grow past it for long
                if !primed { out.write([Float](repeating: 0, count: cushion)) }
                // the earphones ran dry since the last step: keep 20 ms more queued from now on (up to 0.2 s more),
                // so an uneven phone stops crackling at the cost of a little latency
                let u = underruns.totals
                if u.events > lastUnder.events && extraCushion < 9600 {
                    extraCushion += 960
                    out.write([Float](repeating: 0, count: 960))
                }
                var cut = 0
                if out.queued > cushion + extraCushion + 2 * b48 { cut = out.trim(keeping: cushion + extraCushion) }
                trimmed += cut
                let queued = out.queued
                out.write(block)
                if !primed {
                    primed = true
                    underruns.arm()
                }
                state.lock()
                if clippedNow { stats.clips += 1 }
                if recordingNow { recSamples += b48 }
                let recorded = recordingNow ? Double(recSamples) / Double(Rvc.sr) : -1
                state.unlock()
                clippedNow = false
                if recordingNow {
                    io.async { [self] in
                        recIn?.append(heard)
                        recOut?.append(block)
                    }
                }
                for f in stride(from: 0, to: b48, by: 960) {
                    let e = min(f + 960, b48)
                    inPeaks.append(input[f..<e].reduce(Float(0)) { max($0, abs($1)) })
                    outPeaks.append(block[f..<e].reduce(Float(0)) { max($0, abs($1)) })
                }
                if inPeaks.count > 300 { inPeaks.removeFirst(inPeaks.count - 300) }
                if outPeaks.count > 300 { outPeaks.removeFirst(outPeaks.count - 300) }
                let finished = DispatchTime.now().uptimeNanoseconds
                let took = Double(finished - started) / 1e9
                let t = zip(rvc.timing, before).map { ($0 - $1) * 1000 }
                let thermal = ProcessInfo.processInfo.thermalState.rawValue
                let memory = Memory.footprint().now
                // one line per step: when, how far behind, where the time went, what the earphones had
                let fields: [String] = [
                    String(format: "%.3f", Double(started - begun) / 1e9), "\(computeAll.count + 1)",
                    f1(Double(backlog) * 1000 / Double(micRate)), f1(took * 1000), f1(ms(prepared - started)),
                    f1(t[0]), f1(t[1]), f1(t[2]), f1(t[3]), f1(t[4]), f1(ms(finished - modelled)),
                    f1(Double(queued) / 48), "\(u.events - lastUnder.events)", f1(Double(u.samples - lastUnder.samples) / 48),
                    "\(drops)", f1(Double(cut) / 48), f1(Double(extraCushion) / 48), "\(off)", "\(thermal)",
                    String(format: "%.0f", Double(memory) / 1e6),
                ]
                csv += fields.joined(separator: ",") + "\n"
                lastUnder = u
                computeAll.append(took)
                if worst.count < 5 || took > worst[worst.count - 1].took {
                    let text = String(format: "at %.1f s: %.3f s (prep %.0f, HuBERT %.0f, RMVPE %.0f, enc %.0f, decoder %.0f, other %.0f, after %.0f ms), backlog %.0f ms, thermal %ld",
                                      Double(started - begun) / 1e9, took, ms(prepared - started), t[0], t[1], t[2], t[3], t[4],
                                      ms(finished - modelled), Double(backlog) * 1000 / Double(micRate), thermal)
                    worst.append((took, text))
                    worst.sort { $0.took > $1.took }
                    if worst.count > 5 { worst.removeLast() }
                }
                if took > Double(b) / 100 {  // slower than real time: which stage, and how hot
                    Diagnostics.log(String(format: "slow step: %.3f s for a %.2f s block (prep %.3f, HuBERT %.3f, RMVPE %.3f, enc %.3f, decoder %.3f, other %.3f) thermal %ld",
                                           took, Double(b) / 100, ms(prepared - started) / 1000, t[0] / 1000, t[1] / 1000,
                                           t[2] / 1000, t[3] / 1000, t[4] / 1000, thermal))
                }
                recent.append(took)
                if recent.count > max(1, 500 / b) { recent.removeFirst() }
                // a sound reaches the earphones after: this step's compute, what was queued ahead, and its distance
                // from the newest input (the decoded tail minus the SOLA shift)
                let ioLatency = autoreleasepool { AVAudioSession.sharedInstance().inputLatency + AVAudioSession.sharedInstance().outputLatency }
                let latency = took + Double(queued) / 48000 + Double(tail * 480 - off) / 48000 + ioLatency
                state.lock()
                stats.steps += 1
                stats.compute = stats.steps == 1 ? took : stats.compute * 0.9 + took * 0.1
                stats.computeMax = recent.max() ?? took
                stats.latency = stats.steps == 1 ? latency : stats.latency * 0.9 + latency * 0.1
                stats.level = peak
                stats.underruns = u.events
                stats.extraCushion = Double(extraCushion) / 48000
                stats.memory = Double(memory) / 1e9
                stats.memoryLimit = Double(memory + Memory.available) / 1e9
                stats.thermal = thermal
                stats.inPeaks = inPeaks
                stats.outPeaks = outPeaks
                stats.recording = recorded
                let line = stats
                state.unlock()
                Diagnostics.setStage("realtime: waiting for the microphone")
                if line.memoryLimit > 0 && line.memory > line.memoryLimit * 0.85 {  // stop before iOS ends the app
                    failure = String(format: "메모리가 한도에 가까워서 멈췄어요 (%.2f / %.2f GB)", line.memory, line.memoryLimit)
                    stop()
                    return
                }
                let now = DispatchTime.now().uptimeNanoseconds
                if now - lastLog > 1_000_000_000 { autoreleasepool {
                    lastLog = now
                    writeBlockLog(csv)
                    csv = ""
                    Diagnostics.log(String(format: "step %ld compute %.3f max %.3f latency %.2f under %ld drops %ld mem %.2f/%.2f GB thermal %ld formant %.1f",
                                           line.steps, line.compute, line.computeMax, line.latency, line.underruns, line.drops,
                                           line.memory, line.memoryLimit, thermal, st.formant))
                    let total = Double(Memory.footprint().now)
                    let g = zip(rvc.growth, lastGrowth).map { ($0 - $1) / 1e6 }
                    Diagnostics.log(String(format: "  memory growth MB: HuBERT %.1f, RMVPE %.1f, enc %.1f, decoder %.1f, other %.1f, outside the models %.1f (rate %ld Hz)",
                                           g[0], g[1], g[2], g[3], g[4], (total - lastTotal) / 1e6 - g.reduce(0, +), micRate))
                    lastGrowth = rvc.growth
                    let t = zip(rvc.timing, lastTiming).map { $0 - $1 }
                    Diagnostics.log(String(format: "  seconds: HuBERT %.3f, RMVPE %.3f, enc %.3f, decoder %.3f, other %.3f", t[0], t[1], t[2], t[3], t[4]))
                    lastTiming = rvc.timing
                    lastTotal = total
                } }
            }
        }
    }
}
