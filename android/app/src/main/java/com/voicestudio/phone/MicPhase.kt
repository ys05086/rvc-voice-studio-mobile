package com.voicestudio.phone

enum class MicPhase { OFF, PREPARING, WAITING, BUFFERING, RUNNING, STOPPING, ERROR;
    companion object {
        fun resolve(active: Boolean, stopping: Boolean, error: String?, log: String): MicPhase {
            if (stopping && active) return STOPPING
            if (error != null) return ERROR
            if (!active) return OFF
            // STREAM also carries state: long sessions may rotate the original transition out.
            val state = log.lineSequence().lastOrNull {
                "BRIDGE_IDLE" in it || "READY_WAIT_TARGET" in it || "BRIDGE_BUFFERING" in it ||
                    "BRIDGE_RUNNING" in it || "BRIDGE_STREAM" in it
            }.orEmpty()
            return when {
                "BRIDGE_IDLE" in state || "READY_WAIT_TARGET" in state -> WAITING
                "BRIDGE_BUFFERING" in state || "state=BUFFERING" in state -> BUFFERING
                "BRIDGE_RUNNING" in state || "BRIDGE_STREAM" in state -> RUNNING
                else -> PREPARING
            }
        }
    }
}
