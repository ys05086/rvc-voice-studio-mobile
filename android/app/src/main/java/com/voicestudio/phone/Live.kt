package com.voicestudio.phone

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** Realtime settings, phone-wide. Frames are 10 ms. The iPhone's working values: block 18-20, context 100-131,
 *  crossfade 8, cushion 0.06-0.08 s. */
data class LiveParams(
    val block: Int = 20,          // new audio per step: the converted voice comes out in pieces this long
    val context: Int = 100,       // past audio the content and pitch models see with each step
    val crossfade: Int = 8,       // overlap blended between steps
    val search: Int = 1,          // how far SOLA may shift a step to line its waveform up with the last one
    val cushion: Double = 0.06,   // seconds of output kept queued, against uneven compute times
    val thresholdDb: Double = -60.0, // quieter input than this comes out silent
    val gainDb: Double = 12.0,    // output boost; the input follows a quiet, unprocessed microphone
    val skipQuiet: Boolean = false, // skip inference only after the ENTIRE context is below the existing gate
) {
    init { require(context in 0..MAX_CONTEXT) }

    companion object {
        const val MAX_CONTEXT = 300
        // Preserve the old 1.6 s analysis window until the user changes this setting.
        fun defaultContext(block: Int, crossfade: Int = 8, search: Int = 1) =
            max(0, Rvc.REALTIME_WINDOW - block - crossfade - search)
    }

    /** Frames analyzed per step, including history, overlap and the new block. */
    fun window(fixed: Int?): Int {
        var c = if (fixed != null) max(0, fixed - crossfade - search - block) else context
        if ((c + crossfade + search + block) % 2 == 1) c += 1  // HuBERT steps in 20 ms
        return c + crossfade + search + block
    }

    /** Frames decoded each step. */
    val tail get() = crossfade + search + block
}

/**
 * One realtime step at a time, the same for the microphone and for a file: the newest block plus its context in,
 * `block` frames of converted voice out.
 *
 * Each step runs HuBERT, RMVPE and enc over the whole window and decodes only the last block + crossfade + search
 * frames (Rvc.streamWindow). The result is lined up with the previous step by SOLA and cross-faded over `crossfade`
 * frames, as the RVC realtime GUI does. Output follows the input's loudness (rms_mix_rate) and goes quiet under the
 * threshold. A port of the iPhone's LiveEngine.run, without the audio devices.
 */
class LiveCore(private val rvc: Rvc, val params: LiveParams) {
    val window = params.window(rvc.realtimeWindow)
    private val b = params.block
    private val tail = params.tail
    val b48 = b * 480
    val w48 = window * 480
    private val sola = params.crossfade * 480
    private val fadeIn = FloatArray(sola) { i -> sin(0.5 * PI * i / max(sola, 1)).pow(2).toFloat() }
    private val state = StreamState(window, b)
    private val downsampler = Dsp.WindowResampler()
    private var previous: FloatArray? = null
    private var gate = 0f
    private var holdLeft = 0
    private val gateHold = 48000 * 3 / 10
    private val gateAttack = 1f / (48000 * 5 / 1000)
    private val gateRelease = 1f / (48000 * 50 / 1000)
    private val threshold = 10.0.pow(params.thresholdDb / 20).toFloat()
    @Volatile var gain = 10.0.pow(params.gainDb / 20).toFloat()
    /** Steps whose output the soft clip had to bend (too loud). */
    var clips = 0
        private set
    /** The SOLA shift of the last step, in samples. */
    var lastOffset = 0
        private set
    /** The last step's time outside the models: resampling before them, SOLA, loudness and gate after (ms). */
    var lastPrepMs = 0.0
        private set
    var lastPostMs = 0.0
        private set
    /** The last step's input peak (before the output boost) and output peak before the soft clip, and whether the
     *  soft clip had to bend it. */
    var lastInPeak = 0f
        private set
    var lastRawPeak = 0f
        private set
    var lastClipped = false
        private set
    var lastSkippedQuiet = false
        private set

    /** After a skip or an audio restart the past no longer lines up: start the stream over. */
    fun reset() {
        downsampler.reset()
        state.reset()
        previous = null
        gate = 0f
        holdLeft = 0
    }

