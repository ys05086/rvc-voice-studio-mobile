package com.voicestudio.phone

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class LiveOptimizationTest {
    @Test fun rollingResamplerMatchesFullCalculationAtBothEdgesAndAfterDiscontinuity() {
        val random=java.util.Random(42)
        val all=FloatArray(48000*5) { (random.nextDouble()-.5).toFloat() }
        val resampler=Dsp.WindowResampler()
        val size=76800
        val advance=12960
        val reusedInput=FloatArray(size)
        for(start in 0..(all.size-size) step advance) {
            System.arraycopy(all,start,reusedInput,0,size)
            // Impulses at both boundaries make incorrect reuse visible.
            if(start==advance*3) { reusedInput[0]=1f; reusedInput[size-1]=1f }
            val expected=Dsp.resample(reusedInput,48000,16000)
            assertArrayEquals(expected,resampler.process(reusedInput,advance),0f)
        }
        resampler.reset()
        assertArrayEquals(Dsp.resample(reusedInput,48000,16000),resampler.process(reusedInput,advance),0f)
    }
    @Test fun fixedDecoderKeepsRequiredContextAtAllSupportedFormants() {
        for (block in 15..50) for (formant in listOf(-2.0,-0.5,0.0,2.0)) {
            val tail=block+9
            val shape=Rvc.LiveShape(block,tail,formant)
            val required=(tail+Rvc.LIVE_DEC_CONTEXT)*2.0.pow(formant/12)
            assertTrue(shape.decoderFrames>=required)
            assertTrue(shape.decoderFrames<required+1)
            assertTrue(shape.decoderFrames<=Rvc.LiveShape(block,tail).decoderFrames)
        }
    }
    @Test fun quietDetectionKeepsSpeechAnywhereInContext() {
        val x=FloatArray(48000)
        assertTrue(LiveCore.isQuietContext(x,.001f))
        x.fill(.01f,0,480)
        assertFalse(LiveCore.isQuietContext(x,.001f))
        x.fill(0f); x.fill(.01f,x.size-480,x.size)
        assertFalse(LiveCore.isQuietContext(x,.001f))
    }
    @Test fun quietDetectionDoesNotHideInvalidOrShortInput() {
        assertFalse(LiveCore.isQuietContext(FloatArray(480),.001f))
        val x=FloatArray(48000)
        x[24000]=Float.NaN
        assertFalse(LiveCore.isQuietContext(x,.001f))
        assertFalse(LiveCore.isQuietContext(FloatArray(48000),0f))
    }
}
