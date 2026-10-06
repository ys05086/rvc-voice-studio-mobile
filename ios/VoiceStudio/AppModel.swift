import AVFoundation
import SwiftUI
import UIKit
import UniformTypeIdentifiers

enum Page: String { case record, convert, live }
enum Slot: Hashable { case take, input, output }
enum PickKind { case modelFolder, modelFiles, audio }

struct InputChoice: Identifiable, Hashable {
    let id: String
    let name: String
}

struct Recording: Identifiable {
    let url: URL
    let name: String
    let seconds: Double
    var id: URL { url }
}

/// One advanced-setting slider, as the studio's web page lists them.
struct Spec: Identifiable {
    let key: String
    let label: String
    let min: Double
    let max: Double
    let step: Double
    let unit: String
    let def: Double
    let minLabel: String
    let maxLabel: String
    var id: String { key }

    func format(_ x: Double) -> String {
        let s = step >= 1 ? String(Int(x.rounded())) : String(format: step >= 0.1 ? "%.1f" : "%.2f", x)
        return unit.isEmpty ? s : "\(s) \(unit)"
    }
}

/// The app's state: two pages, 녹음 (record, play and seek, a list of takes) and 변환 (model, pitch and texture,
/// advanced settings, original and converted waveforms with linked playheads, save and share).
///
/// Files live in the app's Documents folder, which the Files app shows under 나의 iPhone › Voice Studio:
///   common/hubert.onnx, common/rmvpe.onnx                         shared by every voice
///   models/<name>/model.enc.onnx, model.dec.onnx, settings.json   one folder per voice
///   recordings/<name>.wav                                         the 녹음 page's takes
///   outputs/<name>.wav                                            saved results
/// Model files dropped loose into the Documents folder are sorted into these places on the next refresh.
/// settings.json uses the studio's realtime_settings.json keys and is updated as the sliders move.
final class AppModel: ObservableObject {
    static let specs = [
        Spec(key: "low_pitch_ratio", label: "저음 보호 · 강도", min: 1, max: 3, step: 0.1, unit: "", def: 1, minLabel: "1 = 꺼짐", maxLabel: "3"),
        Spec(key: "low_pitch_knee", label: "저음 보호 · 기준", min: 60, max: 200, step: 5, unit: "Hz", def: 120, minLabel: "60", maxLabel: "200"),
        Spec(key: "high_pitch_ratio", label: "고음 보호 · 강도", min: 1, max: 3, step: 0.1, unit: "", def: 1, minLabel: "1 = 꺼짐", maxLabel: "3"),
        Spec(key: "high_pitch_knee", label: "고음 보호 · 기준", min: 200, max: 600, step: 10, unit: "Hz", def: 260, minLabel: "200", maxLabel: "600"),
        Spec(key: "intonation_ratio", label: "억양 넓히기 · 강도", min: 1, max: 1.5, step: 0.05, unit: "배", def: 1.15, minLabel: "1배", maxLabel: "1.5배"),
        Spec(key: "rms_mix_rate", label: "볼륨 엔벨로프", min: 0, max: 1, step: 0.05, unit: "", def: 0, minLabel: "입력 기준", maxLabel: "변환 기준"),
    ]

    let docs: URL
    let commonDir: URL
    let modelsDir: URL
    let recordingsDir: URL
    let outputsDir: URL

    @Published var page: Page
    @Published var message: String?

    // record page
    @Published var recording = false
    @Published var livePeaks: [Float] = []
    @Published var recSeconds = 0.0
    @Published var take: Clip?
    @Published var recTitle = "새 녹음"
    @Published var recInfo = "빨간 버튼을 누르면 녹음을 시작해요"
    @Published var recordings: [Recording] = []

    // convert page
    @Published var models: [String] = []
    @Published var model = "" {
        didSet {
            guard model != oldValue else { return }
            UserDefaults.standard.set(model, forKey: "model")
            loadSettings()
        }
    }
    @Published var commonReady = false
    @Published var downloadProgress: Double?  // the shared models coming in; nil when not downloading
    private var downloader: CommonDownloader?
    @Published var values: [String: Double] = [:]
    @Published var input: Clip?
    @Published var inInfo = "녹음 탭에서 보내거나 파일을 고르세요"
    @Published var output: Clip?
    @Published var outModel = ""
    @Published var outInfo = ""
    @Published var shareURL: URL?
    @Published var busy = false
    @Published var busyText = ""
    @Published var progress = 0.0
    @Published var positions: [Slot: Int] = [.take: 0, .input: 0, .output: 0]
    @Published var playing: Slot?
    @Published var normalize: Bool { didSet { UserDefaults.standard.set(normalize, forKey: "normalize") } }
    @Published var threads: Int { didSet { UserDefaults.standard.set(threads, forKey: "threads") } }
    @Published var decoder: Rvc.DecoderMode { didSet { UserDefaults.standard.set(decoder.rawValue, forKey: "decoder") } }
    @Published var npu: Bool { didSet { UserDefaults.standard.set(npu, forKey: "npu") } }
    @Published var showSettings = false
    @Published var advancedOpen: Bool { didSet { UserDefaults.standard.set(advancedOpen, forKey: "advanced_open") } }

