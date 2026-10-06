import SwiftUI
import UIKit
import UniformTypeIdentifiers

@main
struct VoiceStudioApp: App {
    var body: some Scene {
        WindowGroup { ContentView() }
    }
}

struct ContentView: View {
    @StateObject private var app = AppModel()

    var body: some View {
        VStack(spacing: 12) {
            header
            tabs
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(spacing: 12) {
                        switch app.page {
                        case .record: RecordPage(app: app)
                        case .convert: ConvertPage(app: app)
                        case .live: LivePage(app: app)
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.bottom, 24)
                }
                .onChange(of: app.output?.id) { id in
                    if id != nil { withAnimation { proxy.scrollTo("result", anchor: .bottom) } }
                }
            }
        }
        .background(Theme.bg.ignoresSafeArea())
        .foregroundColor(Theme.text)
        .fileImporter(isPresented: $app.showPicker, allowedContentTypes: [.audio]) { result in
            app.picked(result.map { [$0] })
        }
        .modifier(ToastOverlay(app: app))
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.willEnterForegroundNotification)) { _ in
            if !app.busy { app.refreshModels() }
        }
    }

    private var header: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                (Text("voice ") + Text("/").foregroundColor(Theme.accent) + Text(" studio"))
                    .font(.system(size: 22, weight: .bold, design: .rounded))
                Text("폰에서 녹음하고 바로 바꾸는 목소리").font(.system(size: 12)).foregroundColor(Theme.muted)
            }
            Spacer()
            Text(app.ready ? "준비됨" : "모델 필요")
                .font(.system(size: 12, weight: .semibold))
                .foregroundColor(app.ready ? Theme.green : Theme.rec)
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(Capsule().fill(app.ready ? Theme.mint : Theme.recSoft))
            Button { app.showSettings = true } label: {
                Image(systemName: "gearshape.fill")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundColor(Theme.accentStrong)
                    .frame(width: 38, height: 38)
                    .background(Circle().fill(Theme.lavender))
            }
            .buttonStyle(.plain)
            .padding(.leading, 6)
        }
        .padding(.horizontal, 20)
        .padding(.top, 8)
        .sheet(isPresented: $app.showSettings) { SettingsSheet(app: app) }
    }

    private var tabs: some View {
        HStack(spacing: 4) {
            tab("녹음", .record)
            tab("변환", .convert)
            tab("실시간", .live)
        }
        .padding(4)
        .background(RoundedRectangle(cornerRadius: 14).fill(Theme.track))
        .padding(.horizontal, 16)
    }

    private func tab(_ title: String, _ page: Page) -> some View {
        Button { app.show(page) } label: {
            Text(title)
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(app.page == page ? Theme.accentStrong : Theme.muted)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 9)
                .background(RoundedRectangle(cornerRadius: 11).fill(app.page == page ? Theme.panel : Color.clear))
        }
        .buttonStyle(.plain)
    }
}

struct RecordPage: View {
    @ObservedObject var app: AppModel

    var body: some View {
        VStack(spacing: 12) {
            Card {
                HStack(alignment: .firstTextBaseline) {
                    Text(app.recTitle).font(.system(size: 15, weight: .bold)).lineLimit(1)
                    Spacer()
                    Text(app.recTimeText).font(.system(size: 14, design: .monospaced)).foregroundColor(Theme.accentStrong)
                }
                Waveform(envelope: app.take?.envelope ?? [], samples: app.take?.samples.count ?? 0,
                         position: app.positions[.take] ?? 0, live: app.recording ? app.livePeaks : nil) { at, done in
                    app.seek(.take, to: at, done: done)
                }
                HStack(spacing: 28) {
                    Spacer()
                    RoundIcon("backward.end.fill") { app.rewindTake() }
                        .disabled(app.take == nil || app.recording)
                    recordButton
                    RoundIcon(app.playing == .take ? "pause.fill" : "play.fill") { app.togglePlay(.take) }
                        .disabled(app.take == nil || app.recording)
                    Spacer()
                }
                .padding(.vertical, 4)
                Hint(app.recInfo).frame(maxWidth: .infinity)
            }
            Button("이 녹음 변환하기 →") { app.show(.convert) }
                .buttonStyle(PillStyle(primary: true))
                .disabled(app.take == nil || app.recording)
            Fold(title: "녹음 목록", hint: "\(app.recordings.count)개", open: $app.recListOpen) {
                if app.recordings.isEmpty {
                    Hint("아직 녹음이 없어요")
                } else {
                    Hint("눌러서 열기 · 길게 눌러 지우기")
                    ScrollView {
                        LazyVStack(spacing: 6) {
                            ForEach(app.recordings) { r in
                                Button { app.open(r) } label: {
                                    HStack {
                                        Text(r.name).font(.system(size: 14)).lineLimit(1)
                                        Spacer()
                                        Text(AppModel.clock(r.seconds)).font(.system(size: 12, design: .monospaced)).foregroundColor(Theme.muted)
                                    }
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 11)
                                    .background(RoundedRectangle(cornerRadius: 12).fill(Theme.lavender.opacity(0.55)))
                                    .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                                .disabled(app.recording)
                                .contextMenu {
                                    Button(role: .destructive) { app.delete(r) } label: { Label("지우기", systemImage: "trash") }
                                }
                            }
                        }
                    }
                    .frame(maxHeight: 300)
                }
            }
        }
    }

