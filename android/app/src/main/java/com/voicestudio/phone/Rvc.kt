package com.voicestudio.phone

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * File conversion with the exported ONNX models: a line-by-line port of phone-vc/pipeline.py (the
 * reference checked against the studio). 48 kHz mono in, 48 kHz mono out.
 *
 *   16 kHz audio -> HuBERT features (50 fps, repeated to 100 fps) and RMVPE pitch (100 fps)
 *   -> pitch shaping (unvoiced fill, range guards, intonation stretch, key shift) -> coarse pitch
 *   -> enc -> latent z, stretched by the formant factor -> dec, driven by the NSF excitation built here
 *   -> 48 kHz audio, resampled back when the formant moved -> the input's loudness envelope.
 * Long audio goes in 15 s chunks with 1 s of context each side, cross-faded over 20 ms.
 */
class Rvc(commonDir: File, private val modelDir: File, private val threads: Int = 4, private val ep: String = "cpu",
          decFile: String = "model.dec.onnx", private val front: String = "cpu",
          /** NPU windows hold this much new audio (16 kHz samples, a multiple of 320) plus context and fade. */
          private val frontChunk: Int = 3 * SR16) : AutoCloseable {
    data class Settings(
        val pitch: Double = 0.0, val formant: Double = 0.0,
        val lowRatio: Double = 1.0, val lowKnee: Double = 120.0,
        val highRatio: Double = 1.0, val highKnee: Double = 260.0,
        val unvoicedKeep: Boolean = true,
        val expand: Boolean = false, val expandRatio: Double = 1.15,
        val rmsMixRate: Double = 0.0,
    )

    private val env = OrtEnvironment.getEnvironment()
    private fun options(accelerate: Boolean) = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setIntraOpNumThreads(threads)
        if (accelerate) when (ep) {
            "xnnpack" -> addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
            "nnapi" -> addNnapi()
            "nnapi16" -> addNnapi(java.util.EnumSet.of(ai.onnxruntime.providers.NNAPIFlags.USE_FP16))
            else -> qnnOptions(ep)?.let { addQnn(it) }
        }
    }
    private val options = options(false)
    /** "ncnn_gpu": the decoder on the GPU through Vulkan in 32 bits (model.dec.ncnn.*, made by make_ncnn.py);
     *  null when the files are missing or the GPU cannot start, and the decoder falls back to the CPU. */
    private val ncnn: NcnnDecoder? = if (ep != "ncnn_gpu") null else runCatching {
        NcnnDecoder(File(modelDir, "model.dec.ncnn.param"), File(modelDir, "model.dec.ncnn.bin"), true, threads)
            .takeIf { it.usesGpu } ?: throw IllegalStateException("no Vulkan GPU")
    }.getOrNull()
    /** Where the decoder runs, for the result line: "GPU", "NPU" or "CPU". */
    val decoderDevice = when { ncnn != null -> "GPU"; qnnOptions(ep) != null -> "NPU"; else -> "CPU" }
    /** QNN and the ncnn graph need fixed shapes: the decoder then runs in DEC_FRAMES pieces (see [decode]). */
    private val decFixed = qnnOptions(ep) != null || ncnn != null
    private val decOptions = options(true).apply {
        if (decFixed) {
            setSymbolicDimensionValue("n", DEC_FRAMES.toLong())
            setSymbolicDimensionValue("samples", DEC_FRAMES * 480L)
        }
    }
    /** Seconds spent per stage since the engine was made: hubert, rmvpe, enc, dec, other. */
    val timing = DoubleArray(5)
    /** HuBERT, RMVPE and enc on the NPU ("qnn_htp"): fp16 is fine for features, unlike for the decoder that makes
     *  the waveform. QNN needs fixed shapes, so every window is exactly [window] samples (see [convert]). */
    private val frontFixed = qnnOptions(front) != null
    private fun frontSession(src: File, cache: File, dims: Map<String, Long>): OrtSession {
        if (!frontFixed) return env.createSession(src.path, options)
        cache.takeIf { it.isFile }?.let { c ->
            runCatching { return env.createSession(c.path, frontOptions(dims, null)) }.onFailure { c.delete() }
        }
        return env.createSession(src.path, frontOptions(dims, cache))
    }
    private fun frontOptions(dims: Map<String, Long>, saveTo: File?) = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setIntraOpNumThreads(threads)
        qnnOptions(front)?.let { addQnn(it) }
        for ((name, value) in dims) setSymbolicDimensionValue(name, value)
        if (saveTo != null) {  // the NPU's compiled graph, so later starts skip the long preparation
            addConfigEntry("ep.context_enable", "1")
            addConfigEntry("ep.context_embed_mode", "1")
            addConfigEntry("ep.context_file_path", saveTo.path)
        }
    }
    /** The NPU's fixed window at 16 kHz: a chunk, the cross-fade and a second of context each side. */
    val window = frontChunk + 320 + 2 * 16000
    private val frames = window / 160
    private val hubert = frontSession(File(commonDir, "hubert.onnx"), File(commonDir, "hubert.$front.$window.onnx"), mapOf("samples" to window.toLong()))
    private val rmvpe = frontSession(File(commonDir, "rmvpe.onnx"), File(commonDir, "rmvpe.$front.$window.onnx"), mapOf("samples" to window.toLong()))
    private val enc = frontSession(File(modelDir, "model.enc.onnx"), File(modelDir, "model.enc.$front.$frames.onnx"), mapOf("T" to frames.toLong()))
    /** The NPU's compiled decoder, saved after the first preparation (~12 s) and loaded directly after that. */
    val compiledDecoder = File(modelDir, "model.dec.$ep.onnx")
    private val dec: OrtSession? = if (ncnn != null) null else if (qnnOptions(ep) == null) env.createSession(File(modelDir, decFile).path, decOptions) else
        compiledDecoder.takeIf { it.isFile }?.let { cached ->
            runCatching { env.createSession(cached.path, options(true)) }.getOrElse { cached.delete(); null }
        } ?: run {
            decOptions.addConfigEntry("ep.context_enable", "1")
            decOptions.addConfigEntry("ep.context_embed_mode", "1")
            decOptions.addConfigEntry("ep.context_file_path", compiledDecoder.path)
            env.createSession(File(modelDir, decFile).path, decOptions)
        }
    private val rng = Random(0)

    override fun close() {
        listOfNotNull(hubert, rmvpe, enc, dec).forEach { it.close() }
        ncnn?.close()
        options.close(); decOptions.close()
    }

    /** progress(done, total) is called after each chunk. */
    fun convert(x48: FloatArray, st: Settings, progress: (Int, Int) -> Unit = { _, _ -> }): FloatArray {
        var x16 = Dsp.resample(x48, SR, SR16)
        x16 = x16.copyOf(x16.size + Math.floorMod(-x16.size, UNIT))
        val chunk = if (frontFixed) frontChunk else (CHUNK_S * SR16).toInt() / UNIT * UNIT
        val ctx = (CONTEXT_S * SR16).toInt() / UNIT * UNIT
        val fade = (FADE_S * SR16).toInt() / UNIT * UNIT
        val out = FloatArray(x16.size * 3)
        val weight = FloatArray(out.size)
        val starts = (0 until x16.size step chunk).toList()
        for ((i, start) in starts.withIndex()) {
            val coreEnd = min(start + chunk + fade, x16.size)
            var lo = max(0, start - ctx)
            var hi = min(x16.size, coreEnd + ctx)
            val win = if (!frontFixed) x16.copyOfRange(lo, hi) else {
                // fixed-size windows for the NPU: fill with real audio by reaching further back, pad only short clips
                lo = max(0, hi - window)
                hi = min(x16.size, lo + window)
                x16.copyOfRange(lo, hi).copyOf(window)
            }
            val y = window(win, st)
            val a = (start - lo) * 3
            val n = (coreEnd - lo) * 3 - a
            val f = fade * 3
            for (k in 0 until n) {
                var w = 1f
                if (start > 0 && k < f) w = k.toFloat() / (f - 1)
                if (coreEnd < x16.size && k >= n - f) w = min(w, (n - 1 - k).toFloat() / (f - 1))
                out[start * 3 + k] += y[a + k] * w
                weight[start * 3 + k] += w
            }
            progress(i + 1, starts.size)
        }
        val y = FloatArray(x48.size) { if (it < out.size) out[it] / max(weight[it], 1e-6f) else 0f }
        if (st.rmsMixRate < 1) {  // follow the input's loudness envelope, as the studio does
            val rIn = Dsp.frameRms(x48)
            val rOut = Dsp.frameRms(y)
            val power = 1 - st.rmsMixRate
            for (k in y.indices) y[k] *= (rIn[k] / max(rOut[k], 1e-3f)).toDouble().pow(power).toFloat()
        }
        for (k in y.indices) y[k] = y[k].coerceIn(-1f, 1f)
        return y
    }

    /** One window of 16 kHz audio (a multiple of 320 samples) -> 48 kHz audio of the same duration. */
    private fun window(x16: FloatArray, st: Settings): FloatArray {
        val pLen = x16.size / 160
        var t0 = System.nanoTime()
        fun lap(stage: Int) { val t = System.nanoTime(); timing[stage] += (t - t0) / 1e9; t0 = t }
        // content: 50 fps, last frame repeated, each frame doubled to 100 fps
        val feats50 = run(hubert, "audio", x16, longArrayOf(1, x16.size.toLong()))
        lap(0)
        val f50 = feats50.size / 768
        val phone = FloatArray(pLen * 768)
        for (t in 0 until pLen) {
            val src = min(t / 2, f50)  // index f50 is the appended copy of the last frame
            System.arraycopy(feats50, min(src, f50 - 1) * 768, phone, t * 768, 768)
        }
        // pitch
        val salience = run(rmvpe, "audio", x16, longArrayOf(1, x16.size.toLong()))
        lap(1)
        var f0 = Dsp.decodeF0(salience, salience.size / 360).copyOf(pLen)
        f0 = Dsp.fillUnvoiced(f0, if (st.unvoicedKeep) UNVOICED_FILL_FRAMES else null)
        f0 = Dsp.shapePitch(f0, st.lowKnee, st.lowRatio, st.highKnee, st.highRatio,
            INTONATION_CENTER_HZ, if (st.expand) st.expandRatio else 1.0)
        val key = 2.0.pow((st.pitch - st.formant) / 12)  // the formant moves by time stretch; pitch compensates
        for (t in f0.indices) f0[t] *= key
        val coarse = LongArray(pLen) { Dsp.coarse(f0[it]) }
        // synthesis
        val factor = 2.0.pow(st.formant / 12)
        val n = ceil(pLen * factor).toInt()
        val noise = FloatArray(192 * pLen) { rng.nextGaussian().toFloat() }
        lap(4)
        val z = OnnxTensor.createTensor(env, FloatBuffer.wrap(phone), longArrayOf(1, pLen.toLong(), 768)).use { ph ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(coarse), longArrayOf(1, pLen.toLong())).use { pi ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(noise), longArrayOf(1, 192, pLen.toLong())).use { no ->
                    enc.run(mapOf("phone" to ph, "pitch" to pi, "noise" to no)).use { floats(it[0] as OnnxTensor) }
                }
            }
        }
        lap(2)
        val zn = Dsp.stretchLinear(z, 192, pLen, n)
        val f0n = Dsp.stretchNearest(f0, n)  // no n/pLen factor: the excitation is built at n frames directly
        val sine = Dsp.nsfSource(f0n, rng)
        lap(4)
        var audio = decode(zn, n, sine)
        lap(3)
        val upp = floor(factor * 480).toInt()
        if (upp != 480) audio = Dsp.resample(audio.copyOf(min(audio.size, pLen * upp)), upp, 480)
        return audio.copyOf(pLen * 480).also { lap(4) }
    }

    private fun runDec(z: FloatArray, frames: Int, sine: FloatArray): FloatArray {
        ncnn?.let { return it.decode(z, frames, sine) }
        val session = dec ?: throw IllegalStateException("디코더가 없어요")
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(z), longArrayOf(1, 192, frames.toLong())).use { zt ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(sine), longArrayOf(1, sine.size.toLong(), 1)).use { st ->
                session.run(mapOf("z" to zt, "sine" to st)).use { floats(it[0] as OnnxTensor) }
            }
        }
    }

    /** The decoder over n frames: in one go, or for fixed-shape runtimes in DEC_FRAMES windows that keep
     *  DEC_CONTEXT frames of context each side. The decoder is local, so the pieces join exactly (checked on
     *  the PC: frames away from the clip's two ends match a one-shot decode at >100 dB). */
    private fun decode(z: FloatArray, n: Int, sine: FloatArray): FloatArray {
        if (!decFixed) return runDec(z, n, sine)
        val hop = DEC_FRAMES - 2 * DEC_CONTEXT
        val out = FloatArray(n * 480)
        var start = 0
        while (start < n) {
            val lo = start - DEC_CONTEXT
            val zz = FloatArray(192 * DEC_FRAMES)
            val ss = FloatArray(DEC_FRAMES * 480)
            val a = max(lo, 0)
            val b = min(lo + DEC_FRAMES, n)
            for (c in 0 until 192) System.arraycopy(z, c * n + a, zz, c * DEC_FRAMES + (a - lo), b - a)
            System.arraycopy(sine, a * 480, ss, (a - lo) * 480, (b - a) * 480)
            val y = runDec(zz, DEC_FRAMES, ss)
            val take = min(hop, n - start)
            System.arraycopy(y, DEC_CONTEXT * 480, out, start * 480, take * 480)
            start += hop
        }
        return out
    }

    private fun run(session: OrtSession, input: String, data: FloatArray, shape: LongArray): FloatArray =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { t ->
            session.run(mapOf(input to t)).use { floats(it[0] as OnnxTensor) }
        }

    private fun floats(t: OnnxTensor): FloatArray {
        val buffer = t.floatBuffer
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    companion object {
        /** QNN (Snapdragon): "qnn_htp" the NPU in fp16, "qnn_gpu" the Adreno GPU. Needs fixed input shapes. */
        fun qnnOptions(ep: String): Map<String, String>? = when (ep) {
            // graph optimization 0 (default): ~12 s to prepare, 0.43 s per 2 s piece on the S23+; level 3 is
            // only 7% faster (0.40 s) but takes over 4 minutes to prepare
            "qnn_htp", "qnn_htp3" -> mapOf("backend_path" to "libQnnHtp.so", "htp_performance_mode" to "burst",
                "htp_arch" to "73", "enable_htp_fp16_precision" to "1",
                "htp_graph_finalization_optimization_mode" to if (ep == "qnn_htp3") "3" else "0")
            "qnn_gpu" -> mapOf("backend_path" to "libQnnGpu.so")
            else -> null
        }

        /** Times a fixed-shape decoder file on the given execution provider: (first run, mean of the rest) in s. */
        fun benchDecoder(path: String, ep: String, threads: Int, runs: Int = 5): Pair<Double, Double> {
            val env = OrtEnvironment.getEnvironment()
            OrtSession.SessionOptions().use { o ->
                o.setIntraOpNumThreads(threads)
                qnnOptions(ep)?.let { o.addQnn(it) }
                val made = System.nanoTime()
                env.createSession(path, o).use { s ->
                    android.util.Log.i("VoiceStudio", String.format(java.util.Locale.US, "bench %s: session ready in %.1f s", ep, (System.nanoTime() - made) / 1e9))
                    val zShape = (s.inputInfo["z"]!!.info as ai.onnxruntime.TensorInfo).shape
                    val sShape = (s.inputInfo["sine"]!!.info as ai.onnxruntime.TensorInfo).shape
                    val rng = Random(0)
                    val z = FloatArray((zShape[1] * zShape[2]).toInt()) { rng.nextGaussian().toFloat() }
                    val sine = FloatArray(sShape[1].toInt()) { (rng.nextGaussian() * 0.1).toFloat() }
                    val times = ArrayList<Double>()
                    repeat(runs) {
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(z), zShape).use { zt ->
                            OnnxTensor.createTensor(env, FloatBuffer.wrap(sine), sShape).use { st ->
                                val t0 = System.nanoTime()
                                s.run(mapOf("z" to zt, "sine" to st)).close()
                                times.add((System.nanoTime() - t0) / 1e9)
                            }
                        }
                    }
                    return times[0] to times.drop(1).average()
                }
            }
        }

        const val DEC_FRAMES = 200  // 2 s pieces for fixed-shape runtimes (QNN)
        const val DEC_CONTEXT = 16  // 160 ms of context each side
        const val SR = 48000
        const val SR16 = 16000
        const val UNIT = 320  // one HuBERT frame at 16 kHz
        const val CHUNK_S = 15.0
        const val CONTEXT_S = 1.0
        const val FADE_S = 0.02
        const val UNVOICED_FILL_FRAMES = 4
        const val INTONATION_CENTER_HZ = 120.0
    }
}

