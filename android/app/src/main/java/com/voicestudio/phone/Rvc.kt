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
          private val frontChunk: Int = 3 * SR16, seed: Long = 0,
          /** Realtime: the shapes one step needs (see [streamWindow]); null for file conversion. */
          private val live: LiveShape? = null,
          /** The NPU's fixed windows even on the CPU: a control for comparing the two with everything else equal. */
          cpuFixedWindows: Boolean = false,
          /** Diagnosis: ORT profiles of the NPU sessions (which device ran each node) go here as <model>_*.json. */
          private val profileDir: File? = null,
          private val allowFallback: Boolean = false) : AutoCloseable {
    /** A realtime step: [block] new frames, the last [tail] frames of the window decoded (block + crossfade + search). */
    data class LiveShape(val block: Int, val tail: Int, val fixedFormant: Double? = null) {
        init { require(fixedFormant == null || (fixedFormant.isFinite() && fixedFormant in -2.0..2.0)) }
        val decoderFrames: Int get() = ceil((tail + LIVE_DEC_CONTEXT) *
            (fixedFormant?.let { 2.0.pow(it / 12) } ?: MAX_FORMANT_FACTOR)).toInt()
    }

    data class Settings(
        val pitch: Double = 0.0, val formant: Double = 0.0,
        val lowRatio: Double = 1.0, val lowKnee: Double = 120.0,
        val highRatio: Double = 1.0, val highKnee: Double = 260.0,
        val unvoicedKeep: Boolean = true,
        val expand: Boolean = false, val expandRatio: Double = 1.15,
        val rmsMixRate: Double = 0.0,
    )

    private val env = OrtEnvironment.getEnvironment()
    private val owned = ArrayList<AutoCloseable>()
    private val notices = java.util.concurrent.CopyOnWriteArrayList<String>()
    val fallbackNotice: String? get() = notices.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    private fun note(stage: String, failure: Throwable) {
        notices.add(str(R.string.ui_switched_to_cpu, stage))
        android.util.Log.w("VoiceStudio", "$stage accelerator unavailable; using CPU", failure)
    }
    private fun <T : AutoCloseable> own(value: T): T = value.also { owned.add(it) }
    private val frontFixed = qnnOptions(front) != null
    // CPU fallback keeps bounded windows, including during file conversion on low-memory devices.
    private val fixedWindows = frontFixed || cpuFixedWindows || allowFallback
    private val split = ep == "split" && File(modelDir, "model.dec_a.onnx").isFile && File(modelDir, "model.dec_b.onnx").isFile
    private val decFixed = qnnOptions(ep) != null || ep == "ncnn_gpu" || split || allowFallback
    val decFrames = live?.decoderFrames ?: DEC_FRAMES
    val timing = DoubleArray(5)
    val window = frontChunk + 320 + 2 * 16000
    val realtimeWindow: Int? = if (live != null && fixedWindows) REALTIME_WINDOW else null
    private val frames = realtimeWindow ?: (window / 160)
    private val hubertSamples = realtimeWindow?.let { it * 160 } ?: window
    private val rmvpeSamples = if (live != null) StreamState.rmvpeSamples(live.block) else window
    private val decoderDims get() = if (decFixed) mapOf("n" to decFrames.toLong(), "samples" to decFrames * 480L) else emptyMap()

    private inner class ModelSession(val name: String, src: File, cpuSrc: File, val backend: String,
                                     private val cache: File?, private val dims: Map<String, Long>,
                                     private val allowCpuPartition: Boolean = false) : AutoCloseable {
        private val accelerated = backend != "cpu"
        private fun load(file: File, device: String, cached: Boolean = false): OrtSession =
            OrtSession.SessionOptions().use { options ->
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                options.setIntraOpNumThreads(threads)
                dims.forEach { (key, value) -> options.setSymbolicDimensionValue(key, value) }
                when (device) {
                    "xnnpack" -> options.addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                    "nnapi" -> options.addNnapi()
                    "nnapi16" -> options.addNnapi(java.util.EnumSet.of(ai.onnxruntime.providers.NNAPIFlags.USE_FP16))
                    else -> qnnOptions(device)?.let {
                        options.addQnn(it)
                        // RMVPE intentionally runs its GRU on CPU; keep its supported QNN partitions.
                        // Other stages must fail explicitly rather than silently claiming full NPU execution.
                        if (allowFallback && !allowCpuPartition) options.addConfigEntry("session.disable_cpu_ep_fallback", "1")
                        if (!cached && cache != null) {
                            options.addConfigEntry("ep.context_enable", "1")
                            options.addConfigEntry("ep.context_embed_mode", "1")
                            options.addConfigEntry("ep.context_file_path", cache.path)
                        }
                    }
                }
                profileDir?.let { options.enableProfiling(File(it, "$name-$device").path) }
                env.createSession(file.path, options)
            }
        private val resource = BackendFallback(
            primary = {
                if (cache?.isFile == true) {
                    try { load(cache, backend, true) } catch (failure: Throwable) {
                        if (!BackendFallback.recoverable(failure)) throw failure
                        cache.delete()
                        load(src, backend)
                    }
                } else load(src, backend)
            },
            fallback = if (allowFallback && accelerated) ({ load(cpuSrc, "cpu") }) else null,
            onFallback = { note(name, it) },
        )
        val device: String get() = if (resource.usingFallback || !accelerated) "CPU" else if (allowCpuPartition) "NPU+CPU" else "NPU"
        fun run(inputs: Map<String, OnnxTensor>): OrtSession.Result = resource.run { it.run(inputs) }
        override fun close() = resource.close()
    }

    private val hubert: ModelSession
    private val rmvpe: ModelSession
    private val enc: ModelSession
    private val decA: ModelSession?
    private val decB: ModelSession?
    @Volatile private var ncnn: NcnnDecoder? = null
    private var dec: ModelSession? = null
    private fun cpuDecoder() = own(ModelSession(str(R.string.ui_decoder), File(modelDir, "model.dec.onnx"),
        File(modelDir, "model.dec.onnx"), "cpu", null, decoderDims))
    val frontDevice: String get() = listOf(hubert.device, rmvpe.device, enc.device).flatMap { it.split('+') }.distinct().joinToString("+")
    val decoderDevice: String get() = when {
        ncnn != null -> "GPU"
        decA != null -> if (decA.device == "CPU") "CPU" else "NPU+CPU"
        dec?.device == "NPU" -> if ("w8a16" in decFileName) str(R.string.ui_integer_npu) else str(R.string.ui_bit_npu)
        else -> "CPU"
    }
    private val decFileName = decFile

    init {
        try {
            fun frontSession(name: String, file: String, dims: Map<String, Long>): ModelSession {
                val original = File(commonDir, file)
                val source = if (file == "hubert.onnx" && frontFixed)
                    File(commonDir, "hubert.htp.onnx").takeIf { it.isFile } ?: original else original
                val cache = if (frontFixed) File(commonDir, "${source.nameWithoutExtension}.$front.${dims.values.joinToString("-")}.onnx") else null
                return own(ModelSession(name, source, original, front, cache, if (frontFixed) dims else emptyMap(),
                    allowCpuPartition = frontFixed && file == "rmvpe.onnx"))
            }
            hubert = frontSession(str(R.string.ui_feature_extraction), "hubert.onnx", mapOf("samples" to hubertSamples.toLong()))
            rmvpe = frontSession(str(R.string.ui_pitch), "rmvpe.onnx", mapOf("samples" to rmvpeSamples.toLong()))
            enc = own(ModelSession(str(R.string.ui_encoder), File(modelDir, "model.enc.onnx"), File(modelDir, "model.enc.onnx"), front,
                if (frontFixed) File(modelDir, "model.enc.$front.$frames.onnx") else null,
                if (frontFixed) mapOf("T" to frames.toLong()) else emptyMap()))
            if (ep == "ncnn_gpu") {
                try {
                    val gpu = NcnnDecoder(File(modelDir, "model.dec.ncnn.param"), File(modelDir, "model.dec.ncnn.bin"), true, threads)
                    if (gpu.usesGpu) ncnn = gpu else { gpu.close(); error("Vulkan GPU unavailable") }
                } catch (failure: Throwable) {
                    if (!BackendFallback.recoverable(failure)) throw failure
                    note(str(R.string.ui_decoder), failure)
                }
            }
            decA = if (!split) null else own(ModelSession(str(R.string.ui_decoder_front), File(modelDir, "model.dec_a.onnx"),
                File(modelDir, "model.dec_a.onnx"), "qnn_htp", File(modelDir, "model.dec_a.qnn_htp.n$decFrames.onnx"), decoderDims))
            decB = if (!split) null else own(ModelSession(str(R.string.ui_decoder_back), File(modelDir, "model.dec_b.onnx"),
                File(modelDir, "model.dec_b.onnx"), "cpu", null, emptyMap()))
            if (ncnn == null && !split) {
                val backend = if (ep == "ncnn_gpu" || ep == "split") "cpu" else ep
                dec = own(ModelSession(str(R.string.ui_decoder), File(modelDir, if (backend == "cpu" && ep == "ncnn_gpu") "model.dec.onnx" else decFile),
                    File(modelDir, "model.dec.onnx"), backend,
                    if (qnnOptions(backend) != null) File(modelDir, "${decFile.removeSuffix(".onnx")}.$backend.n$decFrames.onnx") else null,
                    decoderDims))
            }
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }
    private val rng = Random(seed)

    override fun close() {
        ncnn?.let { runCatching { it.close() } }; ncnn = null
        owned.asReversed().forEach { runCatching { it.close() } }
        owned.clear()
    }

    /** progress(done, total) is called after each chunk. */
    fun convert(x48: FloatArray, st: Settings, progress: (Int, Int) -> Unit = { _, _ -> }): FloatArray {
        var x16 = Dsp.resample(x48, SR, SR16)
        x16 = x16.copyOf(x16.size + Math.floorMod(-x16.size, UNIT))
        val chunk = if (fixedWindows) frontChunk else (CHUNK_S * SR16).toInt() / UNIT * UNIT
        val ctx = (CONTEXT_S * SR16).toInt() / UNIT * UNIT
        val fade = (FADE_S * SR16).toInt() / UNIT * UNIT
        val out = FloatArray(x16.size * 3)
        val weight = FloatArray(out.size)
        val starts = (0 until x16.size step chunk).toList()
        for ((i, start) in starts.withIndex()) {
            val coreEnd = min(start + chunk + fade, x16.size)
            var lo = max(0, start - ctx)
            var hi = min(x16.size, coreEnd + ctx)
            val win = if (!fixedWindows) x16.copyOfRange(lo, hi) else {
                // fixed-size windows for the NPU: fill with real audio by reaching further back, pad only short clips
                lo = max(0, hi - window)
                hi = min(x16.size, lo + window)
                x16.copyOfRange(lo, hi).copyOf(window)
            }
            // only the frames this window contributes go through the decoder, not its context
            val y = window(win, st, (start - lo) / 160, (coreEnd - lo + 159) / 160)
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

    /** One window of 16 kHz audio (a multiple of 320 samples) -> 48 kHz audio of the same duration, of which only
     *  frames [keepFrom, keepTo) are decoded (the rest is the window's context and stays silent). */
    private fun window(x16: FloatArray, st: Settings, keepFrom: Int, keepTo: Int): FloatArray {
        val clock = Lap()
        val (z, f0, pLen) = analyze(x16, st, null, clock)
        val factor = 2.0.pow(st.formant / 12)
        val n = ceil(pLen * factor).toInt()
        val zn = Dsp.stretchLinear(z, 192, pLen, n)
        val f0n = Dsp.stretchNearest(f0, n)  // no n/pLen factor: the excitation is built at n frames directly
        val sine = Dsp.nsfSource(f0n, rng)
        clock.lap(4)
        // a frame more each side for the formant's resampling back to 48 kHz
        var audio = decode(zn, n, sine, max(0, floor(keepFrom * factor).toInt() - 1), min(n, ceil(keepTo * factor).toInt() + 1))
        clock.lap(3)
        val upp = floor(factor * 480).toInt()
        if (upp != 480) audio = Dsp.resample(audio.copyOf(min(audio.size, pLen * upp)), upp, 480)
        return audio.copyOf(pLen * 480).also { clock.lap(4) }
    }

    /** Realtime: one 16 kHz window (past context plus the newest audio, a multiple of 320 samples) -> 48 kHz audio for
     *  its last `tail` frames. Only those frames, with their left context, go through the decoder ([decFrames] of them,
     *  whatever the formant). [state] carries the excitation's phase to the next window, whose decoder starts
     *  `advance` frames later (so the harmonics run on without a jump), and the pitch of frames seen before. */
    fun streamWindow(x16: FloatArray, st: Settings, tail: Int, advance: Int, state: StreamState?): FloatArray {
        live?.fixedFormant?.let { require(abs(st.formant - it) < 1e-9) { "Fixed-formant session must be rebuilt after a formant change" } }
        val clock = Lap()
        state?.advance(advance)
        val (z, f0, pLen) = analyze(x16, st, state, clock)
        val fixed = decFrames
        val factor = 2.0.pow(st.formant / 12)
        val d = min(pLen, max(tail, floor(fixed / factor).toInt()))
        val zd = FloatArray(192 * d)
        for (c in 0 until 192) System.arraycopy(z, c * pLen + pLen - d, zd, c * d, d)
        val n = ceil(d * factor).toInt()
        val zn = Dsp.stretchLinear(zd, 192, d, n)
        val f0n = Dsp.stretchNearest(f0.copyOfRange(pLen - d, pLen), n)
        val mark = (advance * factor * 480).roundToInt()
        val (source, phaseAtMark) = Dsp.nsfSource(f0n, rng, state?.phase ?: 0.0, mark)
        state?.phase = phaseAtMark
        // pad on the left up to the fixed size (that part is context and gets dropped)
        val pad = max(0, fixed - n)
        val zp = if (pad == 0) zn else FloatArray(192 * (n + pad)).also {
            for (c in 0 until 192) System.arraycopy(zn, c * n, it, c * (n + pad) + pad, n)
        }
        val sp = if (pad == 0) source else FloatArray((n + pad) * 480).also { System.arraycopy(source, 0, it, pad * 480, source.size) }
        clock.lap(4)
        var audio = runDec(zp, n + pad, sp)
        if (pad > 0) audio = audio.copyOfRange(pad * 480, audio.size)
        clock.lap(3)
        val upp = floor(factor * 480).toInt()
        if (upp != 480) audio = Dsp.resampleRepeated(audio.copyOf(min(audio.size, d * upp)), upp, 480)  // same size every step
        audio = audio.copyOf(d * 480)
        clock.lap(4)
        val keep = (d - min(tail, d)) * 480  // drop the decoder's left context
        return audio.copyOfRange(keep, audio.size)
    }

    /** Adds the time since the last lap to a stage of [timing]. */
    private inner class Lap {
        private var t0 = System.nanoTime()
        fun lap(stage: Int) { val t = System.nanoTime(); timing[stage] += (t - t0) / 1e9; t0 = t }
    }

    private data class Analysis(val z: FloatArray, val f0: DoubleArray, val pLen: Int)

    /** HuBERT, RMVPE, pitch shaping and enc over one 16 kHz window: the latent z [192, pLen] and the shifted f0. */
    private fun analyze(x16: FloatArray, st: Settings, cache: StreamState?, clock: Lap): Analysis {
        val pLen = x16.size / 160
        // content: 50 fps, last frame repeated, each frame doubled to 100 fps
        val feats50 = run(hubert, "audio", x16, longArrayOf(1, x16.size.toLong()))
        clock.lap(0)
        val f50 = feats50.size / 768
        val phone = FloatArray(pLen * 768)
        for (t in 0 until pLen) {
            val src = min(t / 2, f50)  // index f50 is the appended copy of the last frame
            System.arraycopy(feats50, min(src, f50 - 1) * 768, phone, t * 768, 768)
        }
        // pitch
        var f0: DoubleArray
        if (cache != null && cache.f0.size == pLen && cache.samples < x16.size) {
            // only the newest audio: its frame j is window frame pLen - k + j; the first 3 and the very last lack
            // context and are left to the neighbouring steps (RVC's cache_pitch[4 - n:] = pitch[3:-1])
            val k = cache.samples / 160
            val newest = x16.copyOfRange(x16.size - cache.samples, x16.size)
            val salience = run(rmvpe, "audio", newest, longArrayOf(1, newest.size.toLong()))
            val part = Dsp.decodeF0(salience, salience.size / 360)
            for (j in 3 until min(k, part.size)) cache.f0[pLen - k + j] = part[j]
            f0 = cache.f0.copyOf()
        } else {
            val salience = run(rmvpe, "audio", x16, longArrayOf(1, x16.size.toLong()))
            f0 = Dsp.decodeF0(salience, salience.size / 360).copyOf(pLen)
        }
        clock.lap(1)
        f0 = Dsp.fillUnvoiced(f0, if (st.unvoicedKeep) UNVOICED_FILL_FRAMES else null)
        f0 = Dsp.shapePitch(f0, st.lowKnee, st.lowRatio, st.highKnee, st.highRatio,
            INTONATION_CENTER_HZ, if (st.expand) st.expandRatio else 1.0)
        val key = 2.0.pow((st.pitch - st.formant) / 12)  // the formant moves by time stretch; pitch compensates
        for (t in f0.indices) f0[t] *= key
        val coarse = LongArray(pLen) { Dsp.coarse(f0[it]) }
        val noise = FloatArray(192 * pLen) { rng.nextGaussian().toFloat() }
        clock.lap(4)
        val z = OnnxTensor.createTensor(env, FloatBuffer.wrap(phone), longArrayOf(1, pLen.toLong(), 768)).use { ph ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(coarse), longArrayOf(1, pLen.toLong())).use { pi ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(noise), longArrayOf(1, 192, pLen.toLong())).use { no ->
                    enc.run(mapOf("phone" to ph, "pitch" to pi, "noise" to no)).use { floats(it[0] as OnnxTensor) }
                }
            }
        }
        clock.lap(2)
        return Analysis(z, f0, pLen)
    }

    private fun runDec(z: FloatArray, frames: Int, sine: FloatArray): FloatArray {
        ncnn?.let { gpu ->
            try { return gpu.decode(z, frames, sine) } catch (failure: Throwable) {
                if (!allowFallback || !BackendFallback.recoverable(failure)) throw failure
                gpu.close(); ncnn = null
                dec = cpuDecoder()
                note(str(R.string.ui_decoder), failure)
            }
        }
        if (decA != null && decB != null) {
            val hidden = OnnxTensor.createTensor(env, FloatBuffer.wrap(z), longArrayOf(1, 192, frames.toLong())).use { zt ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(sine), longArrayOf(1, sine.size.toLong(), 1)).use { st ->
                    decA.run(mapOf("z" to zt, "sine" to st)).use { floats(it[0] as OnnxTensor) }
                }
            }
            val length = frames * 240L  // the hidden signal runs at 24 kHz: 240 samples a frame
            return OnnxTensor.createTensor(env, FloatBuffer.wrap(hidden), longArrayOf(1, hidden.size / length, length)).use { ht ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(sine), longArrayOf(1, sine.size.toLong(), 1)).use { st ->
                    decB.run(mapOf("hidden" to ht, "sine" to st)).use { floats(it[0] as OnnxTensor) }
                }
            }
        }
        val session = dec ?: throw IllegalStateException(str(R.string.ui_decoder_unavailable))
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(z), longArrayOf(1, 192, frames.toLong())).use { zt ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(sine), longArrayOf(1, sine.size.toLong(), 1)).use { st ->
                session.run(mapOf("z" to zt, "sine" to st)).use { floats(it[0] as OnnxTensor) }
            }
        }
    }

    /** The decoder over frames [from, to) of n (the rest of the output stays silent): in one go with DEC_CONTEXT
     *  frames of context each side, or for fixed-shape runtimes in DEC_FRAMES windows that keep that context. The
     *  decoder is local, so the pieces join exactly (checked on the PC: frames away from the clip's two ends match a
     *  one-shot decode at >100 dB). */
    private fun decode(z: FloatArray, n: Int, sine: FloatArray, from: Int = 0, to: Int = n): FloatArray {
        val out = FloatArray(n * 480)
        if (!decFixed) {
            val a = max(0, from - DEC_CONTEXT)
            val b = min(n, to + DEC_CONTEXT)
            if (a == 0 && b == n) return runDec(z, n, sine)
            val zz = FloatArray(192 * (b - a))
            for (c in 0 until 192) System.arraycopy(z, c * n + a, zz, c * (b - a), b - a)
            val y = runDec(zz, b - a, sine.copyOfRange(a * 480, b * 480))
            System.arraycopy(y, (from - a) * 480, out, from * 480, (to - from) * 480)
            return out
        }
        val hop = DEC_FRAMES - 2 * DEC_CONTEXT
        var start = from
        while (start < to) {
            val lo = start - DEC_CONTEXT
            val zz = FloatArray(192 * DEC_FRAMES)
            val ss = FloatArray(DEC_FRAMES * 480)
            val a = max(lo, 0)
            val b = min(lo + DEC_FRAMES, n)
            for (c in 0 until 192) System.arraycopy(z, c * n + a, zz, c * DEC_FRAMES + (a - lo), b - a)
            System.arraycopy(sine, a * 480, ss, (a - lo) * 480, (b - a) * 480)
            val y = runDec(zz, DEC_FRAMES, ss)
            val take = min(hop, to - start)
            System.arraycopy(y, DEC_CONTEXT * 480, out, start * 480, take * 480)
            start += hop
        }
        return out
    }

    private fun run(session: ModelSession, input: String, data: FloatArray, shape: LongArray): FloatArray =
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
                "enable_htp_fp16_precision" to "1",  // detect the on-device HTP architecture
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
        /** The formant slider's top (+2 semitones): realtime sizes its decoder input for it. */
        val MAX_FORMANT_FACTOR = 2.0.pow(2.0 / 12)
        /** Realtime window of the NPU's fixed-shape front models, in 10 ms frames (1.6 s, as on the iPhone). */
        const val REALTIME_WINDOW = 160
        const val DEC_CONTEXT = 16  // 160 ms of context each side
        /** Realtime decodes with 10 frames of left context: on the PC the kept block then matches a one-shot decode
         *  at 126 dB (8 frames: 62 dB, 6: 35 dB). */
        const val LIVE_DEC_CONTEXT = 10
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

