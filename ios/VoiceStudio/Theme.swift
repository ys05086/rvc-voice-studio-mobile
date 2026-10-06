import SwiftUI
import UIKit

/// The Voice Studio palette (web/studio.css, the Android app's colors.xml): lavender accent on warm white, and
/// its dark version.
enum Theme {
    static let bg = Color(light: 0xF7F4FA, dark: 0x1C1922)
    static let panel = Color(light: 0xFFFEFD, dark: 0x26222E)
    static let line = Color(light: 0xE9E3F0, dark: 0x3A3445)
    static let text = Color(light: 0x3F3A4A, dark: 0xECE7F3)
    static let muted = Color(light: 0x857B94, dark: 0xA79EB5)
    static let accent = Color(light: 0x9E83BC, dark: 0xB39BD2)
    static let accentStrong = Color(light: 0x7C62A0, dark: 0xCBB6E6)
    static let lavender = Color(light: 0xEAE3F5, dark: 0x3A3150)
    static let track = Color(light: 0xEEEAF3, dark: 0x332D3D)
    static let fill = Color(light: 0xB9A4D3, dark: 0x8F78AE)
    static let green = Color(light: 0x5F9F82, dark: 0x8CC8AA)
    static let mint = Color(light: 0xE3F1EA, dark: 0x23372E)
    static let rec = Color(light: 0xD0607A, dark: 0xE07C93)
    static let recSoft = Color(light: 0xF8E3E8, dark: 0x45262F)
}

extension Color {
    init(light: UInt32, dark: UInt32) {
        self.init(uiColor: UIColor { traits in
            let hex = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
                           blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
        })
    }
}

struct Card<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) { content }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 18).fill(Theme.panel))
            .overlay(RoundedRectangle(cornerRadius: 18).stroke(Theme.line, lineWidth: 1))
    }
}

struct CardTitle: View {
    let text: String
    var hint: String?

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(text).font(.system(size: 15, weight: .bold))
            Spacer()
            if let hint { Text(hint).font(.system(size: 11)).foregroundColor(Theme.muted) }
        }
    }
}

struct Hint: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text).font(.system(size: 12)).foregroundColor(Theme.muted).fixedSize(horizontal: false, vertical: true)
    }
}

/// Lavender pill for secondary actions; `primary` is the wide accent button.
struct PillStyle: ButtonStyle {
    var primary = false

    func makeBody(configuration: Configuration) -> some View {
        PillLabel(configuration: configuration, primary: primary)
    }
}

private struct PillLabel: View {
    let configuration: ButtonStyleConfiguration
    let primary: Bool
    @Environment(\.isEnabled) private var enabled

    var body: some View {
        configuration.label
            .font(.system(size: primary ? 16 : 14, weight: .semibold))
            .foregroundColor(primary ? .white : Theme.accentStrong)
            .padding(.horizontal, primary ? 18 : 14)
            .padding(.vertical, primary ? 14 : 8)
            .frame(maxWidth: primary ? CGFloat.infinity : nil)
            .background(RoundedRectangle(cornerRadius: primary ? 16 : 12).fill(primary ? Theme.accent : Theme.lavender))
            .opacity(enabled ? (configuration.isPressed ? 0.75 : 1) : 0.4)
    }
}

/// A round icon button (play, pause, rewind).
struct RoundIcon: View {
    let icon: String
    let action: () -> Void
    @Environment(\.isEnabled) private var enabled

    init(_ icon: String, action: @escaping () -> Void) {
        self.icon = icon
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.system(size: 17, weight: .semibold))
                .foregroundColor(Theme.accentStrong)
                .frame(width: 46, height: 46)
                .background(Circle().fill(Theme.lavender))
        }
        .buttonStyle(.plain)
        .opacity(enabled ? 1 : 0.4)
    }
}

/// A labelled slider with its value on the right and scale labels under it. `onCommit` runs when the finger lifts.
struct SliderRow: View {
    let title: String
    var hint: String?
    let valueText: String
    @Binding var value: Double
    let range: ClosedRange<Double>
    let step: Double
    let scale: [String]
    let onCommit: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.system(size: 14, weight: .medium))
                    if let hint { Text(hint).font(.system(size: 11)).foregroundColor(Theme.muted) }
                }
                Spacer()
                Text(valueText).font(.system(size: 14, design: .monospaced)).foregroundColor(Theme.accentStrong)
            }
            Slider(value: $value, in: range, step: step, onEditingChanged: { editing in if !editing { onCommit() } })
                .tint(Theme.accent)
            HStack {
                ForEach(Array(scale.enumerated()), id: \.offset) { i, s in
                    Text(s)
                        .font(.system(size: 10, design: .monospaced))
                        .foregroundColor(Theme.muted)
                        .frame(maxWidth: .infinity, alignment: i == 0 ? .leading : (i == scale.count - 1 ? .trailing : .center))
                }
            }
        }
        .padding(.top, 6)
    }
}

/// A recorder-app waveform: bars on a dB scale (so whispers still show), the played part in the accent colour, a
/// playhead, and touch-to-seek. With `live` (one peak per 20 ms) the bars scroll in from the right instead.
struct Waveform: View {
    let envelope: [Float]  // one peak per 480 samples (AudioIO.envelope)
    let samples: Int
    let position: Int
    var live: [Float]?
    var height: CGFloat = 104
    var onSeek: ((Int, Bool) -> Void)?  // last, so a trailing closure lands here