/** The numeric pieces of the pipeline, kept free of Android types so they can be tested on the PC. */
object Dsp {
    private const val F0_MIN = 50.0
    private const val F0_MAX = 1100.0
    private val F0_MEL_MIN = 1127 * ln(1 + F0_MIN / 700)
    private val F0_MEL_MAX = 1127 * ln(1 + F0_MAX / 700)
    private val CENTS = DoubleArray(368) { i -> if (i < 4 || i >= 364) 0.0 else 20.0 * (i - 4) + 1997.3794084376191 }

    /** RMVPE salience [frames * 360] -> Hz: weighted average of the 9 bins around the peak. */
    fun decodeF0(salience: FloatArray, frames: Int, thred: Double = 0.03): DoubleArray = DoubleArray(frames) { t ->
        var best = 0
        var peak = Float.NEGATIVE_INFINITY
        for (b in 0 until 360) {
            val v = salience[t * 360 + b]
            if (v > peak) { peak = v; best = b }
        }
        if (peak <= thred) return@DoubleArray 0.0
        var num = 0.0
        var den = 0.0
        for (k in -4..4) {
            val b = best + k
            val s = if (b in 0 until 360) salience[t * 360 + b].toDouble() else 0.0
            num += s * CENTS[b + 4]
            den += s
        }
        val f0 = 10 * 2.0.pow(num / den / 1200)
        if (f0 == 10.0) 0.0 else f0
    }