    /** x48: the newest [w48] samples at 48 kHz (the step's block last). Returns [b48] samples for the earphones. */
    fun step(x48: FloatArray, st: Rvc.Settings, bypass: Boolean = false, allowQuietSkip: Boolean = true): FloatArray {
        require(x48.size == w48)
        val t0 = System.nanoTime()
        lastSkippedQuiet = false
        if (params.skipQuiet && allowQuietSkip && !bypass && isQuietContext(x48, threshold)) {
            reset()
            lastSkippedQuiet = true
            lastInPeak = x48.maxOfOrNull { abs(it) } ?: 0f
            lastRawPeak = 0f; lastClipped = false
            lastPrepMs = (System.nanoTime() - t0) / 1e6; lastPostMs = 0.0
            return FloatArray(b48)
        }
        val x16 = if (bypass) null else fit(downsampler.process(x48,b48), window * 160)
        val t1 = System.nanoTime()
        val y = if (x16 == null) x48.copyOfRange(w48 - tail * 480, w48)
                else rvc.streamWindow(x16, st, tail, b, state)
        val t2 = System.nanoTime()
        lastPrepMs = (t1 - t0) / 1e6
        // line up with the previous step and blend over the crossfade
        val off = previous?.let { Dsp.solaOffset(y, it, params.search * 480) } ?: 0
        lastOffset = off
        val piece = fit(y.copyOfRange(off, min(off + b48 + sola, y.size)), b48 + sola)
        previous?.let { prev -> for (i in 0 until sola) piece[i] = piece[i] * fadeIn[i] + prev[i] * (1 - fadeIn[i]) }
        previous = piece.copyOfRange(b48, b48 + sola)
        val block = piece.copyOf(b48)
        // the matching stretch of input, for loudness and the gate
        val inStart = w48 - tail * 480 + off
        val input = x48.copyOfRange(inStart, inStart + b48)
        if (st.rmsMixRate < 1) {
            val rIn = Dsp.frameRms(input)
            val rOut = Dsp.frameRms(block)
            val power = 1 - st.rmsMixRate
            for (k in 0 until b48) block[k] *= (rIn[k] / max(rOut[k], 1e-3f)).toDouble().pow(power).toFloat()
        }
        // the gate decides per 10 ms: opens within 5 ms of a loud frame, stays open 0.3 s after the last one (so word
        // ends and soft consonants survive), then closes over 50 ms
        var clipped = false
        var inPeak = 0f
        var rawPeak = 0f
        val boost = gain
        for (f in 0 until b) {
            val a = f * 480
            var energy = 0f
            for (k in a until a + 480) energy += input[k] * input[k]
            if (sqrt(energy / 480) >= threshold) holdLeft = gateHold
            for (k in a until a + 480) {
                gate = if (holdLeft > 0) min(1f, gate + gateAttack) else max(0f, gate - gateRelease)
                if (holdLeft > 0) holdLeft--
                val raw = block[k] * gate * boost
                inPeak = max(inPeak, abs(input[k]))
                rawPeak = max(rawPeak, abs(raw))
                if (abs(raw) > 1) clipped = true
                val v = softClip(raw)
                block[k] = if (v.isNaN()) 0f else v
            }
        }
        if (clipped) clips++
        lastInPeak = inPeak
        lastRawPeak = rawPeak
        lastClipped = clipped
        lastPostMs = (System.nanoTime() - t2) / 1e6
        return block
    }

    /** The recorded original for this step: the same stretch without the SOLA shift, so steps join exactly. */
    fun heard(x48: FloatArray): FloatArray = x48.copyOfRange(w48 - tail * 480, w48 - tail * 480 + b48)

    companion object {
        /** Do not discard words merely because the latest block is quiet: inspect all historical context too. */
        fun isQuietContext(x: FloatArray, threshold: Float): Boolean {
            if (x.size < 48000 * 3 / 10 || threshold <= 0 || !threshold.isFinite()) return false
            for (a in x.indices step 480) {
                val end = min(a + 480, x.size)
                var energy = 0.0
                for (k in a until end) energy += x[k].toDouble() * x[k]
                if (!energy.isFinite() || energy >= threshold.toDouble() * threshold * (end - a)) return false
            }
            return true
        }
        /** Above 0.8 the peaks bend smoothly towards 1 instead of clipping. */
        fun softClip(v: Float): Float {
            val a = abs(v)
            if (!(a > 0.8f)) return v
            return (if (v < 0) -1f else 1f) * (0.8f + 0.2f * tanh((a - 0.8f) / 0.2f))
        }

        fun fit(x: FloatArray, count: Int): FloatArray = if (x.size == count) x else x.copyOf(count)
    }
}

/**
 * Realtime without the audio devices: a file goes through [LiveCore] step by step, paced like a microphone (each step
 * starts no earlier than its block would have arrived) or as fast as possible. Reports per-stage time per step and
 * when a phone's earphones would have run dry with the given cushion; writes the converted voice and one CSV line
 * per step.
 */
object StreamBench {
    /** The Kotlin pieces of a realtime step at realtime sizes, timed on the phone (`--ez dspbench true`). */
    fun dsp(log: (String) -> Unit) {
        val rng = java.util.Random(1)
        val x48 = FloatArray(160 * 480) { (rng.nextGaussian() * 0.1).toFloat() }
        val y = FloatArray(50 * 466) { (rng.nextGaussian() * 0.1).toFloat() }
        val f0 = DoubleArray(50) { 200.0 }
        val z = FloatArray(192 * 160) { rng.nextGaussian().toFloat() }
        val head = FloatArray(3840 + 480) { it * 1e-4f }
        val prev = FloatArray(3840) { it * 1e-4f }
        fun time(name: String, f: () -> Unit) {
            repeat(10) { f() }
            val t0 = System.nanoTime()
            repeat(20) { f() }
            log(String.format(java.util.Locale.US, "dsp %-28s %7.2f ms", name, (System.nanoTime() - t0) / 1e6 / 20))
        }
        time("resample 48k->16k window") { Dsp.resample(x48, 48000, 16000) }
        time("resampleRepeated 466->480") { Dsp.resampleRepeated(y, 466, 480) }
        time("nsfSource 50 frames") { Dsp.nsfSource(f0, rng) }
        time("noise 192x160 gaussians") { FloatArray(192 * 160) { rng.nextGaussian().toFloat() } }
        time("stretchLinear 192x50->49") { Dsp.stretchLinear(z.copyOf(192 * 50), 192, 50, 49) }
        time("sola 3840 x 481") { Dsp.solaOffset(head, prev, 480) }
        time("frameRms 12000 x2") { Dsp.frameRms(x48.copyOf(12000)); Dsp.frameRms(x48.copyOf(12000)) }
        time("phone features 160x768 copy") { val p = FloatArray(160 * 768); for (t in 0 until 160) System.arraycopy(z, 0, p, t * 768, 768) }
    }