    var body: some View {
        GeometryReader { geo in
            Canvas { context, size in draw(context, size) }
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0)
                    .onChanged { g in seek(g.location.x, geo.size.width, done: false) }
                    .onEnded { g in seek(g.location.x, geo.size.width, done: true) })
        }
        .frame(height: height)
        .background(RoundedRectangle(cornerRadius: 14).fill(Theme.track.opacity(0.6)))
    }

    private func seek(_ x: CGFloat, _ width: CGFloat, done: Bool) {
        guard live == nil, samples > 1, width > 0 else { return }
        onSeek?(Int(min(max(x / width, 0), 1) * CGFloat(samples - 1)), done)
    }

    private func barHeight(_ peak: Float) -> CGFloat {  // -60 dB at the axis, 0 dB full height
        let db = 20 * log10(peak + 1e-6)
        return CGFloat(min(max((db + 60) / 60, 0.02), 1))
    }

    private func draw(_ context: GraphicsContext, _ size: CGSize) {
        let step: CGFloat = 3
        let mid = size.height / 2
        let half = size.height / 2 - 6
        var axis = Path()
        axis.move(to: CGPoint(x: 0, y: mid))
        axis.addLine(to: CGPoint(x: size.width, y: mid))
        context.stroke(axis, with: .color(Theme.line), lineWidth: 1)
        let bar = StrokeStyle(lineWidth: 2, lineCap: .round)
        if let live {
            var p = Path()
            let n = Int(size.width / step)
            for i in max(0, live.count - n)..<live.count {
                let x = size.width - CGFloat(live.count - i) * step + step / 2
                let h = barHeight(live[i]) * half
                p.move(to: CGPoint(x: x, y: mid - h))
                p.addLine(to: CGPoint(x: x, y: mid + h))
            }
            context.stroke(p, with: .color(Theme.accent), style: bar)
            return
        }
        guard samples > 0, !envelope.isEmpty else { return }
        let bars = max(1, Int(size.width / step))
        let headX = CGFloat(position) / CGFloat(samples) * size.width
        var played = Path()
        var rest = Path()
        for i in 0..<bars {
            let a = i * envelope.count / bars
            let b = min(max(a + 1, (i + 1) * envelope.count / bars), envelope.count)
            var peak: Float = 0
            for k in a..<b { peak = max(peak, envelope[k]) }
            let x = CGFloat(i) * step + step / 2
            let h = barHeight(peak) * half
            if x <= headX {
                played.move(to: CGPoint(x: x, y: mid - h))
                played.addLine(to: CGPoint(x: x, y: mid + h))
            } else {
                rest.move(to: CGPoint(x: x, y: mid - h))
                rest.addLine(to: CGPoint(x: x, y: mid + h))
            }
        }
        context.stroke(rest, with: .color(Theme.fill.opacity(0.6)), style: bar)
        context.stroke(played, with: .color(Theme.accent), style: bar)
        var head = Path()
        head.move(to: CGPoint(x: headX, y: 0))
        head.addLine(to: CGPoint(x: headX, y: size.height))
        context.stroke(head, with: .color(Theme.rec), lineWidth: 2)
    }
}

/// One line: label, slider, value. For the controls that stay on screen.
struct CompactSlider: View {
    let title: String
    let valueText: String
    @Binding var value: Double
    let range: ClosedRange<Double>
    let step: Double
    let onCommit: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Text(title).font(.system(size: 13, weight: .medium)).frame(width: 62, alignment: .leading)
            Slider(value: $value, in: range, step: step, onEditingChanged: { editing in if !editing { onCommit() } })
                .tint(Theme.accent)
            Text(valueText)
                .font(.system(size: 13, design: .monospaced))
                .foregroundColor(Theme.accentStrong)
                .frame(width: 66, alignment: .trailing)
        }
    }
}

/// A card that folds away: settings stay out of sight until asked for, so each page fits one screen.
struct Fold<Content: View>: View {
    let title: String
    var hint: String?
    @Binding var open: Bool
    @ViewBuilder var content: Content

    var body: some View {
        Card {
            Button {
                withAnimation(.easeInOut(duration: 0.2)) { open.toggle() }
            } label: {
                HStack(alignment: .firstTextBaseline) {
                    Text(title).font(.system(size: 14, weight: .bold))
                    if let hint { Text(hint).font(.system(size: 11)).foregroundColor(Theme.muted) }
                    Spacer()
                    Image(systemName: open ? "chevron.up" : "chevron.down")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(Theme.muted)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            if open { content }
        }
    }
}

/// The short message at the bottom (also shown over the settings sheet).
struct ToastOverlay: ViewModifier {
    @ObservedObject var app: AppModel

    func body(content: Content) -> some View {
        content.overlay(alignment: .bottom) {
            if let text = app.message {
                Text(text)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundColor(.white)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(Capsule().fill(Color.black.opacity(0.78)))
                    .padding(.horizontal, 24)
                    .padding(.bottom, 24)
                    .onTapGesture { app.message = nil }
                    .task(id: text) {
                        try? await Task.sleep(nanoseconds: 3_000_000_000)
                        if app.message == text { app.message = nil }
                    }
            }
        }
    }
}
