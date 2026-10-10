package com.voicestudio.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/** The Kotlin port against golden vectors written by phone-vc/make_golden.py from pipeline.py. */
class DspTest {
    private fun golden(name: String): DoubleArray {
        val bytes = javaClass.getResourceAsStream("/golden/$name.bin")!!.readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return DoubleArray(bytes.size / 8) { b.double }
    }

    private fun assertClose(want: DoubleArray, got: DoubleArray, tol: Double, what: String) {
        assertEquals("$what length", want.size, got.size)
        var worst = 0.0
        for (i in want.indices) worst = max(worst, abs(want[i] - got[i]))
        assertTrue("$what: max difference $worst > $tol", worst <= tol)
    }

    private val salience = golden("salience").map { it.toFloat() }.toFloatArray()
    private val f0 = Dsp.decodeF0(salience, salience.size / 360)

    @Test fun decodesPitch() = assertClose(golden("f0"), f0, 1e-3, "f0")

    @Test fun fillsShortGapsOnly() = assertClose(golden("fill4"), Dsp.fillUnvoiced(golden("f0"), 4), 1e-9, "fill 4")

    @Test fun fillsEveryGap() = assertClose(golden("fill_all"), Dsp.fillUnvoiced(golden("f0"), null), 1e-9, "fill all")

    @Test fun shapesPitch() = assertClose(golden("shaped"),
        Dsp.shapePitch(golden("fill4"), 120.0, 1.3, 330.0, 1.5, 120.0, 1.2), 1e-9, "shaped")

    @Test fun coarsePitch() {
        val shift = Math.pow(2.0, 9.5 / 12)
        val got = golden("shaped").map { Dsp.coarse(it * shift).toDouble() }.toDoubleArray()
        val want = golden("coarse")
        val off = want.indices.count { want[it] != got[it] }  // numpy rounds halves to even
        assertTrue("coarse pitch: $off of ${want.size} differ", off <= 1)
    }

    @Test fun stretchesLatent() {
        val z = golden("z").map { it.toFloat() }.toFloatArray()
        assertClose(golden("z_linear"), Dsp.stretchLinear(z, 192, 37, 41).map { it.toDouble() }.toDoubleArray(), 1e-5, "z 37->41")
        assertClose(golden("z_shrink"), Dsp.stretchLinear(z, 192, 37, 31).map { it.toDouble() }.toDoubleArray(), 1e-5, "z 37->31")
    }

    @Test fun stretchesPitchNearest() = assertClose(golden("f0_nearest"), Dsp.stretchNearest(golden("f0"), 250), 1e-12, "f0 nearest")

    @Test fun loudnessEnvelope() {
        val x = golden("rms_in").map { it.toFloat() }.toFloatArray()
        assertClose(golden("rms_out"), Dsp.frameRms(x).map { it.toDouble() }.toDoubleArray(), 1e-5, "rms")
    }

    @Test fun excitationPhase() {
        val sine = Dsp.nsfSource(golden("glide"), Random(0), noiseStd = 0.0).map { it.toDouble() }.toDoubleArray()
        assertClose(golden("sine"), sine, 1e-5, "sine")
    }

    @Test fun excitationCarriesPhaseAcrossWindows() {
        // realtime: two windows that overlap, the second starting `advance` frames into the first, continue the sine
        // exactly where one long window would be (noise off, so only the phase matters)
        val f0 = golden("glide")  // 40 voiced frames
        val advance = 25
        val (first, phase) = Dsp.nsfSource(f0, Random(0), 0.0, advance * 480, noiseStd = 0.0)
        val (second, _) = Dsp.nsfSource(f0.copyOfRange(advance, f0.size), Random(0), phase, -1, noiseStd = 0.0)
        for (i in 0 until (f0.size - advance) * 480) assertEquals("sample $i", first[advance * 480 + i], second[i], 1e-5f)
    }

    @Test fun solaFindsTheShift() {
        val previous = FloatArray(960) { sin(it * 0.05).toFloat() }
        val head = FloatArray(960 + 480) { sin((it - 123) * 0.05).toFloat() }
        assertEquals(123, Dsp.solaOffset(head, previous, 480))
    }

    @Test fun pitchCacheShiftsWithTheWindow() {
        val s = StreamState(10, 3)
        for (i in 0 until 10) s.f0[i] = i.toDouble()
        s.advance(3)
        assertEquals(listOf(3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 0.0, 0.0, 0.0), s.f0.toList())
        assertEquals(4960, StreamState.rmvpeSamples(20))  // 32 * 5 frames - 1: RMVPE pads nothing
    }

    @Test fun fastResamplersMatchTheGeneralOne() {
        val rng = Random(3)
        val x = FloatArray(48000) { (rng.nextGaussian() * 0.2).toFloat() }
        // the whole-factor shortcut (48 -> 16 kHz) against the general weights, worked out by the cached plan
        val fast = Dsp.resample(x, 48000, 16000)
        val general = Dsp.resampleRepeated(x, 48000, 16000)
        assertEquals(general.size, fast.size)
        for (i in fast.indices) assertEquals("sample $i", general[i], fast[i], 1e-5f)
        // the cached plan (realtime's formant step) against the general path, twice (the second from the cache)
        val y = x.copyOf(466 * 50)
        val once = Dsp.resample(y, 466, 480)
        for (round in 0 until 2) {
            val cached = Dsp.resampleRepeated(y, 466, 480)
            for (i in once.indices) assertEquals("sample $i", once[i], cached[i], 1e-5f)
        }
    }

    @Test fun resamplesCleanly() {
        // a 1 kHz tone survives 48k -> 16k -> 48k with its level, and a 12 kHz tone is removed going down
        val n = 48000
        val tone = FloatArray(n) { (0.5 * Math.sin(2 * Math.PI * 1000 * it / 48000.0)).toFloat() }
        val down = Dsp.resample(tone, 48000, 16000)
        assertEquals(16000, down.size)
        val back = Dsp.resample(down, 16000, 48000)
        var err = 0.0; var ref = 0.0
        for (i in 4800 until n - 4800) { err += (back[i] - tone[i]).let { it * it }.toDouble(); ref += (tone[i] * tone[i]).toDouble() }
        assertTrue("1 kHz round trip SNR ${10 * Math.log10(ref / err)} dB", 10 * Math.log10(ref / err) > 40)
        val high = FloatArray(n) { (0.5 * Math.sin(2 * Math.PI * 12000 * it / 48000.0)).toFloat() }
        val leaked = Dsp.resample(high, 48000, 16000).drop(1000).dropLast(1000).maxOf { abs(it) }
        assertTrue("12 kHz leaks $leaked after 48k -> 16k", leaked < 0.005)
    }
}
