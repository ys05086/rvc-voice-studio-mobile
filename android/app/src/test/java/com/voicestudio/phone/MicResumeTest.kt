package com.voicestudio.phone

import org.junit.Assert.*
import org.junit.Test

class MicResumeTest {
    @Test fun resumedInputCannotReadSpeechFromPreviousSession() {
        val ring = SampleRing(16)
        ring.write(FloatArray(12) { .8f })
        val boundary = ring.total
        ring.write(floatArrayOf(.1f, .2f, .3f, .4f))
        assertArrayEquals(floatArrayOf(0f,0f,0f,0f,.1f,.2f,.3f,.4f), ring.copy(8,8,boundary), 0f)
        assertEquals(16L, ring.total)
    }

    @Test fun resumeBoundarySurvivesRingWrapAndMultiplePauses() {
        val ring = SampleRing(8)
        ring.write(FloatArray(20) { 1f })
        val first = ring.total
        ring.write(floatArrayOf(.1f,.2f))
        assertArrayEquals(floatArrayOf(0f,0f,.1f,.2f), ring.copy(18,4,first), 0f)
        val second = ring.total
        ring.write(floatArrayOf(.3f,.4f))
        assertArrayEquals(floatArrayOf(0f,0f,.3f,.4f), ring.copy(20,4,second), 0f)
        assertArrayEquals(floatArrayOf(.1f,.2f,.3f,.4f), ring.copy(20,4), 0f)
    }
}
