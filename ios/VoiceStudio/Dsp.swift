import Foundation

/// The numeric pieces of the pipeline: a port of `Dsp` in the Android app's Rvc.kt, itself checked against
/// phone-vc/pipeline.py. Foundation only, so `swift test` can check it against the same golden vectors on Linux.
enum Dsp {
    static let f0Min = 50.0
    static let f0Max = 1100.0
    static let f0MelMin = 1127 * log(1 + f0Min / 700)
    static let f0MelMax = 1127 * log(1 + f0Max / 700)
    static let cents: [Double] = (0..<368).map { i in (i < 4 || i >= 364) ? 0 : 20.0 * Double(i - 4) + 1997.3794084376191 }

    /// RMVPE salience [frames * 360] -> Hz: weighted average of the 9 bins around the peak.
    static func decodeF0(_ salience: [Float], frames: Int, thred: Double = 0.03) -> [Double] {
        var out = [Double](repeating: 0, count: frames)
        for t in 0..<frames {
            var best = 0
            var peak = -Float.infinity
            for b in 0..<360 {
                let v = salience[t * 360 + b]
                if v > peak { peak = v; best = b }
            }
            if Double(peak) <= thred { continue }
            var num = 0.0
            var den = 0.0
            for k in -4...4 {
                let b = best + k
                let s = (b >= 0 && b < 360) ? Double(salience[t * 360 + b]) : 0
                num += s * cents[b + 4]
                den += s
            }
            let f0 = 10 * pow(2.0, num / den / 1200)
            out[t] = f0 == 10 ? 0 : f0
        }
        return out
    }

    /// Unvoiced gaps of at most `maxGap` frames (every gap when nil) filled as np.interp does: linear between
    /// the voiced neighbours, held flat past either end.
    static func fillUnvoiced(_ f0: [Double], maxGap: Int?) -> [Double] {
        let voiced = f0.reduce(0) { $0 + ($1 > 0 ? 1 : 0) }
        if voiced == 0 || voiced == f0.count { return f0 }
        var out = f0
        var t = 0
        while t < f0.count {
            if f0[t] > 0 { t += 1; continue }
            var end = t
            while end < f0.count && f0[end] <= 0 { end += 1 }
            if maxGap == nil || end - t <= maxGap! {
                let left: Int? = t - 1 >= 0 ? t - 1 : nil
                let right: Int? = end < f0.count ? end : nil
                for k in t..<end {
                    if let l = left, let r = right {
                        out[k] = f0[l] + (f0[r] - f0[l]) * Double(k - l) / Double(r - l)
                    } else if let r = right {
                        out[k] = f0[r]
                    } else if let l = left {
                        out[k] = f0[l]
                    }
                }
            }
            t = end
        }
        return out
    }

    static func shapePitch(_ f0: [Double], lowKnee: Double, lowRatio: Double, highKnee: Double, highRatio: Double,
                           center: Double, expand: Double) -> [Double] {
        f0.map { f in
            var v = f
            if expand != 1 && center > 0 && v > center { v = center * pow(2.0, log2(v / center) * expand) }
            if lowRatio > 1 && lowKnee > 0 && v > 0 && v < lowKnee { v = lowKnee * pow(2.0, log2(v / lowKnee) / lowRatio) }
            if highRatio > 1 && highKnee > 0 && v > highKnee { v = highKnee * pow(2.0, log2(v / highKnee) / highRatio) }
            return v
        }
    }

    static func coarse(_ f0: Double) -> Int64 {
        var mel = 1127 * log(1 + f0 / 700)
        if mel > 0 { mel = (mel - f0MelMin) * 254 / (f0MelMax - f0MelMin) + 1 }
        return Int64(min(max(mel, 1), 255).rounded())
    }

    /// SineGen's excitation with a float64 phase: a sine at f0 where voiced plus a little noise.
    static func nsfSource(_ f0: [Double], rng: Gaussian, upp: Int = 480, sineAmp: Double = 0.1, noiseStd: Double = 0.003) -> [Float] {
        nsfSource(f0, rng: rng, startPhase: 0, markAt: -1, upp: upp, sineAmp: sineAmp, noiseStd: noiseStd).samples
    }

    /// The same, starting from `startPhase` (in cycles) and also returning the phase just before sample `markAt`,
    /// where the next realtime window starts.
    static func nsfSource(_ f0: [Double], rng: Gaussian, startPhase: Double, markAt: Int, upp: Int = 480,
                          sineAmp: Double = 0.1, noiseStd: Double = 0.003) -> (samples: [Float], phaseAtMark: Double) {
        var out = [Float](repeating: 0, count: f0.count * upp)
        var phase = startPhase
        var marked = startPhase
        var i = 0
        for f in f0 {
            let step = f / 48000.0
            for _ in 0..<upp {
                if i == markAt { marked = phase }
                phase += step
                phase -= floor(phase)
                let noise = rng.next()
                out[i] = f > 0 ? Float(sineAmp * sin(2 * Double.pi * phase) + noiseStd * noise) : Float(sineAmp / 3 * noise)
                i += 1
            }
        }
        if markAt >= i { marked = phase }
        return (out, marked)
    }

    /// The best start for the next block within `search` samples: where its head lines up with `previous`, the
    /// tail that is about to be cross-faded (normalised cross-correlation, as the RVC realtime GUI's SOLA).
    static func solaOffset(_ head: [Float], _ previous: [Float], search: Int) -> Int {
        let n = previous.count
        guard search > 0, head.count >= n + search else { return 0 }
        var best = 0
        var bestScore = -Float.infinity
        head.withUnsafeBufferPointer { h in
            previous.withUnsafeBufferPointer { p in
                for off in 0...search {
                    var num: Float = 0
                    var den: Float = 1e-8
                    for i in 0..<n {
                        let v = h[off + i]
                        num += v * p[i]
                        den += v * v
                    }
                    let score = num / den.squareRoot()
                    if score > bestScore { bestScore = score; best = off }
                }
            }
        }
        return best
    }