    fun fillUnvoiced(f0: DoubleArray, maxGap: Int?): DoubleArray {
        val voiced = f0.indices.filter { f0[it] > 0 }
        if (voiced.isEmpty() || voiced.size == f0.size) return f0
        val out = f0.copyOf()
        var t = 0
        while (t < f0.size) {
            if (f0[t] > 0) { t++; continue }
            var end = t
            while (end < f0.size && f0[end] <= 0) end++
            if (maxGap == null || end - t <= maxGap) {
                // np.interp: linear between the voiced neighbours, held flat past either end
                val left = (t - 1).takeIf { it >= 0 }
                val right = end.takeIf { it < f0.size }
                for (k in t until end) {
                    out[k] = when {
                        left == null -> f0[right!!]
                        right == null -> f0[left]
                        else -> f0[left] + (f0[right] - f0[left]) * (k - left).toDouble() / (right - left)
                    }
                }
            }
            t = end
        }
        return out
    }

    fun shapePitch(f0: DoubleArray, lowKnee: Double, lowRatio: Double, highKnee: Double, highRatio: Double,
                   center: Double, expand: Double): DoubleArray = DoubleArray(f0.size) { i ->
        var v = f0[i]
        if (expand != 1.0 && center > 0 && v > center) v = center * 2.0.pow(log2(v / center) * expand)
        if (lowRatio > 1 && lowKnee > 0 && v > 0 && v < lowKnee) v = lowKnee * 2.0.pow(log2(v / lowKnee) / lowRatio)
        if (highRatio > 1 && highKnee > 0 && v > highKnee) v = highKnee * 2.0.pow(log2(v / highKnee) / highRatio)
        v
    }

