import SwiftUI

/// 설정 (the gear at the top): where the models run, for the whole phone.
struct SettingsSheet: View {
    @ObservedObject var app: AppModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                HStack {
                    Text("설정").font(.system(size: 20, weight: .bold))
                    Spacer()
                    Button("닫기") { dismiss() }.buttonStyle(PillStyle())
                }
                Card {
                    CardTitle(text: "목소리 모델", hint: app.ready ? "준비됨" : "모델 필요")
                    if !app.models.isEmpty {
                        Menu {
                            ForEach(app.models, id: \.self) { name in
                                Button(name) { app.model = name }
                            }
                        } label: {
                            HStack {
                                Text(app.model).font(.system(size: 15, weight: .medium)).foregroundColor(Theme.text)
                                Spacer()
                                Image(systemName: "chevron.up.chevron.down").foregroundColor(Theme.muted)
                            }
                            .padding(.horizontal, 14)
                            .padding(.vertical, 12)
                            .background(RoundedRectangle(cornerRadius: 12).stroke(Theme.line, lineWidth: 1))
                        }
                        .disabled(app.busy)
                    }
                    HStack(spacing: 10) {
                        Button("폴더에서 불러오기") { app.pick(.modelFolder) }.buttonStyle(PillStyle())
                        Button("파일에서 불러오기") { app.pick(.modelFiles) }.buttonStyle(PillStyle())
                    }
                    .disabled(app.busy)
                    if app.busy && !app.busyText.isEmpty { Hint(app.busyText) }
                    if !app.commonReady { commonRow }
                    Hint(app.helpText)
                }
                Card {
                    CardTitle(text: "계산 장치", hint: "폰 전체 설정")
                    VStack(alignment: .leading, spacing: 6) {
                        Text("디코더 (소리를 만드는 단계)").font(.system(size: 14, weight: .medium))
                        Picker("디코더", selection: $app.decoder) {
                            ForEach(Rvc.DecoderMode.allCases, id: \.self) { mode in Text(mode.label).tag(mode) }
                        }
                        .pickerStyle(.segmented)
                        Text(app.decoder.hint).font(.system(size: 11)).foregroundColor(Theme.muted)
                    }
                    Toggle(isOn: $app.npu) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("NPU로 변환").font(.system(size: 14, weight: .medium))
                            Text("HuBERT·음높이·enc를 뉴럴 엔진으로 돌려요. 빠르고 메모리도 적게 써요. 16비트지만 소리를 만드는 디코더는 GPU 32비트라 음질은 거의 그대로예요. 처음 켤 때 준비가 조금 걸려요")
                                .font(.system(size: 11)).foregroundColor(Theme.muted)
                        }
                    }
                    .tint(Theme.accent)
                    .padding(.top, 6)
                    SliderRow(title: "변환 스레드", hint: "디코더가 CPU가 아니면 2가 가장 빨라요 (고성능 코어가 2개라서)",
                              valueText: "\(app.threads)",
                              value: Binding(get: { Double(app.threads) }, set: { app.threads = Int($0.rounded()) }),
                              range: 1...6, step: 1, scale: ["1", "6"]) {}
                    Hint("바꾼 설정은 다음 변환이나 실시간을 켤 때부터 적용돼요.")
                }
                Card {
                    CardTitle(text: "기록")
                    Hint("실시간 기록: 파일 앱 › 나의 iPhone › Voice Studio › live_log.txt")
                    Hint("버전 " + ((Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) ?? "?"))
                }
            }
            .padding(16)
        }
        .background(Theme.bg.ignoresSafeArea())
        .foregroundColor(Theme.text)
        // model import lives here, so its picker and name prompt must be presented from the sheet itself
        .fileImporter(isPresented: $app.showModelPicker, allowedContentTypes: app.pickTypes,
                      allowsMultipleSelection: app.pickKind == .modelFiles) { result in app.picked(result) }
        .alert("모델 이름", isPresented: $app.askingName) {
            TextField("이름", text: $app.nameField)
            Button("불러오기") { app.copyIn(name: app.nameField) }
            Button("취소", role: .cancel) { app.cancelImport() }
        } message: {
            Text("목소리 모델을 이 이름으로 저장해요")
        }
        .modifier(ToastOverlay(app: app))
        .presentationDetents([.large])
    }

    /// The shared models (HuBERT, RMVPE), downloaded once; voices are never downloaded.
    private var commonRow: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("공통 모델 받기").font(.system(size: 14, weight: .medium))
                    Text(String(format: "hubert·rmvpe, 처음 한 번 %.0f MB (와이파이 추천)", Double(CommonDownloader.totalBytes) / 1e6))
                        .font(.system(size: 11)).foregroundColor(Theme.muted)
                }
                Spacer()
                if app.downloadProgress == nil {
                    Button("받기") { app.downloadCommon() }.buttonStyle(PillStyle(primary: true)).disabled(app.busy)
                } else {
                    Button("멈추기") { app.cancelDownload() }.buttonStyle(PillStyle())
                }
            }
            if let p = app.downloadProgress {
                ProgressView(value: p).tint(Theme.accent)
                Text(String(format: "%.0f%%", p * 100)).font(.system(size: 11, design: .monospaced)).foregroundColor(Theme.muted)
            }
        }
    }
}