    // live page (frames are 10 ms; these apply from the next start)
    @Published var live = false { didSet { player.keepSession = live } }  // playback must not reset the live session
    @Published var liveStarting = false
    @Published var liveStats = LiveEngine.Stats()
    @Published var liveRecording = false
    @Published var liveRoute = ""  // which microphone and output iOS picked
    @Published var liveBlock: Int { didSet { UserDefaults.standard.set(liveBlock, forKey: "live_block") } }
    @Published var liveContext: Int { didSet { UserDefaults.standard.set(liveContext, forKey: "live_context") } }
    @Published var liveCrossfade: Int { didSet { UserDefaults.standard.set(liveCrossfade, forKey: "live_crossfade") } }
    @Published var liveCushion: Double { didSet { UserDefaults.standard.set(liveCushion, forKey: "live_cushion") } }
    @Published var liveThreshold: Double { didSet { UserDefaults.standard.set(liveThreshold, forKey: "live_threshold") } }
    @Published var liveGain: Double {  // applies at once
        didSet {
            UserDefaults.standard.set(liveGain, forKey: "live_gain")
            liveEngine?.setGain(liveGain)
        }
    }
    @Published var liveInputID: String { didSet { UserDefaults.standard.set(liveInputID, forKey: "live_input") } }
    @Published var liveSpeaker: Bool { didSet { UserDefaults.standard.set(liveSpeaker, forKey: "live_speaker") } }
    @Published var liveBluetoothMic: Bool { didSet { UserDefaults.standard.set(liveBluetoothMic, forKey: "live_bt_mic") } }
    @Published var inputChoices = [InputChoice(id: "", name: "자동 (iOS가 고름)")]
    @Published var liveBypass = false  // diagnosis only, not saved
    private var liveEngine: LiveEngine?

    // file picking and model import
    @Published var showPicker = false       // audio files, from the 변환 page
    @Published var showModelPicker = false  // model files, from the settings sheet
    // folded sections, remembered
    @Published var convertMoreOpen: Bool { didSet { UserDefaults.standard.set(convertMoreOpen, forKey: "fold_convert") } }
    @Published var liveMoreOpen: Bool { didSet { UserDefaults.standard.set(liveMoreOpen, forKey: "fold_live") } }
    @Published var recListOpen: Bool { didSet { UserDefaults.standard.set(recListOpen, forKey: "fold_recordings") } }
    @Published var askingName = false
    @Published var nameField = ""
    @Published private(set) var pickKind = PickKind.audio  // published: the importer must see the new kind
    private var pendingSources: [URL] = []
    private var scoped: [URL] = []

    private var inputFromTake = false  // the input is the 녹음 take, so a newer take replaces it
    private var recorder: Recorder?
    private var recordingName = ""
    private let player = ClipPlayer()
    private var dragging = false
    private var ticker: Timer?
    private let work = DispatchQueue(label: "convert", qos: .userInteractive)  // also runs the realtime loop
    private var engine: Rvc?  // used on `work` only
    private var engineKey = ""