    private fun log2(x: Double) = ln(x) / ln(2.0)

    fun coarse(f0: Double): Long {
        var mel = 1127 * ln(1 + f0 / 700)
        if (mel > 0) mel = (mel - F0_MEL_MIN) * 254 / (F0_MEL_MAX - F0_MEL_MIN) + 1
        return mel.coerceIn(1.0, 255.0).roundToInt().toLong()
    }

    /** SineGen's excitation with a float64 phase: a sine at f0 where voiced plus a little noise. */
    fun nsfSource(f0: DoubleArray, rng: Random, upp: Int = 480, sineAmp: Double = 0.1, noiseStd: Double = 0.003): FloatArray {
        val out = FloatArray(f0.size * upp)
        var phase = 0.0
        var i = 0
        for (f in f0) {
            val step = f / 48000.0
            for (k in 0 until upp) {
                phase += step
                phase -= floor(phase)
                val noise = rng.nextGaussian()
                out[i++] = if (f > 0) (sineAmp * sin(2 * PI * phase) + noiseStd * noise).toFloat()
                           else (sineAmp / 3 * noise).toFloat()
            }
        }
        return out
    }

    /** [channels, t] -> [channels, n] as F.interpolate(mode="linear", align_corners=False). */
    fun stretchLinear(x: FloatArray, channels: Int, t: Int, n: Int): FloatArray {
        if (n == t) return x
        val out = FloatArray(channels * n)
        for (i in 0 until n) {
            val pos = ((i + 0.5) * t / n - 0.5).coerceIn(0.0, (t - 1).toDouble())
            val lo = floor(pos).toInt()
            val hi = min(lo + 1, t - 1)
            val w = (pos - lo).toFloat()
            for (c in 0 until channels) out[c * n + i] = x[c * t + lo] * (1 - w) + x[c * t + hi] * w
        }
        return out
    }