    class Result(val output: FloatArray, val csv: String, val summary: String)

    fun run(rvc: Rvc, params: LiveParams, settings: Rvc.Settings, x48: FloatArray, paced: Boolean,
            seconds: Double, warmUp: Int = 3, log: (String) -> Unit = {}): Result {
        val core = LiveCore(rvc, params)
        // the first runs of a new shape are slow (graph preparation): a few silent steps first, as the iPhone does
        repeat(warmUp) { i ->
            val t0 = System.nanoTime()
            core.step(FloatArray(core.w48), settings)
            log(String.format(java.util.Locale.US, "warm-up %d: %.3f s", i + 1, (System.nanoTime() - t0) / 1e9))
        }
        core.reset()
        val total = (seconds * 48000).toInt() / core.b48 * core.b48  // loops the file to fill `seconds`
        val out = FloatArray(total)
        val csv = StringBuilder("step,start_ms,compute_ms,hubert_ms,rmvpe_ms,enc_ms,dec_ms,other_ms,core_other_ms,late_ms,sola\n")
        val steps = total / core.b48
        val took = DoubleArray(steps)
        val stage = Array(5) { DoubleArray(steps) }
        val blockS = params.block / 100.0
        val begun = System.nanoTime()
        var playStart = -1.0
        var underruns = 0
        var lateSum = 0.0
        val window = FloatArray(core.w48)
        for (k in 0 until steps) {
            val end = (k + 1) * core.b48  // the newest sample this step sees
            if (paced) {
                val due = begun + (end / 48000.0 * 1e9).toLong()
                val wait = due - System.nanoTime()
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
            }
            for (i in 0 until core.w48) {
                val pos = end - core.w48 + i
                window[i] = if (pos >= 0) x48[pos % x48.size] else 0f
            }
            val before = rvc.timing.copyOf()
            val t0 = System.nanoTime()
            val y = core.step(window, settings)
            val t1 = System.nanoTime()
            System.arraycopy(y, 0, out, k * core.b48, core.b48)
            took[k] = (t1 - t0) / 1e9
            for (s in 0 until 5) stage[s][k] = rvc.timing[s] - before[s]
            // the earphones: playback starts one cushion after the first block is ready, then needs a block every
            // block length; a block finished after its turn is an underrun (paced runs only)
            val doneAt = (t1 - begun) / 1e9
            if (playStart < 0) playStart = doneAt + params.cushion
            val late = if (paced) doneAt - (playStart + k * blockS) else 0.0
            if (late > 0) { underruns++; lateSum += late; playStart += late }  // the queue restarts after a gap
            val modelled = stage.sumOf { it[k] }
            csv.append(String.format(java.util.Locale.US, "%d,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%d\n", k,
                (t0 - begun) / 1e6, took[k] * 1000, stage[0][k] * 1000, stage[1][k] * 1000, stage[2][k] * 1000,
                stage[3][k] * 1000, stage[4][k] * 1000, (took[k] - modelled) * 1000, max(0.0, late) * 1000, core.lastOffset))
        }
        fun pct(a: DoubleArray, p: Double) = a.sorted()[min(a.size - 1, (a.size * p).toInt())]
        fun ms(a: DoubleArray) = String.format(java.util.Locale.US, "%.0f/%.0f/%.0f", a.average() * 1000, pct(a, 0.95) * 1000, a.max() * 1000)
        val summary = String.format(java.util.Locale.US,
            "stream %s block %d window %d (context %d) tail %d dec frames %d: %d steps, compute mean/p95/max %s ms for a %.0f ms block (load %.0f%%); " +
                "hubert %s, rmvpe %s, enc %s, dec %s, other %s; underruns %d (%.2f s) with a %.0f ms cushion%s",
            if (paced) "paced" else "flat-out", params.block, core.window, core.window - params.tail, params.tail, rvc.decFrames,
            steps, ms(took), blockS * 1000, took.average() / blockS * 100,
            ms(stage[0]), ms(stage[1]), ms(stage[2]), ms(stage[3]), ms(stage[4]), underruns, lateSum, params.cushion * 1000,
            if (core.clips > 0) "; soft-clipped steps ${core.clips}" else "")
        return Result(out, csv.toString(), summary)
    }
}