/**
 * Realtime state carried from step to step: the excitation's phase (cycles) and the pitch already found.
 *
 * Pitch as the RVC realtime GUI does it: RMVPE runs only over the newest audio and the older frames are kept from
 * earlier steps. The newest stretch is 32k - 1 frames long, so RMVPE pads nothing. Over the whole window (padded up to
 * a multiple of 32 frames) the played frames matched whole-file pitch less often (94.5% vs 97.7% within 50 cents,
 * voicing differing in 8.6% vs 2.8% of frames, on the PC) and cost about five times more.
 */
class StreamState(frames: Int, block: Int) {
    var phase = 0.0
    /** Raw RMVPE pitch (Hz, 0 = unvoiced) for every frame of the window, before any shaping. */
    val f0 = DoubleArray(frames)
    /** 16 kHz samples RMVPE gets each step. */
    val samples = rmvpeSamples(block)

    /** After skipping ahead (or restarting) the kept frames no longer line up: start over. */
    fun reset() {
        f0.fill(0.0)
        phase = 0.0
    }

    fun advance(frames: Int) {
        val n = min(frames, f0.size)
        if (n <= 0) return
        System.arraycopy(f0, n, f0, 0, f0.size - n)
        f0.fill(0.0, f0.size - n, f0.size)
    }

