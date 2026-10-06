import AVKit
import SwiftUI

/// 실시간: the microphone converted into the earphones, with the numbers that tell whether the phone keeps up.
struct LivePage: View {
    @ObservedObject var app: AppModel

    /// One screen: on/off and record, the two waveforms, the numbers, volume and pitch. Settings fold away below.
    var body: some View {
        VStack(spacing: 10) {
            Card {
                HStack(alignment: .firstTextBaseline) {
                    Text("실시간 변환").font(.system(size: 15, weight: .bold))
                    Text(app.model).font(.system(size: 12)).foregroundColor(Theme.muted).lineLimit(1)
                    Spacer()
                    if app.liveRecording {
                        Text("● " + AppModel.clock(max(app.liveStats.recording, 0)))
                            .font(.system(size: 13, weight: .semibold, design: .monospaced))
                            .foregroundColor(Theme.rec)
                    }
                }
                HStack(spacing: 14) {
                    Button(app.liveStarting ? "준비 중…" : app.live ? "끄기" : "켜기") { app.toggleLive() }
                        .buttonStyle(PillStyle(primary: true))
                        .disabled(!app.live && (!app.ready || app.busy || app.recording))
                    Button { app.toggleLiveRecording() } label: {
                        ZStack {
                            Circle().stroke(Theme.rec.opacity(0.35), lineWidth: 3).frame(width: 50, height: 50)
                            if app.liveRecording {
                                RoundedRectangle(cornerRadius: 4).fill(Theme.rec).frame(width: 18, height: 18)
                            } else {
                                Circle().fill(Theme.rec).frame(width: 36, height: 36)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                    .disabled(!app.live)
                    .opacity(app.live ? 1 : 0.35)
                }
                waveRow("원음", app.live ? app.liveStats.inPeaks : [])
                waveRow("변환", app.live ? app.liveStats.outPeaks : [])
                if app.live {
                    stats
                } else {
                    Hint(app.ready ? "이어폰으로 들으세요. 빨간 버튼은 켠 다음에 녹음해요." : app.helpText)
                }
            }
            Card {
                CompactSlider(title: "출력 크기", valueText: String(format: "%+.0f dB", app.liveGain), value: $app.liveGain,
                              range: 0...30, step: 1) {}
                pitchSlider(app)
                formantSlider(app)
            }
            Fold(title: "더보기 설정", hint: "입출력 · 블록 · 진단", open: $app.liveMoreOpen) {
                section("입력 · 출력", "다음에 켤 때 적용")
                HStack {
                    Text("마이크").font(.system(size: 13, weight: .medium))
                    Spacer()
                    Picker("마이크", selection: $app.liveInputID) {
                        ForEach(app.inputChoices) { choice in Text(choice.name).tag(choice.id) }
                    }
                    .pickerStyle(.menu)
                    .tint(Theme.accentStrong)
                }
                Toggle(isOn: $app.liveBluetoothMic) { label("블루투스 마이크 쓰기", "에어팟 마이크도 고를 수 있지만 통화 음질(16 kHz)로 떨어져요") }
                    .tint(Theme.accent)
                    .onChange(of: app.liveBluetoothMic) { _ in app.refreshInputs(configure: true) }
                Toggle(isOn: $app.liveSpeaker) { label("스피커로 내보내기", "바뀐 목소리가 다시 마이크로 들어갈 수 있어요") }
                    .tint(Theme.accent)
                HStack {
                    label("출력 장치", "에어팟·블루투스 스피커는 iOS 메뉴에서 골라요")
                    Spacer()
                    RoutePicker().frame(width: 40, height: 40)
                }
                section("블록", "다음에 켤 때 적용")
                CompactSlider(title: "블록", valueText: seconds(app.liveBlock), value: frames(\.liveBlock),
                              range: 10...50, step: 2) {}
                CompactSlider(title: "앞 문맥", valueText: seconds(app.liveContext), value: frames(\.liveContext),
                              range: 30...200, step: 10) {}
                CompactSlider(title: "크로스", valueText: seconds(app.liveCrossfade), value: frames(\.liveCrossfade),
                              range: 2...10, step: 1) {}
                CompactSlider(title: "출력 여유", valueText: String(format: "%.2f초", app.liveCushion), value: $app.liveCushion,
                              range: 0...0.3, step: 0.02) {}
                CompactSlider(title: "무음 기준", valueText: String(format: "%.0f dB", app.liveThreshold), value: $app.liveThreshold,
                              range: (-80)...(-30), step: 1) {}
                Hint("블록이 짧을수록 지연이 줄지만 계산이 빠듯해요. 소리가 끊기면 출력 여유를 늘리세요.")
                section("진단", "")
                Toggle(isOn: $app.liveBypass) { label("변환 없이 통과", "모델을 건너뛰고 마이크 소리를 그대로 내보내요") }
                    .tint(Theme.accent)
                HStack {
                    label("진단 기록", "블록마다 걸린 시간·끊김을 적은 파일 (live_log.txt, live_blocks.csv)")
                    Spacer()
                    ShareLink(items: logFiles) { Text("보내기") }
                        .buttonStyle(PillStyle())
                        .disabled(logFiles.isEmpty)
                }
                Hint("빨간 버튼으로 녹음하면 원음은 녹음 목록에, 바뀐 소리는 outputs 폴더에 저장되고 변환 탭에서 다시 들을 수 있어요.")
            }
            .onAppear { app.refreshInputs(configure: true) }
        }
    }

    /// The diagnosis files that exist, to send to a PC.
    private var logFiles: [URL] {
        [Diagnostics.url, LiveEngine.blocksURL, LiveEngine.previousBlocksURL]
            .filter { FileManager.default.fileExists(atPath: $0.path) }
    }

    private func section(_ title: String, _ hint: String) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title).font(.system(size: 13, weight: .bold)).foregroundColor(Theme.accentStrong)
            Text(hint).font(.system(size: 11)).foregroundColor(Theme.muted)
            Spacer()
        }
        .padding(.top, 8)
    }

