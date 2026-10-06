import Foundation
import XCTest
@testable import VSDsp

/// The Swift port against the golden vectors phone-vc/make_golden.py wrote from pipeline.py (the same files the
/// Android DspTest reads). Run with `swift test` at the repository root.
final class DspTests: XCTestCase {
    static let goldenDir = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("android/app/src/test/resources/golden")

    func golden(_ name: String) throws -> [Double] {
        let data = try Data(contentsOf: Self.goldenDir.appendingPathComponent("\(name).bin"))
        return data.withUnsafeBytes { raw in
            (0..<data.count / 8).map { Double(bitPattern: UInt64(littleEndian: raw.loadUnaligned(fromByteOffset: $0 * 8, as: UInt64.self))) }
        }
    }

    func assertClose(_ want: [Double], _ got: [Double], _ tol: Double, _ what: String) {
        XCTAssertEqual(want.count, got.count, "\(what) length")
        var worst = 0.0
        for i in 0..<min(want.count, got.count) { worst = max(worst, abs(want[i] - got[i])) }
        XCTAssertLessThanOrEqual(worst, tol, "\(what): max difference \(worst)")
    }

    func testDecodesPitch() throws {
        let salience = try golden("salience").map { Float($0) }
        assertClose(try golden("f0"), Dsp.decodeF0(salience, frames: salience.count / 360), 1e-3, "f0")
    }

    func testFillsShortGapsOnly() throws {
        assertClose(try golden("fill4"), Dsp.fillUnvoiced(try golden("f0"), maxGap: 4), 1e-9, "fill 4")
    }

    func testFillsEveryGap() throws {
        assertClose(try golden("fill_all"), Dsp.fillUnvoiced(try golden("f0"), maxGap: nil), 1e-9, "fill all")
    }

    func testShapesPitch() throws {
        let got = Dsp.shapePitch(try golden("fill4"), lowKnee: 120, lowRatio: 1.3, highKnee: 330, highRatio: 1.5, center: 120, expand: 1.2)
        assertClose(try golden("shaped"), got, 1e-9, "shaped")
    }

    func testCoarsePitch() throws {
        let shift = pow(2.0, 9.5 / 12)
        let got = try golden("shaped").map { Double(Dsp.coarse($0 * shift)) }
        let want = try golden("coarse")
        let off = zip(want, got).filter { $0.0 != $0.1 }.count  // numpy rounds halves to even
        XCTAssertLessThanOrEqual(off, 1, "coarse pitch: \(off) of \(want.count) differ")
    }

    func testStretchesLatent() throws {
        let z = try golden("z").map { Float($0) }
        assertClose(try golden("z_linear"), Dsp.stretchLinear(z, channels: 192, t: 37, n: 41).map { Double($0) }, 1e-5, "z 37->41")
        assertClose(try golden("z_shrink"), Dsp.stretchLinear(z, channels: 192, t: 37, n: 31).map { Double($0) }, 1e-5, "z 37->31")
    }

    func testStretchesPitchNearest() throws {
        assertClose(try golden("f0_nearest"), Dsp.stretchNearest(try golden("f0"), n: 250), 1e-12, "f0 nearest")
    }

    func testLoudnessEnvelope() throws {
        let x = try golden("rms_in").map { Float($0) }
        assertClose(try golden("rms_out"), Dsp.frameRms(x).map { Double($0) }, 1e-5, "rms")
    }

    func testExcitationPhase() throws {
        let sine = Dsp.nsfSource(try golden("glide"), rng: Gaussian(seed: 0), noiseStd: 0).map { Double($0) }
        assertClose(try golden("sine"), sine, 1e-5, "sine")
    }

    func testExcitationPhaseCarriesAcrossWindows() {
        // realtime windows overlap: the second starts 25 frames into the first, with the phase handed over
        let f0 = (0..<60).map { 120 + 2 * Double($0) }
        let whole = Dsp.nsfSource(f0, rng: Gaussian(seed: 1), noiseStd: 0)
        let first = Dsp.nsfSource(Array(f0[0..<40]), rng: Gaussian(seed: 1), startPhase: 0, markAt: 25 * 480, noiseStd: 0)
        let second = Dsp.nsfSource(Array(f0[25..<60]), rng: Gaussian(seed: 1), startPhase: first.phaseAtMark, markAt: -1, noiseStd: 0)
        assertClose(whole[(25 * 480)...].map { Double($0) }, second.samples.map { Double($0) }, 1e-5, "carried phase")
    }

    func testSolaFindsTheShift() {
        let tone = (0..<3000).map { Float(sin(Double($0) * 0.05) + 0.3 * sin(Double($0) * 0.31)) }
        let previous = Array(tone[1000..<1960])
        let head = Array(tone[(1000 - 137)..<(1000 - 137 + 960 + 480)])  // the same sound, 137 samples later
        XCTAssertEqual(Dsp.solaOffset(head, previous, search: 480), 137)
    }

    func testResamplesCleanly() {
        // a 1 kHz tone survives 48k -> 16k -> 48k with its level, and a 12 kHz tone is removed going down
        let n = 48000
        let tone = (0..<n).map { Float(0.5 * sin(2 * Double.pi * 1000 * Double($0) / 48000)) }
        let down = Dsp.resample(tone, 48000, 16000)
        XCTAssertEqual(down.count, 16000)
        let back = Dsp.resample(down, 16000, 48000)
        var err = 0.0
        var ref = 0.0
        for i in 4800..<(n - 4800) {
            err += Double((back[i] - tone[i]) * (back[i] - tone[i]))
            ref += Double(tone[i] * tone[i])
        }
        XCTAssertGreaterThan(10 * log10(ref / err), 40, "1 kHz round trip")
        let high = (0..<n).map { Float(0.5 * sin(2 * Double.pi * 12000 * Double($0) / 48000)) }
        let leaked = Dsp.resample(high, 48000, 16000).dropFirst(1000).dropLast(1000).map { abs($0) }.max() ?? 0
        XCTAssertLessThan(leaked, 0.005, "12 kHz leaks after 48k -> 16k")
    }
}
