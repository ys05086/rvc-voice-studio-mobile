package com.voicestudio.phone

import org.junit.Assert.*
import org.junit.Test

class AutoBlockTest {
    @Test fun efficientModelKeepsRequestedBlock() { assertEquals(27, AutoBlock.nextBlock(27, 210.0)) }
    @Test fun observedContextTwoSecondsNeedsLargerBlock() {
        assertEquals(39, AutoBlock.nextBlock(27, 310.4))
        assertEquals(39, AutoBlock.nextBlock(39, 310.4))
    }
    @Test fun increasedInferenceCostRequiresAnotherMeasurement() {
        assertEquals(47, AutoBlock.nextBlock(37, 330.0))
        assertEquals(47, AutoBlock.nextBlock(47, 375.0))
    }
    @Test fun maximumWithoutHeadroomFailsInsteadOfGrowingForever() {
        assertEquals(50, AutoBlock.nextBlock(47, 500.0))
        assertNull(AutoBlock.nextBlock(50, 500.0))
        assertEquals(50, AutoBlock.nextBlock(50, 400.0))
    }
    @Test fun runtimeBlockDoesNotShrinkExplicitContextOrChangeOtherSettings() {
        val saved = LiveParams(block = 27, context = 200, crossfade = 8, gainDb = 4.0)
        val actual = saved.copy(block = AutoBlock.nextBlock(saved.block, 310.4)!!)
        assertEquals(200, actual.context)
        assertEquals(27, saved.block)
        assertEquals(saved, actual.copy(block = saved.block))
        assertEquals(248, actual.window(null))
    }
    @Test fun invalidTimingIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { AutoBlock.nextBlock(27, Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { AutoBlock.nextBlock(27, -1.0) }
    }
}