    /// [channels, t] -> [channels, n] as F.interpolate(mode="linear", align_corners=False).
    static func stretchLinear(_ x: [Float], channels: Int, t: Int, n: Int) -> [Float] {
        if n == t { return x }
        var out = [Float](repeating: 0, count: channels * n)
        for i in 0..<n {
            let pos = min(max((Double(i) + 0.5) * Double(t) / Double(n) - 0.5, 0), Double(t - 1))
            let lo = Int(floor(pos))
            let hi = min(lo + 1, t - 1)
            let w = Float(pos - Double(lo))
            for c in 0..<channels { out[c * n + i] = x[c * t + lo] * (1 - w) + x[c * t + hi] * w }
        }
        return out
    }

    static func stretchNearest(_ x: [Double], n: Int) -> [Double] {
        (0..<n).map { i in x[min(i * x.count / n, x.count - 1)] }
    }

    /// librosa.feature.rms(frame 1920, hop 480, centered), interpolated to one value per sample.
    static func frameRms(_ x: [Float], frame: Int = 1920, hop: Int = 480) -> [Float] {
        let half = frame / 2
        let count = 1 + x.count / hop
        var rms = [Double](repeating: 0, count: count)
        for j in 0..<count {
            var sum = 0.0
            let start = j * hop - half
            let a = max(start, 0)
            let b = min(start + frame, x.count)
            if a < b { for k in a..<b { sum += Double(x[k]) * Double(x[k]) } }
            rms[j] = (sum / Double(frame)).squareRoot()
        }
        var out = [Float](repeating: 0, count: x.count)
        let scale = x.count > 1 ? Double(count - 1) / Double(x.count - 1) : 0
        for k in 0..<x.count {
            let pos = Double(k) * scale
            let lo = Int(floor(pos))
            let hi = min(lo + 1, count - 1)
            out[k] = Float(rms[lo] + (rms[hi] - rms[lo]) * (pos - Double(lo)))
        }
        return out
    }

    /// Windowed-sinc (Kaiser) resampling between any two rates, low-passed below the lower Nyquist. The kernel is
    /// tabulated once per rate pair and read with linear interpolation.
    static func resample(_ x: [Float], _ src: Int, _ dst: Int, zeros: Int = 24) -> [Float] {
        if src == dst || x.isEmpty { return x }
        let ratio = Double(dst) / Double(src)
        let n = Int(floor(Double(x.count) * ratio))
        let cutoff = min(1.0, ratio) * 0.95
        let width = Double(zeros) / cutoff  // half width in input samples
        let table = kernel(cutoff: cutoff, width: width)
        let steps = Double(table.count - 1) / width
        var out = [Float](repeating: 0, count: n)
        x.withUnsafeBufferPointer { xp in
            table.withUnsafeBufferPointer { tp in
                for i in 0..<n {
                    let t = Double(i) / ratio
                    let first = max(Int(ceil(t - width)), 0)
                    let last = min(Int(floor(t + width)), x.count - 1)
                    var acc = 0.0
                    if first <= last {
                        for j in first...last {
                            let pos = abs(t - Double(j)) * steps
                            let k = Int(pos)
                            let w = k + 1 < tp.count ? tp[k] + (tp[k + 1] - tp[k]) * (pos - Double(k)) : tp[tp.count - 1]
                            acc += Double(xp[j]) * w
                        }
                    }
                    out[i] = Float(acc)
                }
            }
        }
        return out
    }

    private static var kernels = [String: [Double]]()
    private static let kernelLock = NSLock()

    private static func kernel(cutoff: Double, width: Double) -> [Double] {
        kernelLock.lock()
        defer { kernelLock.unlock() }
        let key = "\(cutoff)|\(width)"
        if let k = kernels[key] { return k }
        let beta = 8.0
        let norm = bessel0(beta)
        let size = 16384
        let table: [Double] = (0...size).map { k in
            let d = Double(k) / Double(size) * width
            let u = d / width
            let arg = Double.pi * cutoff * d
            let sinc = abs(arg) < 1e-12 ? 1.0 : sin(arg) / arg
            return cutoff * sinc * bessel0(beta * max(0, 1 - u * u).squareRoot()) / norm
        }
        kernels[key] = table
        return table
    }

    private static func bessel0(_ x: Double) -> Double {
        var sum = 1.0
        var term = 1.0
        let q = x * x / 4
        for k in 1...30 {
            term *= q / Double(k * k)
            sum += term
            if term < 1e-12 * sum { break }
        }
        return sum
    }
}

/// Seeded normal noise (SplitMix64 + Box-Muller) for the latent noise and the excitation.
final class Gaussian {
    private var state: UInt64
    private var spare: Double?

    init(seed: UInt64) { state = seed }

    private func nextBits() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }

    private func uniform() -> Double { Double(nextBits() >> 11) * (1.0 / 9_007_199_254_740_992.0) }

    func next() -> Double {
        if let s = spare { spare = nil; return s }
        var u = 0.0
        repeat { u = uniform() } while u <= 0
        let r = (-2 * log(u)).squareRoot()
        let a = 2 * Double.pi * uniform()
        spare = r * sin(a)
        return r * cos(a)
    }
}
