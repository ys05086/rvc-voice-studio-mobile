package com.voicestudio.phone

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

class ModelSpecTest {
    @Test fun legacyGeometryAndNeutral48OutputStayUnchanged() {
        assertEquals(ModelSpec(), ModelSpec.load(File("nonexistent-model-test-folder")))
        val audio = FloatArray(4800) { Random(it.toLong()).nextFloat() }
        assertArrayEquals(audio, ModelSpec().output48(audio, 10, 1.0, true), 0f)
    }

    @Test fun native32ToneKeepsPitchLevelAndDurationAt48() {
        val input = FloatArray(32000) { (0.5 * sin(2 * PI * 1000 * it / 32000)).toFloat() }
        val spec = ModelSpec(32000)
        val output = spec.output48(input, 100, 1.0, false)
        assertEquals(48000, output.size)
        var error = 0.0
        for (i in 2000 until 46000) error += (output[i] - 0.5 * sin(2 * PI * 1000 * i / 48000)).pow(2)
        assertTrue("Resampled tone error: $error", error / 44000 < 1e-5)
        assertArrayEquals(output, spec.output48(input, 100, 1.0, true), 1e-6f)
    }

    @Test fun formantRestorationReturnsExactLiveDurationForBothRates() {
        for (rate in listOf(32000, 48000)) for (semitones in listOf(-2.0, 0.0, 2.0)) {
            val spec = ModelSpec(rate)
            val factor = 2.0.pow(semitones / 12)
            val count = kotlin.math.ceil(50 * factor).toInt() * spec.hopLength
            val native = FloatArray(count) { sin(it * .04).toFloat() }
            assertEquals(24000, spec.output48(native, 50, factor, true).size)
            assertArrayEquals(spec.output48(native, 50, factor, false), spec.output48(native, 50, factor, true), 1e-6f)
        }
    }

    @Test fun native32ExcitationUses32kClockAndContinuousPhase() {
        val f0 = DoubleArray(60) { 233.0 }
        val (first, phase) = Dsp.nsfSource(f0, Random(0), 0.0, 20 * 320, upp=320, sampleRate=32000, noiseStd=0.0)
        assertEquals(60 * 320, first.size)
        for (i in first.indices) assertEquals((.1 * sin(2 * PI * 233 * (i + 1) / 32000)).toFloat(), first[i], 1e-5f)
        val (second, _) = Dsp.nsfSource(f0, Random(1), phase, 20 * 320, upp=320, sampleRate=32000, noiseStd=0.0)
        for (i in 0 until 40 * 320) assertEquals(first[20 * 320 + i], second[i], 1e-5f)
    }

    @Test fun replacementDropsStaleBackendsAndLegacyMetadata() {
        val main = setOf("model.enc.onnx", "model.dec.onnx")
        assertTrue("model.json" in ModelSpec.obsoleteParts(main))
        assertFalse("model.json" in ModelSpec.obsoleteParts(main + "model.json"))
        assertTrue("model.dec.w8a16.onnx" in ModelSpec.obsoleteParts(main + "model.json"))
        assertTrue(ModelSpec.obsoleteParts(setOf("model.dec.w8a16.onnx")).isEmpty())
        assertFalse("settings.json" in ModelSpec.obsoleteParts(main))
    }

    @Test fun invalidGeometryIsRejected() {
        for (bad in listOf({ ModelSpec(40000) }, { ModelSpec(32000, 480) }, { ModelSpec(32000, 320, 0) },
            { ModelSpec(formatVersion=2) })) {
            try { bad(); fail("Invalid model metadata accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
