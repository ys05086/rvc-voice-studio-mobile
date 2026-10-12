package com.voicestudio.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class MicPhaseTest {
    @Test fun followsLatestTransitionForPauseAndResume() {
        assertEquals(MicPhase.WAITING, MicPhase.resolve(true,false,null,"BRIDGE_RUNNING\nBRIDGE_IDLE"))
        assertEquals(MicPhase.BUFFERING, MicPhase.resolve(true,false,null,"BRIDGE_IDLE\nBRIDGE_BUFFERING"))
        assertEquals(MicPhase.RUNNING, MicPhase.resolve(true,false,null,"BRIDGE_IDLE\nBRIDGE_BUFFERING\nBRIDGE_RUNNING"))
    }
    @Test fun staysAccurateAfterTransitionLogsRotateOut() {
        assertEquals(MicPhase.RUNNING, MicPhase.resolve(true,false,null,"BRIDGE_STREAM elapsed_s=1200 state=RUNNING"))
        assertEquals(MicPhase.BUFFERING, MicPhase.resolve(true,false,null,"BRIDGE_STREAM state=BUFFERING"))
    }
    @Test fun lifecycleOverridesStaleStreamingLog() {
        assertEquals(MicPhase.OFF, MicPhase.resolve(false,false,null,"BRIDGE_RUNNING"))
        assertEquals(MicPhase.STOPPING, MicPhase.resolve(true,true,null,"BRIDGE_RUNNING"))
        assertEquals(MicPhase.ERROR, MicPhase.resolve(true,false,"disconnected","BRIDGE_RUNNING"))
        assertEquals(MicPhase.PREPARING, MicPhase.resolve(true,false,null,"BRIDGE_CONNECTED"))
    }
}