    fun stretchNearest(x: DoubleArray, n: Int): DoubleArray =
        DoubleArray(n) { i -> x[min((i.toLong() * x.size / n).toInt(), x.size - 1)] }

    /** librosa.feature.rms(frame 1920, hop 480, centered), interpolated to one value per sample. */
    fun frameRms(x: FloatArray, frame: Int = 1920, hop: Int = 480): FloatArray {
        val half = frame / 2
        val count = 1 + x.size / hop
        val rms = DoubleArray(count) { j ->
            var sum = 0.0
            val start = j * hop - half
            for (k in start until start + frame) if (k in x.indices) sum += x[k].toDouble() * x[k]
            sqrt(sum / frame)
        }
        val out = FloatArray(x.size)
        val scale = if (x.size > 1) (count - 1).toDouble() / (x.size - 1) else 0.0
        for (k in x.indices) {
            val pos = k * scale
            val lo = floor(pos).toInt()
            val hi = min(lo + 1, count - 1)
            out[k] = (rms[lo] + (rms[hi] - rms[lo]) * (pos - lo)).toFloat()
        }
        return out
    }

    /** Windowed-sinc (Kaiser) resampling between any two rates, low-passed below the lower Nyquist. The kernel
     *  is tabulated once per rate pair and read with linear interpolation. */
    fun resample(x: FloatArray, src: Int, dst: Int, zeros: Int = 24): FloatArray {
        if (src == dst || x.isEmpty()) return x
        val ratio = dst.toDouble() / src
        val n = floor(x.size * ratio).toInt()
        val cutoff = min(1.0, ratio) * 0.95
        val width = zeros / cutoff  // half width in input samples
        val table = kernel(cutoff, width)
        val steps = (table.size - 1) / width
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i / ratio
            var acc = 0.0
            for (j in max(ceil(t - width).toInt(), 0)..min(floor(t + width).toInt(), x.size - 1)) {
                val pos = abs(t - j) * steps
                val k = pos.toInt()
                val w = if (k + 1 < table.size) table[k] + (table[k + 1] - table[k]) * (pos - k) else table[table.size - 1]
                acc += x[j] * w
            }
            out[i] = acc.toFloat()
        }
        return out
    }

    private val kernels = HashMap<Pair<Double, Double>, DoubleArray>()

    @Synchronized
    private fun kernel(cutoff: Double, width: Double): DoubleArray = kernels.getOrPut(cutoff to width) {
        val beta = 8.0
        val norm = bessel0(beta)
        val size = 16384
        DoubleArray(size + 1) { k ->
            val d = k.toDouble() / size * width
            val u = d / width
            val arg = PI * cutoff * d
            val sinc = if (abs(arg) < 1e-12) 1.0 else sin(arg) / arg
            cutoff * sinc * bessel0(beta * sqrt(max(0.0, 1 - u * u))) / norm
        }
    }

    private fun bessel0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        val q = x * x / 4
        for (k in 1..30) {
            term *= q / (k * k)
            sum += term
            if (term < 1e-12 * sum) break
        }
        return sum
    }
}