    init() {
        let d = UserDefaults.standard
        docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        commonDir = docs.appendingPathComponent("common", isDirectory: true)
        modelsDir = docs.appendingPathComponent("models", isDirectory: true)
        recordingsDir = docs.appendingPathComponent("recordings", isDirectory: true)
        outputsDir = docs.appendingPathComponent("outputs", isDirectory: true)
        page = Page(rawValue: d.string(forKey: "page") ?? "") ?? .record
        normalize = d.object(forKey: "normalize") as? Bool ?? true
        threads = d.object(forKey: "threads") as? Int ?? 2
        // the older GPU switch carries over: on = GPU (Core ML, ~5x faster, sounds the same), off = CPU
        decoder = Rvc.DecoderMode(rawValue: d.string(forKey: "decoder") ?? "")
            ?? ((d.object(forKey: "gpu") as? Bool ?? true) ? .gpu : .cpu)
        npu = d.object(forKey: "npu") as? Bool ?? true  // features on the Neural Engine: fast, and no leak (see Rvc)
        advancedOpen = d.bool(forKey: "advanced_open")
        convertMoreOpen = d.bool(forKey: "fold_convert")
        liveMoreOpen = d.bool(forKey: "fold_live")
        recListOpen = d.bool(forKey: "fold_recordings")
        let p = LiveParams()
        liveBlock = d.object(forKey: "live_block") as? Int ?? p.block
        liveContext = d.object(forKey: "live_context") as? Int ?? p.context
        liveCrossfade = d.object(forKey: "live_crossfade") as? Int ?? p.crossfade
        liveCushion = d.object(forKey: "live_cushion") as? Double ?? p.cushion
        liveThreshold = d.object(forKey: "live_threshold") as? Double ?? p.thresholdDb
        liveGain = d.object(forKey: "live_gain") as? Double ?? p.gainDb
        liveInputID = d.string(forKey: "live_input") ?? ""
        liveSpeaker = d.bool(forKey: "live_speaker")
        liveBluetoothMic = d.bool(forKey: "live_bt_mic")
        player.onFinish = { [weak self] in self?.playbackEnded() }
        Diagnostics.open()
        NotificationCenter.default.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] _ in
            guard let self, self.page == .live else { return }
            self.refreshInputs(configure: false)  // earphones plugged in or out
            if self.live { self.updateRoute() }
        }
        if d.bool(forKey: "live_running") {  // the last realtime session never reached liveEnded
            d.set(false, forKey: "live_running")
            message = "지난번 실시간 변환이 갑자기 끝났어요. 기록(live_log.txt) 마지막 부분:\n" + Diagnostics.tail(3)
        }
        refreshModels()
        refreshRecordings()
        let t = Timer(timeInterval: 1.0 / 30, repeats: true) { [weak self] _ in self?.tick() }
        RunLoop.main.add(t, forMode: .common)
        ticker = t
    }

    var ready: Bool { commonReady && !models.isEmpty }
    var canConvert: Bool { ready && input != nil && !busy && !recording }

    var helpText: String {
        if ready { return "모델 폴더: 파일 앱 › 나의 iPhone › Voice Studio" }
        var s = "\"불러오기\"로 모델 폴더나 파일을 고르거나, 파일 앱 › 나의 iPhone › Voice Studio에 넣으세요."
        if !commonReady { s += "\n· 공통 모델 hubert.onnx, rmvpe.onnx가 필요해요 (처음 한 번, 톱니바퀴 › 받기)" }
        if models.isEmpty { s += "\n· 목소리 모델: <이름>.enc.onnx, <이름>.dec.onnx, settings.json" }
        return s
    }

    func show(_ p: Page) {
        // the newest take follows to 변환 by itself, unless a file was picked there since
        if p == .convert, let t = take, recorder == nil, input?.id != t.id, input == nil || inputFromTake {
            setInput(t, fromTake: true)
        }
        page = p
        UserDefaults.standard.set(p.rawValue, forKey: "page")
    }

    func toast(_ text: String) { message = text }

    // MARK: - moving parts

    /// One loop for the live waveform, the recording timer and the playheads.
    private func tick() {
        if let e = liveEngine {
            // the numbers only change once per step: redrawing the page 30 times a second competed with the converter
            let s = e.snapshot()
            if s.steps != liveStats.steps || s.restarts != liveStats.restarts { liveStats = s }
        } else if let r = recorder {
            livePeaks = r.peaks
            recSeconds = r.seconds
        } else if let slot = playing, !dragging {
            let p = player.position
            positions[slot] = p
            link(slot, p)
        }
    }

    /// The original and the result share one timeline, so the same moment can be heard before and after.
    private func link(_ slot: Slot, _ at: Int) {
        if slot == .input && output != nil { positions[.output] = at }
        if slot == .output && input != nil { positions[.input] = at }
    }

    func clip(_ slot: Slot) -> Clip? {
        switch slot {
        case .take: return take
        case .input: return input
        case .output: return output
        }
    }

    func togglePlay(_ slot: Slot) {
        if playing == slot { stopPlayback(); return }
        guard let c = clip(slot) else { return }
        let at = positions[slot] ?? 0
        let from = at >= c.samples.count - 1 ? 0 : at
        do {
            try player.play(c.samples, from: from)
            playing = slot
            positions[slot] = from
            link(slot, from)
        } catch {
            toast("재생하지 못했어요: \(error.localizedDescription)")
        }
    }

    func stopPlayback() {
        player.stop()
        playing = nil
    }

    private func playbackEnded() {
        if let slot = playing, let c = clip(slot) { positions[slot] = max(c.samples.count - 1, 0) }
        playing = nil
    }

    func seek(_ slot: Slot, to at: Int, done: Bool) {
        dragging = !done
        positions[slot] = at
        link(slot, at)
        if done, playing == slot, let c = clip(slot) { try? player.play(c.samples, from: at) }
    }

    func timeText(_ slot: Slot) -> String {
        guard let c = clip(slot) else { return "0:00.0" }
        return "\(Self.clock(Double(positions[slot] ?? 0) / Double(Rvc.sr))) / \(Self.clock(c.seconds))"
    }

    static func clock(_ seconds: Double) -> String {
        let s = max(seconds, 0)
        return "\(Int(s / 60)):" + String(format: "%04.1f", s.truncatingRemainder(dividingBy: 60))
    }

    static func stamp(_ format: String) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = format
        return f.string(from: Date())
    }

    static func sanitize(_ name: String) -> String {
        let s = name.replacingOccurrences(of: "[\\\\/:*?\"<>|]+", with: "_", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return s.isEmpty ? "모델" : s
    }

    // MARK: - record page

    var recTimeText: String {
        if recording { return Self.clock(recSeconds) }
        return take == nil ? "0:00.0" : timeText(.take)
    }

    func toggleRecording() {
        if recorder != nil { finishRecording(); return }
        let session = AVAudioSession.sharedInstance()
        switch session.recordPermission {
        case .granted:
            startRecording()
        case .undetermined:
            session.requestRecordPermission { ok in
                DispatchQueue.main.async {
                    if ok { self.startRecording() } else { self.toast("마이크 권한이 있어야 녹음할 수 있어요") }
                }
            }
        default:
            toast("설정 › Voice Studio에서 마이크를 허용해 주세요")
        }
    }

    private func startRecording() {
        stopPlayback()
        try? FileManager.default.createDirectory(at: recordingsDir, withIntermediateDirectories: true)
        let name = "녹음_" + Self.stamp("MMdd_HHmmss")
        let r = Recorder(url: recordingsDir.appendingPathComponent(name + ".wav"))
        do {
            try r.start()
        } catch {
            toast("녹음을 시작하지 못했어요: \(error.localizedDescription)")
            return
        }
        recorder = r
        recordingName = name
        recording = true
        livePeaks = []
        recSeconds = 0
        recTitle = "녹음 중…"
        recInfo = "다시 누르면 멈춰요"
        UIApplication.shared.isIdleTimerDisabled = true
    }

    private func finishRecording() {
        guard let r = recorder else { return }
        r.stop()
        recorder = nil
        recording = false
        UIApplication.shared.isIdleTimerDisabled = false
        let x = (try? AudioIO.load(r.url)) ?? []
        if x.count < Rvc.sr / 5 {
            try? FileManager.default.removeItem(at: r.url)
            recTitle = take?.name ?? "새 녹음"
            recInfo = "녹음이 너무 짧아요"
            return
        }
        loadTake(Clip(x, name: recordingName))
        refreshRecordings()
    }

    private func loadTake(_ c: Clip) {
        stopPlayback()
        take = c
        positions[.take] = 0
        recTitle = c.name
        recInfo = "파형을 누르거나 끌어서 원하는 곳부터 들을 수 있어요"
    }

    func rewindTake() {
        positions[.take] = 0
        if playing == .take, let t = take { try? player.play(t.samples, from: 0) }
    }

    func refreshRecordings() {
        let files = (try? FileManager.default.contentsOfDirectory(at: recordingsDir, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
        recordings = files.filter { $0.pathExtension.lowercased() == "wav" }
            .map { url -> (URL, Date) in (url, (try? url.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? .distantPast) }
            .sorted { $0.1 > $1.1 }
            .map { pair in
                let seconds = (try? AVAudioFile(forReading: pair.0)).map { Double($0.length) / $0.fileFormat.sampleRate } ?? 0
                return Recording(url: pair.0, name: pair.0.deletingPathExtension().lastPathComponent, seconds: seconds)
            }
    }

    func open(_ r: Recording) {
        do {
            loadTake(Clip(try AudioIO.load(r.url), name: r.name))
        } catch {
            toast("열지 못했어요: \(error.localizedDescription)")
        }
    }

    func delete(_ r: Recording) {
        try? FileManager.default.removeItem(at: r.url)
        refreshRecordings()
    }

    // MARK: - models and settings

    /// On the work queue, before a conversion or realtime: when the app already holds a lot (Core ML plans for many
    /// window sizes, ORT's arenas), the models load fresh instead of the job running out of memory halfway.
    private func releaseIfTight(_ job: String) {
        guard engine != nil, Memory.available < 1_600_000_000 else { return }
        let before = Memory.footprint().now
        engine = nil
        engineKey = ""
        Diagnostics.log(String(format: "before %@: released the models at %.2f GB (%.2f GB left), now %.2f GB", job,
                               Double(before) / 1e9, Double(Memory.available) / 1e9, Double(Memory.footprint().now) / 1e9))
    }

    /// The shared models from the release page (about 740 MB, once). Voice models stay the user's own.
    func downloadCommon() {
        guard downloader == nil else { return }
        let d = CommonDownloader(into: commonDir, progress: { [weak self] p in self?.downloadProgress = p },
                                 finished: { [weak self] error in self?.commonDownloaded(error) })
        downloader = d
        downloadProgress = 0
        UIApplication.shared.isIdleTimerDisabled = true
        d.start()
    }

    func cancelDownload() { downloader?.cancel() }

    private func commonDownloaded(_ error: Error?) {
        downloader = nil
        downloadProgress = nil
        if !live { UIApplication.shared.isIdleTimerDisabled = false }
        refreshModels()
        guard let error else {
            toast("공통 모델을 받았어요")
            return
        }
        if (error as? URLError)?.code == .cancelled {
            toast("공통 모델 받기를 멈췄어요. 다시 누르면 남은 파일부터 받아요")
            return
        }
        toast("공통 모델을 받지 못했어요: \(error.localizedDescription)\n파일 앱 › 나의 iPhone › Voice Studio › common 폴더에 "
              + "hubert.onnx와 rmvpe.onnx를 직접 넣어 주세요. PC 변환 도구(rvc2phone --common)로 만들 수 있어요.")
    }

    func refreshModels() {
        let fm = FileManager.default
        for d in [commonDir, modelsDir, recordingsDir, outputsDir] { try? fm.createDirectory(at: d, withIntermediateDirectories: true) }
        adoptLooseFiles()
        commonReady = fm.fileExists(atPath: commonDir.appendingPathComponent("hubert.onnx").path)
            && fm.fileExists(atPath: commonDir.appendingPathComponent("rmvpe.onnx").path)
        let dirs = (try? fm.contentsOfDirectory(at: modelsDir, includingPropertiesForKeys: nil, options: [.skipsHiddenFiles])) ?? []
        models = dirs.filter {
            fm.fileExists(atPath: $0.appendingPathComponent("model.enc.onnx").path) && fm.fileExists(atPath: $0.appendingPathComponent("model.dec.onnx").path)
        }.map { $0.lastPathComponent }.sorted { $0.lowercased() < $1.lowercased() }
        let keep = UserDefaults.standard.string(forKey: "model") ?? ""
        let pick = models.contains(keep) ? keep : (models.first ?? "")
        if model != pick { model = pick } else { loadSettings() }
    }

    /// Where an imported file goes, by its name: <name>.enc.onnx or model.enc.onnx and the like.
    func importTarget(_ fileName: String, model: URL?) -> URL? {
        let n = fileName.lowercased()
        if n == "hubert.onnx" || n == "rmvpe.onnx" { return commonDir.appendingPathComponent(n) }
        guard let m = model else { return nil }
        if n.hasSuffix(".enc.onnx") { return m.appendingPathComponent("model.enc.onnx") }
        if n.hasSuffix(".dec.onnx") { return m.appendingPathComponent("model.dec.onnx") }
        if n.hasSuffix(".dec_a.onnx") { return m.appendingPathComponent("model.dec_a.onnx") }
        if n.hasSuffix(".dec_b.onnx") { return m.appendingPathComponent("model.dec_b.onnx") }
        if n == "settings.json" || n.hasSuffix(".settings.json") || n == "realtime_settings.json" { return m.appendingPathComponent("settings.json") }
        return nil
    }

    private func isModelFile(_ name: String) -> Bool {
        importTarget(name, model: modelsDir) != nil && importTarget(name, model: nil) == nil
    }

    /// The voice's name from its files (<name>.enc.onnx), else the folder's.
    private static func modelName(_ files: [URL], fallback: String) -> String {
        for f in files {
            let n = f.lastPathComponent
            if n.lowercased().hasSuffix(".enc.onnx") {
                let base = String(n.dropLast(".enc.onnx".count))
                if base.lowercased() != "model" && !base.isEmpty { return sanitize(base) }
            }
        }
        return sanitize(fallback)
    }

    /// Files and folders put straight into Documents with the Files app go where the app looks for them.
    private func adoptLooseFiles() {
        let fm = FileManager.default
        let reserved: Set<String> = ["common", "models", "recordings", "outputs"]
        let items = (try? fm.contentsOfDirectory(at: docs, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles])) ?? []
        for item in items where !reserved.contains(item.lastPathComponent) {
            let isDir = (try? item.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory ?? false
            if isDir {
                let kids = (try? fm.contentsOfDirectory(at: item, includingPropertiesForKeys: nil, options: [.skipsHiddenFiles])) ?? []
                let hasModel = kids.contains { $0.lastPathComponent.lowercased().hasSuffix(".enc.onnx") }
                let hasCommon = kids.contains { importTarget($0.lastPathComponent, model: nil) != nil }
                guard hasModel || hasCommon else { continue }
                move(kids, into: hasModel ? modelsDir.appendingPathComponent(Self.modelName(kids, fallback: item.lastPathComponent)) : nil)
                if ((try? fm.contentsOfDirectory(atPath: item.path)) ?? []).isEmpty { try? fm.removeItem(at: item) }
            } else {
                let name = item.lastPathComponent
                let n = name.lowercased()
                if importTarget(name, model: nil) != nil {
                    move([item], into: nil)
                } else if n.hasSuffix(".enc.onnx") || n.hasSuffix(".dec.onnx") {
                    move([item], into: modelsDir.appendingPathComponent(Self.sanitize(String(name.dropLast(9)))))
                } else if n.hasSuffix(".settings.json") {
                    move([item], into: modelsDir.appendingPathComponent(Self.sanitize(String(name.dropLast(14)))))
                }
            }
        }
    }

    private func move(_ files: [URL], into model: URL?) {
        let fm = FileManager.default
        if let m = model { try? fm.createDirectory(at: m, withIntermediateDirectories: true) }
        for f in files {
            guard let dst = importTarget(f.lastPathComponent, model: model) else { continue }
            try? fm.removeItem(at: dst)
            try? fm.moveItem(at: f, to: dst)
        }
    }

    private func settingsURL(_ name: String) -> URL {
        modelsDir.appendingPathComponent(name).appendingPathComponent("settings.json")
    }

    private func readSettings(_ name: String) -> [String: Any] {
        guard let data = try? Data(contentsOf: settingsURL(name)),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [:] }
        return obj
    }

    func loadSettings() {
        var v: [String: Double] = [:]
        if !model.isEmpty {
            for (k, x) in readSettings(model) { if let n = x as? NSNumber { v[k] = n.doubleValue } }
        }
        values = v
    }

    func value(_ key: String, _ def: Double) -> Double { values[key] ?? def }

    /// Slider values; a running realtime session hears the change from its next step.
    func binding(_ key: String, _ def: Double) -> Binding<Double> {
        Binding(get: { self.values[key] ?? def }, set: {
            self.values[key] = $0
            self.liveEngine?.update(self.rvcSettings())
        })
    }

    func flag(_ key: String, _ def: Double) -> Binding<Bool> {
        Binding(get: { (self.values[key] ?? def) != 0 }, set: { on in
            self.values[key] = on ? 1 : 0
            self.commit(key, def)
            self.liveEngine?.update(self.rvcSettings())
        })
    }

    /// Writes one value into the selected model's settings.json, keeping the keys the app does not know.
    func commit(_ key: String, _ def: Double) {
        guard !model.isEmpty else { return }
        var obj = readSettings(model)
        obj[key] = values[key] ?? def
        if let data = try? JSONSerialization.data(withJSONObject: obj, options: [.prettyPrinted, .sortedKeys]) {
            try? data.write(to: settingsURL(model), options: .atomic)
        }
    }

    private func rvcSettings() -> Rvc.Settings {
        var s = Rvc.Settings()
        s.pitch = value("pitch", s.pitch)
        s.formant = value("formant", s.formant)
        s.lowRatio = value("low_pitch_ratio", s.lowRatio)
        s.lowKnee = value("low_pitch_knee", s.lowKnee)
        s.highRatio = value("high_pitch_ratio", s.highRatio)
        s.highKnee = value("high_pitch_knee", s.highKnee)
        s.unvoicedKeep = value("unvoiced_keep", 1) != 0
        s.expand = value("intonation_expand", 0) != 0
        s.expandRatio = value("intonation_ratio", s.expandRatio)
        s.rmsMixRate = value("rms_mix_rate", s.rmsMixRate)
        return s
    }

    // MARK: - picking files

    var pickTypes: [UTType] {
        switch pickKind {
        case .modelFolder: return [.folder]
        case .modelFiles: return [.item]
        case .audio: return [.audio]
        }
    }

    func pick(_ kind: PickKind) {
        pickKind = kind
        // a beat later: from a Menu, presenting while the menu is still closing is silently dropped
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
            if kind == .audio { self.showPicker = true } else { self.showModelPicker = true }
        }
    }

    func picked(_ result: Result<[URL], Error>) {
        switch result {
        case .failure(let error):
            Diagnostics.log("picker failed: \(error.localizedDescription)")
            toast("파일을 열지 못했어요: \(error.localizedDescription)")
        case .success(let urls):
            Diagnostics.log("picked \(urls.count) for \(pickKind): " + urls.map { $0.lastPathComponent }.joined(separator: ", "))
            guard !urls.isEmpty else { return }
            if pickKind == .audio { openAudio(urls[0]) } else { prepareImport(urls) }
        }
    }

    private func openAudio(_ url: URL) {
        inInfo = "파일 읽는 중…"
        DispatchQueue.global(qos: .userInitiated).async {
            let access = url.startAccessingSecurityScopedResource()
            defer { if access { url.stopAccessingSecurityScopedResource() } }
            do {
                var x: [Float] = []
                try Self.coordinated(url) { x = try AudioIO.load($0) }
                let c = Clip(x, name: url.deletingPathExtension().lastPathComponent)
                DispatchQueue.main.async { self.setInput(c, fromTake: false) }
            } catch {
                DispatchQueue.main.async { self.inInfo = "파일을 읽지 못했어요: \(error.localizedDescription)" }
            }
        }
    }

    /// Reads through a file coordinator, so files still in iCloud are downloaded first.
    private static func coordinated(_ url: URL, _ body: (URL) throws -> Void) throws {
        var coordinatorError: NSError?
        var bodyError: Error?
        withoutActuallyEscaping(body) { body in
            NSFileCoordinator().coordinate(readingItemAt: url, options: [], error: &coordinatorError) { readable in
                do { try body(readable) } catch { bodyError = error }
            }
        }
        if let e = coordinatorError ?? bodyError { throw e }
    }

    private func prepareImport(_ urls: [URL]) {
        var sources: [URL] = []
        var folder = "모델"
        for u in urls {
            if u.startAccessingSecurityScopedResource() { scoped.append(u) }
            if (try? u.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true {
                folder = u.lastPathComponent
                sources += (try? FileManager.default.contentsOfDirectory(at: u, includingPropertiesForKeys: nil, options: [.skipsHiddenFiles])) ?? []
            } else {
                sources.append(u)
            }
        }
        let hasModel = sources.contains { isModelFile($0.lastPathComponent) }
        let hasCommon = sources.contains { importTarget($0.lastPathComponent, model: nil) != nil }
        if !hasModel && !hasCommon {
            releaseScoped()
            toast("모델 파일(.enc.onnx, .dec.onnx)이나 공통 모델(hubert.onnx, rmvpe.onnx)이 없어요")
            return
        }
        pendingSources = sources
        if hasModel {
            nameField = Self.modelName(sources, fallback: folder)
            askingName = true
        } else {
            copyIn(name: nil)
        }
    }

    func cancelImport() {
        pendingSources = []
        releaseScoped()
    }

    private func releaseScoped() {
        scoped.forEach { $0.stopAccessingSecurityScopedResource() }
        scoped = []
    }

    func copyIn(name: String?) {
        let sources = pendingSources
        pendingSources = []
        let modelDir = name.map { modelsDir.appendingPathComponent(Self.sanitize($0)) }
        busy = true
        busyText = "불러오는 중…"
        work.async { [self] in
            let fm = FileManager.default
            do {
                if let m = modelDir { try fm.createDirectory(at: m, withIntermediateDirectories: true) }
                let todo = sources.compactMap { s in importTarget(s.lastPathComponent, model: modelDir).map { (s, $0) } }
                for (i, (source, target)) in todo.enumerated() {
                    DispatchQueue.main.async { self.busyText = "불러오는 중 (\(i + 1)/\(todo.count)) \(source.lastPathComponent)…" }
                    let part = target.appendingPathExtension("part")
                    try? fm.removeItem(at: part)
                    try Self.coordinated(source) { try fm.copyItem(at: $0, to: part) }
                    try? fm.removeItem(at: target)
                    try fm.moveItem(at: part, to: target)
                }
                if let m = modelDir, !fm.fileExists(atPath: m.appendingPathComponent("model.enc.onnx").path) {
                    throw RvcError("model.enc.onnx가 없어요")
                }
                if let m = modelDir, engineKey.hasPrefix(m.path) { engine = nil; engineKey = "" }
                DispatchQueue.main.async {
                    if let m = modelDir { UserDefaults.standard.set(m.lastPathComponent, forKey: "model") }
                    self.toast(modelDir.map { "\($0.lastPathComponent) 모델을 불러왔어요" } ?? "공통 모델을 불러왔어요")
                }
            } catch {
                DispatchQueue.main.async { self.toast("불러오지 못했어요: \(error.localizedDescription)") }
            }
            DispatchQueue.main.async {
                self.releaseScoped()
                self.busy = false
                self.refreshModels()
            }
        }
    }

    // MARK: - conversion

    func setInput(_ c: Clip, fromTake: Bool) {
        stopPlayback()
        input = c
        inputFromTake = fromTake
        inInfo = ""
        output = nil
        shareURL = nil
        positions[.input] = 0
        positions[.output] = 0
    }

    func convert() {
        guard canConvert, let source = input else { return }
        let dir = modelsDir.appendingPathComponent(model)
        let st = rvcSettings()
        let threads = self.threads
        let decoder = self.decoder
        let npu = self.npu
        let normalize = self.normalize
        let key = "\(dir.path)|\(threads)|\(decoder.rawValue)|\(npu)"
        let modelName = model
        stopPlayback()
        busy = true
        busyText = "변환 중…"
        progress = 0
        output = nil
        shareURL = nil
        UIApplication.shared.isIdleTimerDisabled = true
        let background = UIApplication.shared.beginBackgroundTask(withName: "convert")
        work.async { [self] in
            do {
                let started = Date()
                releaseIfTight("convert")
                if engineKey != key {
                    DispatchQueue.main.async { self.busyText = "모델 불러오는 중…" }
                    engine = nil
                    engine = try Rvc(commonDir: commonDir, modelDir: dir, threads: threads, decoder: decoder, npu: npu)
                    engineKey = key
                }
                guard let e = engine else { throw RvcError("모델을 불러오지 못했어요") }
                let loaded = Date()
                let before = e.timing
                DispatchQueue.main.async { self.busyText = "변환 중…" }
                var y = try e.convert(source.samples, st) { done, total in
                    DispatchQueue.main.async { self.progress = Double(done) / Double(total) }
                }
                if normalize { y = AudioIO.peakNormalize(y) }
                let took = Date().timeIntervalSince(loaded)
                let load = loaded.timeIntervalSince(started)
                let name = Self.sanitize("\(source.name)_\(modelName)_" + Self.stamp("MMdd_HHmm"))
                let share = FileManager.default.temporaryDirectory.appendingPathComponent(name + ".wav")
                try? AudioIO.writeWav(y, to: share)
                let result = Clip(y, name: name)
                var info = String(format: "%.1f초 분량을 %.1f초에 변환 · 디코더 ", result.seconds, took) + e.decoderUsed.label
                if load > 0.5 { info += String(format: " · 모델 불러오기 %.0f초", load) }
                if decoder != e.decoderUsed {
                    info += decoder == .split && e.decoderUsed == .gpu ? " (이 모델엔 dec_a·dec_b가 없어서 GPU로 했어요)"
                        : " (\(decoder.label)를 쓸 수 없어서 바꿨어요)"
                }
                if npu { info += e.usesNPU ? " · 앞 단계 NPU" : " · NPU를 쓸 수 없어 앞 단계는 CPU로 했어요" }
                else { info += e.frontCoreML ? " · 앞 단계 CPU (Core ML)" : " · 앞 단계 CPU" }
                let memory = Memory.footprint()
                info += String(format: "\n메모리 지금 %.2f GB · 최고 %.2f GB · 한도 약 %.1f GB", Double(memory.now) / 1e9,
                               Double(memory.peak) / 1e9, Double(memory.now + Memory.available) / 1e9)
                let t = zip(e.timing, before).map { $0 - $1 }
                info += String(format: "\nHuBERT %.1f · 음높이 %.1f · enc %.1f · 디코더 %.1f · 기타 %.1f초", t[0], t[1], t[2], t[3], t[4])
                DispatchQueue.main.async {
                    self.output = result
                    self.outModel = modelName
                    self.outInfo = info
                    self.shareURL = share
                    self.positions[.output] = self.positions[.input] ?? 0
                }
            } catch {
                engine = nil
                engineKey = ""
                DispatchQueue.main.async { self.toast("변환 실패: \(error.localizedDescription)") }
            }
            DispatchQueue.main.async {
                self.busy = false
                self.busyText = ""
                UIApplication.shared.isIdleTimerDisabled = false
                UIApplication.shared.endBackgroundTask(background)
            }
        }
    }

    // MARK: - realtime

    /// Where realtime audio comes from and goes to, for the stats (asked on start and on route changes only).
    func updateRoute() {
        let route = AVAudioSession.sharedInstance().currentRoute
        let inputs = route.inputs.map { $0.portName }.joined(separator: ", ")
        let outputs = route.outputs.map { $0.portName }.joined(separator: ", ")
        liveRoute = "입력: \(inputs.isEmpty ? "없음" : inputs) · 출력: \(outputs.isEmpty ? "없음" : outputs)"
    }

    /// The microphones iOS offers now. `configure` sets the realtime session category first (not while audio plays).
    func refreshInputs(configure: Bool) {
        if configure && !live && playing == nil && recorder == nil {
            try? LiveParams.configureSession(bluetoothMic: liveBluetoothMic)
        }
        inputChoices = LiveParams.inputChoices().map { InputChoice(id: $0.id, name: $0.name) }
        if !inputChoices.contains(where: { $0.id == liveInputID }) {
            inputChoices.append(InputChoice(id: liveInputID, name: "연결 안 됨 (자동으로 해요)"))
        }
    }

    func toggleLive() {
        if let e = liveEngine { e.stop(); return }
        guard ready, !busy, !recording else { return }
        let session = AVAudioSession.sharedInstance()
        switch session.recordPermission {
        case .granted:
            startLive()
        case .undetermined:
            session.requestRecordPermission { ok in
                DispatchQueue.main.async {
                    if ok { self.startLive() } else { self.toast("마이크 권한이 있어야 실시간 변환을 할 수 있어요") }
                }
            }
        default:
            toast("설정 › Voice Studio에서 마이크를 허용해 주세요")
        }
    }

    private func startLive() {
        let dir = modelsDir.appendingPathComponent(model)
        let threads = self.threads
        let decoder = self.decoder
        let npu = self.npu
        let key = "\(dir.path)|\(threads)|\(decoder.rawValue)|\(npu)"
        let modelName = model
        var params = LiveParams()
        params.block = liveBlock
        params.context = liveContext
        params.crossfade = liveCrossfade
        params.cushion = liveCushion
        params.thresholdDb = liveThreshold
        params.gainDb = liveGain
        params.inputID = liveInputID
        params.speaker = liveSpeaker
        params.bluetoothMic = liveBluetoothMic
        params.bypass = liveBypass
        let session = LiveEngine(params: params, settings: rvcSettings())
        stopPlayback()
        busy = true
        liveStarting = true
        liveStats = LiveEngine.Stats()
        work.async { [self] in
            do {
                releaseIfTight("realtime")
                if engineKey != key {
                    engine = nil
                    engine = try Rvc(commonDir: commonDir, modelDir: dir, threads: threads, decoder: decoder, npu: npu)
                    engineKey = key
                }
                guard let e = engine else { throw RvcError("모델을 불러오지 못했어요") }
                DispatchQueue.main.async { self.busyText = "준비 중…" }
                session.warmUp(e)  // before the microphone opens, so nothing piles up meanwhile
                try session.start()
                UserDefaults.standard.set(true, forKey: "live_running")
                DispatchQueue.main.async {
                    self.liveEngine = session
                    self.live = true
                    self.liveStarting = false
                    self.updateRoute()
                    UIApplication.shared.isIdleTimerDisabled = true
                }
                session.run(e)  // until stopped
            } catch {
                session.stop()
                DispatchQueue.main.async { self.toast("실시간을 시작하지 못했어요: \(error.localizedDescription)") }
            }
            let recorded = session.stopRecording()  // still recording when the session stopped
            let failure = session.failure
            DispatchQueue.main.async { self.liveEnded(recorded: recorded, model: modelName, failure: failure) }
        }
    }

    private func liveEnded(recorded: (input: URL, output: URL, seconds: Double)?, model: String, failure: String?) {
        UserDefaults.standard.set(false, forKey: "live_running")
        liveEngine = nil
        live = false
        liveStarting = false
        liveRecording = false
        busy = false
        UIApplication.shared.isIdleTimerDisabled = false
        if let failure { toast("실시간 변환이 멈췄어요: \(failure)") }
        if let recorded { liveRecorded(recorded, model: model) }
    }

    /// Records the realtime session (microphone into recordings/, converted voice into outputs/) until pressed again.
    func toggleLiveRecording() {
        guard let e = liveEngine else { return }
        if liveRecording {
            liveRecording = false
            if let r = e.stopRecording() { liveRecorded(r, model: model) }
            return
        }
        let stamp = Self.stamp("MMdd_HHmmss")
        let mic = recordingsDir.appendingPathComponent("실시간원본_\(stamp).wav")
        let converted = outputsDir.appendingPathComponent(Self.sanitize("실시간_\(stamp)_\(model)") + ".wav")
        do {
            try e.startRecording(input: mic, output: converted)
            liveRecording = true
        } catch {
            toast("녹음을 시작하지 못했어요: \(error.localizedDescription)")
        }
    }

    /// The recording's two files land on the 변환 page as original and result, to listen back and share.
    private func liveRecorded(_ r: (input: URL, output: URL, seconds: Double), model: String) {
        if r.seconds < 0.5 {
            try? FileManager.default.removeItem(at: r.input)
            try? FileManager.default.removeItem(at: r.output)
            return
        }
        refreshRecordings()
        guard let mic = try? AudioIO.load(r.input), let converted = try? AudioIO.load(r.output) else { return }
        setInput(Clip(mic, name: r.input.deletingPathExtension().lastPathComponent), fromTake: false)
        output = Clip(converted, name: r.output.deletingPathExtension().lastPathComponent)
        outModel = model
        outInfo = String(format: "실시간 녹음 %.1f초 · 파일 앱 › Voice Studio › outputs에 저장했어요", r.seconds)
        shareURL = r.output
        toast(String(format: "실시간 녹음 %.1f초를 저장했어요. 변환 탭에서 다시 들을 수 있어요", r.seconds))
    }

    /// Keeps the result in outputs/, which the Files app shows.
    func saveOutput() {
        guard let c = output else { return }
        do {
            try AudioIO.writeWav(c.samples, to: outputsDir.appendingPathComponent(c.name + ".wav"))
            toast("파일 앱 › 나의 iPhone › Voice Studio › outputs에 저장했어요")
        } catch {
            toast("저장하지 못했어요: \(error.localizedDescription)")
        }
    }
}
