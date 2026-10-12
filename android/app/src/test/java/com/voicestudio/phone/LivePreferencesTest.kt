package com.voicestudio.phone

import org.junit.Assert.*
import org.junit.Test

class LivePreferencesTest {
    @Test fun existingDefaultsKeepEachModesAnalysisWindow() {
        val local = LivePreferences.read { _, default -> default }
        val virtual = LivePreferences.read(true) { _, default -> default }
        assertEquals(27, local.block)
        assertEquals(27, virtual.block)
        assertEquals(160, local.window(null))
        assertEquals(160, virtual.window(null))
        assertFalse(local.skipQuiet)
        assertTrue(virtual.skipQuiet)
    }

    @Test fun savedVoiceTimingAndGainReachBothModesIdentically() {
        val saved = mapOf("live_block" to 32, "live_context" to 80, "live_crossfade" to 10,
            "live_cushion_cs" to 12, "live_threshold_db" to -48, "live_gain_db" to 6)
        val read = { key: String, default: Int -> saved[key] ?: default }
        val local = LivePreferences.read(getInt = read)
        val virtual = LivePreferences.read(true, read)
        assertEquals(local.copy(skipQuiet = true), virtual)
        assertEquals(32, virtual.block)
        assertEquals(80, virtual.context)
        assertEquals(10, virtual.crossfade)
        assertEquals(.12, virtual.cushion, .00001)
        assertEquals(-48.0, virtual.thresholdDb, 0.0)
        assertEquals(6.0, virtual.gainDb, 0.0)
    }

    @Test fun outOfRangePreferencesCannotCreateUnsafeAudioShapes() {
        for (value in listOf(-1000, 1000)) {
            val p = LivePreferences.read(true) { _, _ -> value }
            assertTrue(p.block in 15..50)
            assertTrue(p.context in 0..300)
            assertTrue(p.crossfade in 4..12)
            assertTrue(p.cushion in .02.. .20)
            assertTrue(p.gainDb in -12.0..24.0)
        }
    }
}
