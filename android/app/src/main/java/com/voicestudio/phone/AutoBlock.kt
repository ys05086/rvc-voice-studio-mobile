package com.voicestudio.phone

import kotlin.math.ceil
import kotlin.math.PI
import kotlin.math.sin

/** Startup calibration only. Saved block/context values are never overwritten. */
object AutoBlock {
    const val MAX_BLOCK = 50
    private const val TARGET_LOAD = 0.80

    /** Re-measure every new shape: a larger block also costs more to convert. */
    fun nextBlock(block: Int, measuredMs: Double): Int? {
        require(block in 15..MAX_BLOCK && measuredMs.isFinite() && measuredMs >= 0)
        if (measuredMs <= block * 10 * TARGET_LOAD) return block
        if (block == MAX_BLOCK) return null
        val estimated = ceil(measuredMs / (10 * TARGET_LOAD)).toInt()
        return maxOf(block + 10, estimated).coerceAtMost(MAX_BLOCK)
    }

    data class Timing(val maximumMs: Double, val steadyMs: Double)
    fun measure(core: LiveCore, settings: Rvc.Settings, checkRunning: () -> Unit = {}): Timing {
        val times = DoubleArray(4)
        // Exercise voiced pitch/decoder work without recording the user or playing test audio.
        val input = FloatArray(core.w48) { i ->
            val t = i / 48000.0
            val phase = 2 * PI * (150 * t + 12 * t * t)
            ((0.045 * sin(phase) + 0.015 * sin(2 * phase)) * (0.7 + 0.3 * sin(2 * PI * 3 * t))).toFloat()
        }
        repeat(times.size) { i ->
            checkRunning()
            val start = System.nanoTime()
            // Never let quiet skipping bypass the measurement.
            core.step(input, settings, allowQuietSkip = false)
            times[i] = (System.nanoTime() - start) / 1e6
        }
        core.reset()
        return Timing(times.max(), times.drop(1).max())
    }
}