    private var recordButton: some View {
        Button { app.toggleRecording() } label: {
            ZStack {
                Circle().stroke(Theme.rec.opacity(0.35), lineWidth: 4).frame(width: 74, height: 74)
                if app.recording {
                    RoundedRectangle(cornerRadius: 6).fill(Theme.rec).frame(width: 26, height: 26)
                } else {
                    Circle().fill(Theme.rec).frame(width: 58, height: 58)
                }
            }
        }
        .buttonStyle(.plain)
        .disabled(app.busy)
    }
}

struct ConvertPage: View {
    @ObservedObject var app: AppModel

    /// One screen: original, pitch, convert, result. Everything else folds away at the bottom.
    var body: some View {
        VStack(spacing: 10) {
            inputCard
            Card {
                HStack(alignment: .firstTextBaseline) {
                    Text("목소리").font(.system(size: 14, weight: .bold))
                    Text(app.model.isEmpty ? "톱니바퀴에서 모델을 불러오세요" : app.model)
                        .font(.system(size: 12)).foregroundColor(Theme.muted).lineLimit(1)
                    Spacer()
                }
                pitchSlider(app)
                formantSlider(app)
            }
            Button(app.busy ? app.busyText : "변환하기") { app.convert() }
                .buttonStyle(PillStyle(primary: true))
                .disabled(!app.canConvert)
            if app.busy { ProgressView(value: app.progress).tint(Theme.accent) }
            if app.output != nil { resultCard.id("result") }
            Fold(title: "더보기 설정", hint: "음량 · 세부", open: $app.convertMoreOpen) {
                Toggle(isOn: $app.normalize) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("음량 맞추기").font(.system(size: 14, weight: .medium))
                        Text("작게 녹음해도 결과를 알맞은 크기로 키워요").font(.system(size: 11)).foregroundColor(Theme.muted)
                    }
                }
                .tint(Theme.accent)
                Toggle("숨소리 · 자음 살리기", isOn: app.flag("unvoiced_keep", 1)).tint(Theme.accent)
                Toggle("억양 넓히기", isOn: app.flag("intonation_expand", 0)).tint(Theme.accent)
                ForEach(AppModel.specs) { spec in
                    SliderRow(title: spec.label, valueText: spec.format(app.value(spec.key, spec.def)),
                              value: app.binding(spec.key, spec.def), range: spec.min...spec.max, step: spec.step,
                              scale: [spec.minLabel, spec.maxLabel]) { app.commit(spec.key, spec.def) }
                }
                Hint("모델마다 따로 저장되고 실시간에도 같이 쓰여요. \"1 = 꺼짐\"인 항목은 1에서 멀어질수록 강해져요.")
            }
        }
    }

    private var inputCard: some View {
        Card {
            HStack {
                Text(app.input.map { "원본 · \($0.name)" } ?? "원본").font(.system(size: 15, weight: .bold)).lineLimit(1)
                Spacer()
                Button("파일 고르기") { app.pick(.audio) }
                    .buttonStyle(PillStyle())
                    .disabled(app.busy)
            }
            Waveform(envelope: app.input?.envelope ?? [], samples: app.input?.samples.count ?? 0,
                     position: app.positions[.input] ?? 0, height: 72) { at, done in app.seek(.input, to: at, done: done) }
            HStack(spacing: 12) {
                RoundIcon(app.playing == .input ? "pause.fill" : "play.fill") { app.togglePlay(.input) }
                    .disabled(app.input == nil)
                Text(app.timeText(.input)).font(.system(size: 13, design: .monospaced)).foregroundColor(Theme.muted)
                Spacer()
            }
            if !app.inInfo.isEmpty { Hint(app.inInfo) }
        }
    }

    private var resultCard: some View {
        Card {
            Text("변환 결과 · \(app.outModel)").font(.system(size: 15, weight: .bold)).lineLimit(1)
            Waveform(envelope: app.output?.envelope ?? [], samples: app.output?.samples.count ?? 0,
                     position: app.positions[.output] ?? 0, height: 72) { at, done in app.seek(.output, to: at, done: done) }
            HStack(spacing: 10) {
                RoundIcon(app.playing == .output ? "pause.fill" : "play.fill") { app.togglePlay(.output) }
                Text(app.timeText(.output)).font(.system(size: 13, design: .monospaced)).foregroundColor(Theme.muted)
                Spacer()
                Button("저장") { app.saveOutput() }.buttonStyle(PillStyle())
                if let url = app.shareURL {
                    ShareLink(item: url) { Text("공유") }.buttonStyle(PillStyle())
                }
            }
            Hint(app.outInfo)
        }
    }
}

/// The formant (voice texture), right under the pitch on 변환 and 실시간.
func formantSlider(_ app: AppModel) -> some View {
    CompactSlider(title: "포먼트", valueText: String(format: "%+.1f", app.value("formant", 0)),
                  value: app.binding("formant", 0), range: -2...2, step: 0.1) { app.commit("formant", 0) }
}

/// The pitch shift, shared by 변환 and 실시간.
func pitchSlider(_ app: AppModel) -> some View {
    let pitch = Int(app.value("pitch", 0).rounded())
    return CompactSlider(title: "음높이", valueText: pitch == 0 ? "0 반음" : String(format: "%+ld 반음", pitch),
                         value: app.binding("pitch", 0), range: -24...24, step: 1) { app.commit("pitch", 0) }
}