    private func label(_ title: String, _ hint: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.system(size: 13, weight: .medium))
            Text(hint).font(.system(size: 11)).foregroundColor(Theme.muted)
        }
    }

    private func waveRow(_ label: String, _ peaks: [Float]) -> some View {
        HStack(spacing: 8) {
            Text(label).font(.system(size: 11)).foregroundColor(Theme.muted).frame(width: 26, alignment: .leading)
            Waveform(envelope: [], samples: 0, position: 0, live: peaks, height: 44)
        }
    }

    private func seconds(_ frames: Int) -> String { String(format: "%.2f초", Double(frames) / 100) }

    private func frames(_ key: ReferenceWritableKeyPath<AppModel, Int>) -> Binding<Double> {
        Binding(get: { Double(app[keyPath: key]) }, set: { app[keyPath: key] = Int($0.rounded()) })
    }

    /// The numbers, in four short lines (red when something is off).
    private var stats: some View {
        let s = app.liveStats
        let load = s.block > 0 ? s.compute / s.block * 100 : 0
        let heat = ["보통", "조금 높음", "높음", "심각"][min(max(s.thermal, 0), 3)]
        let memoryWarn = s.memoryLimit > 0 && s.memory > s.memoryLimit * 0.8
        var line2 = String(format: "지연 %.2f초 · 끊김 %ld · 밀림 %ld · 찌그러짐 %ld", s.latency, s.underruns, s.drops, s.clips)
        if s.extraCushion > 0 { line2 += String(format: " · 여유 +%.2f", s.extraCushion) }
        if s.restarts > 0 { line2 += " · 재시작 \(s.restarts)" }
        return VStack(alignment: .leading, spacing: 3) {
            statLine(String(format: "계산 %.2f / %.2f초 (%.0f%%) · 최대 %.2f", s.compute, s.block, load, s.computeMax),
                     warn: load > 85 || s.computeMax > s.block)
            statLine(line2, warn: s.underruns + s.drops + s.clips + s.restarts > 0)
            statLine(String(format: "메모리 %.1f/%.1f GB · 발열 ", s.memory, s.memoryLimit) + heat + " · " + s.device,
                     warn: s.thermal >= 2 || memoryWarn)
            Text(app.liveRoute).font(.system(size: 11)).foregroundColor(Theme.muted).lineLimit(1)
        }
    }

    private func statLine(_ text: String, warn: Bool) -> some View {
        Text(text)
            .font(.system(size: 12, design: .monospaced))
            .foregroundColor(warn ? Theme.rec : Theme.accentStrong)
            .lineLimit(1)
            .minimumScaleFactor(0.7)
    }
}

/// iOS's own audio route menu (AirPods, Bluetooth speakers, AirPlay): apps cannot pick these outputs themselves.
struct RoutePicker: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let view = AVRoutePickerView()
        view.tintColor = UIColor(Theme.accent)
        view.activeTintColor = UIColor(Theme.accentStrong)
        view.prioritizesVideoDevices = false
        return view
    }

    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {}
}