    companion object {
        fun rmvpeSamples(block: Int) = 5120 * ((block * 160 + 800 + 5119) / 5120) - 160
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
    fun nsfSource(f0: DoubleArray, rng: Random, upp: Int = 480, sineAmp: Double = 0.1, noiseStd: Double = 0.003): FloatArray =
        nsfSource(f0, rng, 0.0, -1, upp, sineAmp, noiseStd).first

    /** The same, starting from `startPhase` (in cycles); also returns the phase just before sample `markAt`, where
     *  the next realtime window starts. */
    fun nsfSource(f0: DoubleArray, rng: Random, startPhase: Double, markAt: Int, upp: Int = 480, sineAmp: Double = 0.1,
                  noiseStd: Double = 0.003): Pair<FloatArray, Double> {
        val out = FloatArray(f0.size * upp)
        var phase = startPhase
        var marked = startPhase
        var i = 0
        for (f in f0) {
            val step = f / 48000.0
            for (k in 0 until upp) {
                if (i == markAt) marked = phase
                phase += step
                phase -= floor(phase)
                val noise = rng.nextGaussian()
                out[i++] = if (f > 0) (sineAmp * sin(2 * PI * phase) + noiseStd * noise).toFloat()
                           else (sineAmp / 3 * noise).toFloat()
            }
        }
        if (markAt >= i) marked = phase
        return out to marked
    }

    /** The best start for the next block within `search` samples: where its head lines up with `previous`, the tail
     *  that is about to be cross-faded (normalised cross-correlation, as the RVC realtime GUI's SOLA). */
    fun solaOffset(head: FloatArray, previous: FloatArray, search: Int): Int {
        val n = previous.size
        if (search <= 0 || head.size < n + search) return 0
        var best = 0
        var bestScore = Float.NEGATIVE_INFINITY
        for (off in 0..search) {
            var num = 0f
            var den = 1e-8f
            for (i in 0 until n) {
                val v = head[off + i]
                num += v * previous[i]
                den += v * v
            }
            val score = num / sqrt(den)
            if (score > bestScore) { bestScore = score; best = off }
        }
        return best
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
        if (src % dst == 0) return decimate(x, src / dst, zeros)
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

    /** [resample] by a whole factor (48 -> 16 kHz): every output sits on an input sample, so the kernel is needed only at
     *  whole distances and is worked out once; the same values as the general path, without its table lookups
     *  (realtime resamples its whole window every step: ~25 ms a step on the S23+ before). */
    private fun decimate(x: FloatArray, factor: Int, zeros: Int): FloatArray {
        val cutoff = 0.95 / factor
        val width = zeros / cutoff
        val taps = taps(factor, zeros, cutoff, width)
        val reach = taps.size - 1
        val n = x.size / factor
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i * factor
            var acc = 0.0
            for (j in max(t - reach, 0)..min(t + reach, x.size - 1)) acc += x[j] * taps[abs(t - j)]
            out[i] = acc.toFloat()
        }
        return out
    }

    private val tapSets = HashMap<Int, DoubleArray>()

    /** Exact rolling 48->16k conversion. Recompute both window boundaries and all newly arrived samples. */
    class WindowResampler {
        private var previous: FloatArray? = null
        private var converted: FloatArray? = null
        fun reset() { previous=null; converted=null }
        fun process(x: FloatArray, advance: Int): FloatArray {
            val prev=previous
            val old=converted
            var reusable=prev!=null && old!=null && prev.size==x.size && advance>0 && advance<x.size && advance%3==0
            if(reusable) {
                for(i in 0 until x.size-advance) if(x[i]!=prev!![i+advance]) { reusable=false; break }
            }
            val out: FloatArray
            if(!reusable) out=decimate(x,3,24)
            else {
                val cutoff=.95/3
                val weights=taps(3,24,cutoff,24/cutoff)
                val reach=weights.size-1
                val shift=advance/3
                out=FloatArray(x.size/3)
                for(i in out.indices) {
                    val t=i*3
                    if(t>=reach && t+reach<x.size-advance) out[i]=old!![i+shift]
                    else {
                        var acc=0.0
                        for(j in max(t-reach,0)..min(t+reach,x.size-1)) acc+=x[j]*weights[abs(t-j)]
                        out[i]=acc.toFloat()
                    }
                }
            }
            // Keep a copy: callers may refill the same rolling input array for their next step.
            previous=x.copyOf(); converted=out
            return out
        }
    }

    @Synchronized
    private fun taps(factor: Int, zeros: Int, cutoff: Double, width: Double): DoubleArray = tapSets.getOrPut(factor * 1000 + zeros) {
        val table = kernel(cutoff, width)
        val steps = (table.size - 1) / width
        DoubleArray(floor(width).toInt() + 1) { d ->
            val pos = d * steps
            val k = pos.toInt()
            if (k + 1 < table.size) table[k] + (table[k + 1] - table[k]) * (pos - k) else table[table.size - 1]
        }
    }

    /** [resample] for a length that repeats (realtime's formant step: the same input size every step): the weights of
     *  every output are worked out on the first call and kept for the next ones with the same rates and size. */
    fun resampleRepeated(x: FloatArray, src: Int, dst: Int): FloatArray {
        if (src == dst || x.isEmpty()) return x
        val p = synchronized(this) {
            plan?.takeIf { it.src == src && it.dst == dst && it.size == x.size } ?: Plan(src, dst, x.size).also { plan = it }
        }
        val out = FloatArray(p.first.size)
        for (i in out.indices) {
            var acc = 0.0
            val a = p.first[i]
            val base = i * p.taps
            for (k in 0 until p.count[i]) acc += x[a + k] * p.w[base + k]
            out[i] = acc.toFloat()
        }
        return out
    }

    private class Plan(val src: Int, val dst: Int, val size: Int, zeros: Int = 24) {
        val first: IntArray
        val count: IntArray
        val taps: Int
        val w: FloatArray

        init {
            val ratio = dst.toDouble() / src
            val n = floor(size * ratio).toInt()
            val cutoff = min(1.0, ratio) * 0.95
            val width = zeros / cutoff
            val table = kernel(cutoff, width)
            val steps = (table.size - 1) / width
            taps = 2 * ceil(width).toInt() + 2
            first = IntArray(n)
            count = IntArray(n)
            w = FloatArray(n * taps)
            for (i in 0 until n) {
                val t = i / ratio
                val a = max(ceil(t - width).toInt(), 0)
                val b = min(floor(t + width).toInt(), size - 1)
                first[i] = a
                count[i] = max(0, b - a + 1)
                for (j in a..b) {
                    val pos = abs(t - j) * steps
                    val k = pos.toInt()
                    w[i * taps + j - a] = (if (k + 1 < table.size) table[k] + (table[k + 1] - table[k]) * (pos - k) else table[table.size - 1]).toFloat()
                }
            }
        }
    }

    private var plan: Plan? = null

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
