import AVFoundation
import Foundation

enum AudioIO {
    /// Any audio iOS can read (wav, m4a, mp3, caf, aiff...) -> 48 kHz mono float.
    static func load(_ url: URL) throws -> [Float] {
        let file = try AVAudioFile(forReading: url)
        let format = file.processingFormat  // float32, one buffer per channel
        let frames = AVAudioFrameCount(max(file.length, 0))
        guard frames > 0, let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames) else {
            throw RvcError("소리가 없는 파일이에요")
        }
        try file.read(into: buffer)
        let n = Int(buffer.frameLength)
        let channels = Int(format.channelCount)
        guard let data = buffer.floatChannelData, channels > 0 else { throw RvcError("읽을 수 없는 형식이에요") }
        var mono = [Float](repeating: 0, count: n)
        for c in 0..<channels {
            let p = data[c]
            for i in 0..<n { mono[i] += p[i] }
        }
        if channels > 1 { for i in 0..<n { mono[i] /= Float(channels) } }
        return Dsp.resample(mono, Int(format.sampleRate.rounded()), Rvc.sr)
    }

    /// 16-bit PCM WAV bytes.
    static func wav(_ x: [Float], rate: Int = Rvc.sr) -> Data {
        var d = Data(capacity: 44 + x.count * 2)
        func text(_ s: String) { d.append(contentsOf: Array(s.utf8)) }
        func u32(_ v: Int) { withUnsafeBytes(of: UInt32(v).littleEndian) { d.append(contentsOf: $0) } }
        func u16(_ v: Int) { withUnsafeBytes(of: UInt16(v).littleEndian) { d.append(contentsOf: $0) } }
        text("RIFF"); u32(36 + x.count * 2); text("WAVE")
        text("fmt "); u32(16); u16(1); u16(1); u32(rate); u32(rate * 2); u16(2); u16(16)
        text("data"); u32(x.count * 2)
        let pcm = x.map { v -> Int16 in v.isNaN ? 0 : Int16(min(max(v, -1), 1) * 32767) }
        pcm.withUnsafeBytes { d.append(contentsOf: $0) }  // little-endian on every iPhone
        return d
    }

    static func writeWav(_ x: [Float], to url: URL) throws {
        try wav(x).write(to: url, options: .atomic)
    }

    static func peakNormalize(_ x: [Float], peakDb: Double = -1, maxGainDb: Double = 18) -> [Float] {
        let peak = x.reduce(Float(0)) { max($0, abs($1)) }
        if peak < 1e-6 { return x }
        let gain = Float(min(pow(10, peakDb / 20) / Double(peak), pow(10, maxGainDb / 20)))
        return x.map { $0 * gain }
    }

    /// One peak per `hop` samples (10 ms), from which the waveform picks its bars at any width.
    static func envelope(_ x: [Float], hop: Int = 480) -> [Float] {
        let count = (x.count + hop - 1) / hop
        var out = [Float](repeating: 0, count: count)
        x.withUnsafeBufferPointer { p in
            for j in 0..<count {
                var peak: Float = 0
                for k in j * hop..<min((j + 1) * hop, x.count) { peak = max(peak, abs(p[k])) }
                out[j] = peak
            }
        }
        return out
    }
}

/// A clip with its waveform envelope, ready to show and play.
struct Clip {
    let id = UUID()
    let samples: [Float]
    let envelope: [Float]
    let name: String

    init(_ samples: [Float], name: String) {
        self.samples = samples
        self.envelope = AudioIO.envelope(samples)
        self.name = name
    }

    var seconds: Double { Double(samples.count) / Double(Rvc.sr) }
}

/// 48 kHz mono 16-bit WAV capture with the phone's voice processing off (the measurement mode skips automatic
/// gain and noise suppression, so whispers and breath survive). Peaks for the live waveform every 20 ms.
final class Recorder {
    private var recorder: AVAudioRecorder?
    private var timer: Timer?
    private(set) var peaks: [Float] = []
    let url: URL

    init(url: URL) { self.url = url }

    var seconds: Double { recorder?.currentTime ?? 0 }

    func start() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .measurement, options: [.defaultToSpeaker])
        try session.setActive(true)
        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatLinearPCM),
            AVSampleRateKey: Double(Rvc.sr),
            AVNumberOfChannelsKey: 1,
            AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsFloatKey: false,
            AVLinearPCMIsBigEndianKey: false,
        ]
        let r = try AVAudioRecorder(url: url, settings: settings)
        r.isMeteringEnabled = true
        guard r.record() else { throw RvcError("녹음을 시작하지 못했어요") }
        recorder = r
        peaks = []
        let t = Timer(timeInterval: 0.02, repeats: true) { [weak self] _ in self?.meter() }
        RunLoop.main.add(t, forMode: .common)
        timer = t
    }

    private func meter() {
        guard let r = recorder else { return }
        r.updateMeters()
        peaks.append(pow(10, r.peakPower(forChannel: 0) / 20))
    }

    func stop() {
        timer?.invalidate()
        timer = nil
        recorder?.stop()
        recorder = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}

/// Plays one clip at a time from any sample; the app reads [position] to move the playheads.
final class ClipPlayer: NSObject, AVAudioPlayerDelegate {
    private var player: AVAudioPlayer?
    var onFinish: (() -> Void)?

    var isPlaying: Bool { player?.isPlaying ?? false }
    var position: Int { Int((player?.currentTime ?? 0) * Double(Rvc.sr)) }

    /// True while the realtime mode runs: its session (play and record) already plays, and changing the category
    /// under a running engine fails with "insufficient priority" (561017449).
    var keepSession = false

    func play(_ x: [Float], from: Int) throws {
        stop()
        if !keepSession {
            // full speaker volume, even with the ring switch off; if the session refuses, play in whatever it allows
            let session = AVAudioSession.sharedInstance()
            try? session.setCategory(.playback, mode: .default)
            try? session.setActive(true)
        }
        let p = try AVAudioPlayer(data: AudioIO.wav(x))
        p.delegate = self
        p.currentTime = Double(from) / Double(Rvc.sr)
        p.prepareToPlay()
        p.play()
        player = p
    }

    func stop() {
        player?.stop()
        player = nil
    }

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        self.player = nil
        onFinish?()
    }
}

/// Streams 16-bit mono WAV to disk, so a long realtime recording never sits in memory. The sizes in the header are
/// filled in by finish().
final class WavWriter {
    let url: URL
    private let handle: FileHandle
    private(set) var samples = 0

    init(url: URL, rate: Int = Rvc.sr) throws {
        self.url = url
        _ = FileManager.default.createFile(atPath: url.path, contents: AudioIO.wav([], rate: rate))
        handle = try FileHandle(forWritingTo: url)
        _ = try handle.seekToEnd()
    }

    func append(_ x: [Float]) {
        let pcm = x.map { v -> Int16 in v.isNaN ? 0 : Int16(min(max(v, -1), 1) * 32767) }
        pcm.withUnsafeBytes { handle.write(Data($0)) }
        samples += x.count
    }

    func finish() {
        func u32(_ v: Int) -> Data { withUnsafeBytes(of: UInt32(v).littleEndian) { Data($0) } }
        try? handle.seek(toOffset: 4)
        handle.write(u32(36 + samples * 2))
        try? handle.seek(toOffset: 40)
        handle.write(u32(samples * 2))
        try? handle.close()
    }
}
