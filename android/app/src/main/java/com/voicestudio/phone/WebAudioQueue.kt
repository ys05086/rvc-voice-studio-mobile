package com.voicestudio.phone

/** Converted PCM only. A bounded queue keeps a stalled browser from replaying old conversation. */
internal class WebAudioQueue(private val capacity: Int = 28800) {
    private val samples = FloatArray(capacity)
    private var start = 0
    private var size = 0
    private var enabled = false

    @Synchronized fun enable() { start = 0; size = 0; enabled = true }
    @Synchronized fun disable() { enabled = false; start = 0; size = 0 }
    @Synchronized fun offer(block: FloatArray) {
        if (!enabled) return
        for (sample in block) {
            if (size == capacity) { start = (start + 1) % capacity; size-- }
            samples[(start + size) % capacity] = if (sample.isFinite()) sample.coerceIn(-1f, 1f) else 0f
            size++
        }
    }
    @Synchronized fun take(max: Int = 24000): FloatArray {
        val count = minOf(max, size)
        val result = FloatArray(count) { samples[(start + it) % capacity] }
        start = (start + count) % capacity
        size -= count
        return result
    }
}
