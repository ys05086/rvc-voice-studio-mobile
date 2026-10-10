package com.voicestudio.phone

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Samples shared between an audio thread and the worker: a circular store addressed by absolute position, also
 *  readable first-in first-out. Short critical sections, so the audio threads never wait long. */
class SampleRing(private val capacity: Int) {
    private val data = FloatArray(capacity)
    private var written = 0L
    private var readPos = 0L

    @Synchronized
    fun write(x: FloatArray, n: Int = x.size) {
        var i = 0
        while (i < n) {
            val at = ((written + i) % capacity).toInt()
            val k = min(n - i, capacity - at)
            System.arraycopy(x, i, data, at, k)
            i += k
        }
        written += n
        if (written - readPos > capacity) readPos = written - capacity
    }

    /** First-in first-out: up to n samples into p; returns how many there were. */
    @Synchronized
    fun read(p: FloatArray, n: Int): Int {
        val k = min(n.toLong(), written - readPos).toInt()
        var i = 0
        while (i < k) {
            val at = ((readPos + i) % capacity).toInt()
            val c = min(k - i, capacity - at)
            System.arraycopy(data, at, p, i, c)
            i += c
        }
        readPos += k
        return k
    }

    /** Samples [start, start + n) by absolute position; positions not stored (yet, or any more) read as zeros. */
    @Synchronized
    fun copy(start: Long, n: Int): FloatArray {
        val out = FloatArray(n)
        val from = max(start, max(0L, written - capacity))
        val to = min(start + n, written)
        var pos = from
        while (pos < to) {
            val at = (pos % capacity).toInt()
            val c = min(to - pos, (capacity - at).toLong()).toInt()
            System.arraycopy(data, at, out, (pos - start).toInt(), c)
            pos += c
        }
        return out
    }

    val total: Long @Synchronized get() = written
    val queued: Int @Synchronized get() = (written - readPos).toInt()

    /** Drops the oldest queued samples so at most `keep` remain; returns how many went. */
    @Synchronized
    fun trim(keep: Int): Int {
        val cut = max(0L, written - readPos - keep)
        readPos += cut
        return cut.toInt()
    }
}

/**
 * Realtime conversion: the microphone converted into the earphones, a [LiveCore] step at a time (a port of the
 * iPhone's LiveEngine). Three threads: the microphone fills a ring, a worker runs a step whenever a block has
 * arrived, and the output thread feeds the earphones from a second ring, which starts with a cushion of silence.
 *
 * Every step leaves a line in files/live_blocks.csv (the previous session's in live_blocks_prev.csv, the iPhone's
 * columns), and the session ends with a summary in live_log.txt: which stage was slow, when the earphones ran dry,
 * what was skipped.
 */
class LiveEngine(private val context: Context, private val rvc: Rvc, val params: LiveParams, settings: Rvc.Settings,
                 private val speaker: Boolean, private val bypass: Boolean, private val dir: File) {
    data class Stats(
        val block: Double = 0.0,       // seconds of audio per step
        val compute: Double = 0.0,     // seconds per step, smoothed
        val computeMax: Double = 0.0,  // over the last ~5 s
        val latency: Double = 0.0,     // input to output, smoothed (the device's own buffers estimated)
        val steps: Int = 0,
        val drops: Int = 0,            // times the worker fell too far behind and skipped ahead
        val underruns: Int = 0,        // times the earphones ran out of audio
        val clips: Int = 0,            // steps whose output the soft clip had to bend (too loud)
        val inPeakDb: Double = -120.0, // the loudest input of the last ~2 s, before the output boost
        val outPeakDb: Double = -120.0, // the loudest output of the last ~2 s, before the soft clip
        val extraCushion: Double = 0.0, // seconds of queue added after underruns
        val thermal: Int = 0,          // PowerManager thermal status: 0 none ... 6 shutdown
        val inPeaks: FloatArray = FloatArray(0),  // the last few seconds, one peak per 20 ms
        val outPeaks: FloatArray = FloatArray(0),
        val recording: Double = -1.0,  // seconds recorded so far; -1 when not recording
        val output: String = "",       // where the converted voice goes
        val failure: String? = null,
    )

    private val core = LiveCore(rvc, params)
    private val mic = SampleRing(48000 * 8)
    private val out = SampleRing(48000 * 4)
    private val wake = Semaphore(0)
    @Volatile private var running = false
    @Volatile private var settings = settings
    private var stats = Stats()
    private val lock = Any()
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private val threads = ArrayList<Thread>()
    // files are written on their own thread, so a slow flash write never holds up a step
    private val io = Executors.newSingleThreadExecutor()
    private var recIn: WavWriter? = null   // only touched on io
    private var recOut: WavWriter? = null  // only touched on io
    @Volatile private var recording = false
    private var recSamples = 0
    private var blockLog: java.io.FileWriter? = null  // only touched on io
    // underruns: counted by the output thread once converted audio was queued
    @Volatile private var armed = false
    private var underrunEvents = 0
    private var underrunSamples = 0L
    private val power = context.getSystemService(PowerManager::class.java)

    val isRunning get() = running

    fun snapshot(): Stats = synchronized(lock) { stats }

    /** Voice settings apply from the next step. */
    fun update(s: Rvc.Settings) { settings = s }

    /** Output boost in dB, from the next step. */
    fun setGain(db: Double) { core.gain = Math.pow(10.0, db / 20).toFloat() }

    /** The device that gets the converted voice: the loudspeaker when asked, else earphones when any are connected
     *  (wired, USB, Bluetooth), else the earpiece, so the voice does not go round through the microphone. */
    private fun outputDevice(am: AudioManager): Pair<AudioDeviceInfo?, Boolean> {
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        if (speaker) return outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } to false
        val headsets = mutableSetOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_HEARING_AID)
        if (Build.VERSION.SDK_INT >= 31) headsets += AudioDeviceInfo.TYPE_BLE_HEADSET
        outs.firstOrNull { it.type in headsets }?.let { return it to false }
        return outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE } to true
    }

    private fun describe(d: AudioDeviceInfo?): String = when (d?.type) {
        null -> "기본 출력"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "스피커"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "통화 스피커 (귀에 대고 들어요)"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "블루투스 ${d.productName}"
        else -> "이어폰 ${d.productName}"
    }

    /** A few silent steps before the audio starts: the first runs of a new shape are slow. */
    fun warmUp() {
        repeat(3) { i ->
            val t0 = System.nanoTime()
            core.step(FloatArray(core.w48), settings, bypass)
            log(String.format(Locale.US, "warm-up %d: %.3f s", i + 1, (System.nanoTime() - t0) / 1e9))
        }
        core.reset()
    }

    @SuppressLint("MissingPermission")  // the activity asks for RECORD_AUDIO first
    fun start() {
        val am = context.getSystemService(AudioManager::class.java)
        val source = if (am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
            MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val inFormat = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(48000)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        val inMin = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val rec = AudioRecord.Builder().setAudioSource(source).setAudioFormat(inFormat)
            .setBufferSizeInBytes(max(inMin, 4 * 4800)).build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); throw IllegalStateException("마이크를 열지 못했어요") }
        val (device, earpiece) = outputDevice(am)
        val usage = if (earpiece) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA
        val outFormat = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(48000)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        val outMin = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val trk = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(outFormat).setBufferSizeInBytes(max(outMin, 4 * 960))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).setTransferMode(AudioTrack.MODE_STREAM).build()
        if (device != null) trk.preferredDevice = device
        // the device buffer only has to ride out this thread's scheduling: the ring before it holds the cushion. The
        // minimum the phone suggests was 100 ms on the S23+ (4800 frames), which went straight into the latency.
        val burst = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 240
        trk.setBufferSizeInFrames(max(2 * burst, 960))
        record = rec
        track = trk
        synchronized(lock) { stats = Stats(block = params.block / 100.0, output = describe(device)) }
        running = true
        rec.startRecording()
        trk.play()
        threads += Thread({ captureLoop(rec) }, "live-mic")
        threads += Thread({ renderLoop(trk) }, "live-out")
        threads += Thread({ workerLoop(source, trk) }, "live-worker")
        threads.forEach { it.start() }
    }

    fun stop(failure: String? = null) {
        if (failure != null) synchronized(lock) { stats = stats.copy(failure = failure) }
        if (!running && threads.isEmpty()) return
        running = false
        wake.release()
        val me = Thread.currentThread()
        threads.filter { it !== me }.forEach { it.join(2000) }
        threads.clear()
        runCatching { record?.stop() }; record?.release(); record = null
        runCatching { track?.stop() }; track?.release(); track = null
        stopRecording()
        io.submit { runCatching { blockLog?.close() }; blockLog = null }
        io.shutdown()
        io.awaitTermination(2, TimeUnit.SECONDS)
    }

    /** Starts writing the microphone and the converted voice to two WAV files, from the next step. */
    fun startRecording(input: File, output: File) {
        io.submit { recIn = WavWriter(input); recOut = WavWriter(output) }.get()
        synchronized(lock) { recSamples = 0 }
        recording = true
    }

    /** Closes the files once everything queued is written; null when nothing was being recorded. */
    fun stopRecording(): Triple<File, File, Double>? {
        recording = false
        if (io.isShutdown) return null
        return io.submit<Triple<File, File, Double>?> {
            val a = recIn ?: return@submit null
            val b = recOut ?: return@submit null
            recIn = null; recOut = null
            a.finish(); b.finish()
            Triple(a.file, b.file, b.samples / 48000.0)
        }.get()
    }

    private fun captureLoop(rec: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = FloatArray(480)
        while (running) {
            val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
            if (n > 0) {
                mic.write(buf, n)
                wake.release()
            } else if (n < 0) {
                log("microphone read failed: $n")
                Thread.sleep(10)
            }
        }
    }

    private fun renderLoop(trk: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = FloatArray(480)
        while (running) {
            val got = out.read(buf, buf.size)
            if (got < buf.size) {
                buf.fill(0f, got, buf.size)
                if (armed) synchronized(lock) { underrunEvents++; underrunSamples += buf.size - got }
            }
            trk.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
        }
    }

    private fun log(text: String) {
        Log.i("VoiceStudio", text)
        val line = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date()) + "  " + text + "\n"
        if (!io.isShutdown) io.submit {
            val f = File(dir, "live_log.txt")
            if (f.length() > 2_000_000) f.renameTo(File(dir, "live_log_prev.txt"))
            f.appendText(line)
        }
    }

    private fun workerLoop(source: Int, trk: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        try {
            work(source, trk)
        } catch (e: Throwable) {
            Log.e("VoiceStudio", "live worker failed", e)
            log("live failed: ${e.message}")
            synchronized(lock) { stats = stats.copy(failure = e.message ?: e.toString()) }
            running = false
            wake.release()
        }
    }

    private fun work(source: Int, trk: AudioTrack) {
        val b = params.block
        val b48 = core.b48
        val w48 = core.w48
        val tail = params.tail
        val cushion = (params.cushion * 48000).toInt()
        var consumed = mic.total
        var primed = false
        var lastUnder = 0
        var lastMissing = 0L
        var extraCushion = 0
        var drops = 0
        var trimmed = 0L
        val recent = ArrayDeque<Double>()
        val inLevels = ArrayDeque<Float>()
        val outLevels = ArrayDeque<Float>()
        val allIn = ArrayList<Float>()
        val computeAll = ArrayList<Double>()
        val worst = ArrayList<Pair<Double, String>>()
        val inPeaks = ArrayList<Float>()
        val outPeaks = ArrayList<Float>()
        var csv = StringBuilder()
        var smoothCompute = 0.0
        var smoothLatency = 0.0
        val ioLatency = trk.bufferSizeInFrames / 48000.0 + 0.02  // the output buffer plus a guess for the input side
        val begun = System.nanoTime()
        var lastLog = begun
        var lastTiming = rvc.timing.copyOf()
        val setup = "block ${params.block} window ${core.window} (context ${core.window - tail}) crossfade ${params.crossfade} " +
            "search ${params.search} cushion ${params.cushion} threshold ${params.thresholdDb} decoder ${rvc.decoderDevice} " +
            "dec frames ${rvc.decFrames} bypass $bypass mic ${if (source == MediaRecorder.AudioSource.UNPROCESSED) "unprocessed" else "voice recognition"} " +
            "out ${snapshot().output} (buffer ${trk.bufferSizeInFrames} of ${trk.bufferCapacityInFrames} frames)"
        log("live start: $setup")
        io.submit {
            val f = File(dir, "live_blocks.csv")
            File(dir, "live_blocks_prev.csv").delete()
            f.renameTo(File(dir, "live_blocks_prev.csv"))
            blockLog = java.io.FileWriter(File(dir, "live_blocks.csv")).apply { write("# $setup\n$CSV_HEADER\n") }
        }
        fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
        try {
            while (running) {
                wake.tryAcquire(100, TimeUnit.MILLISECONDS)
                wake.drainPermits()
                while (running) {
                    val have = mic.total
                    if (have - consumed < b48) break
                    val recordingNow = recording
                    // fell too far behind: skip to the newest step. While recording, wait up to 2 s first so the
                    // recording stays whole; the earphones meanwhile catch up by dropping queued output instead.
                    val limit = if (recordingNow) max(3 * b48, 2 * 48000) else 3 * b48
                    if (have - consumed > limit) {
                        val skipped = (have - b48 - consumed).toInt()
                        // the recorded original runs (crossfade + search) frames behind the newest audio
                        val missed = mic.copy(consumed - (tail - b) * 480L, skipped)
                        consumed = have - b48
                        core.reset()
                        drops++
                        if (recordingNow) {  // keep the recording in time: the original keeps the audio, the voice gets silence
                            synchronized(lock) { recSamples += missed.size }
                            io.submit { recIn?.append(missed); recOut?.append(FloatArray(missed.size)) }
                        }
                        log(String.format(Locale.US, "drop: fell %.2f s behind, skipped ahead", skipped / 48000.0))
                    }
                    val backlog = have - consumed - b48  // what waits behind this step
                    val end = consumed + b48
                    consumed = end
                    val started = System.nanoTime()
                    val before = rvc.timing.copyOf()
                    val x48 = mic.copy(end - w48, w48)
                    val block = core.step(x48, settings, bypass)
                    val modelled = System.nanoTime()
                    // queue for the earphones after an initial cushion; never let the queue grow past it for long
                    if (!primed) out.write(FloatArray(cushion))
                    // the earphones ran dry since the last step: keep 20 ms more queued from now on (up to 0.2 s more),
                    // so an uneven phone stops crackling at the cost of a little latency
                    val (events, missing) = synchronized(lock) { underrunEvents to underrunSamples }
                    if (events > lastUnder && extraCushion < 9600) {
                        extraCushion += 960
                        out.write(FloatArray(960))
                    }
                    var cut = 0
                    if (out.queued > cushion + extraCushion + 2 * b48) cut = out.trim(cushion + extraCushion)
                    trimmed += cut
                    val queued = out.queued
                    out.write(block)
                    if (!primed) { primed = true; armed = true }
                    if (recordingNow) {
                        val heard = core.heard(x48)
                        io.submit { recIn?.append(heard); recOut?.append(block) }
                    }
                    // waveforms: one peak per 20 ms of the stretch this step played
                    val inStart = w48 - tail * 480 + core.lastOffset
                    var k = 0
                    while (k < b48) {
                        val e = min(k + 960, b48)
                        var pi = 0f; var po = 0f
                        for (i in k until e) { pi = max(pi, abs(x48[inStart + i])); po = max(po, abs(block[i])) }
                        inPeaks += pi; outPeaks += po
                        k = e
                    }
                    while (inPeaks.size > 250) inPeaks.removeAt(0)
                    while (outPeaks.size > 250) outPeaks.removeAt(0)
                    val finished = System.nanoTime()
                    val took = (finished - started) / 1e9
                    val t = DoubleArray(5) { (rvc.timing[it] - before[it]) * 1000 }
                    val thermal = power.currentThermalStatus
                    val memory = Debug.getNativeHeapAllocatedSize() / 1e6
                    val fields = listOf(String.format(Locale.US, "%.3f", (started - begun) / 1e9), "${computeAll.size + 1}",
                        f1(backlog * 1000.0 / 48000), f1(took * 1000), f1(core.lastPrepMs), f1(t[0]), f1(t[1]), f1(t[2]), f1(t[3]),
                        f1(t[4]), f1(core.lastPostMs + (finished - modelled) / 1e6), f1(queued / 48.0), "${events - lastUnder}",
                        f1((missing - lastMissing) / 48.0), "$drops", f1(cut / 48.0), f1(extraCushion / 48.0),
                        "${core.lastOffset}", "$thermal", String.format(Locale.US, "%.0f", memory),
                        f1(db(core.lastInPeak)), f1(db(core.lastRawPeak)), if (core.lastClipped) "1" else "0")
                    inLevels.addLast(core.lastInPeak); outLevels.addLast(core.lastRawPeak); allIn += core.lastInPeak
                    while (inLevels.size > max(1, 200 / b)) { inLevels.removeFirst(); outLevels.removeFirst() }
                    csv.append(fields.joinToString(",")).append('\n')
                    computeAll += took
                    if (worst.size < 5 || took > worst.last().first) {
                        worst += took to String.format(Locale.US,
                            "at %.1f s: %.3f s (prep %.0f, HuBERT %.0f, RMVPE %.0f, enc %.0f, decoder %.0f, other %.0f, after %.0f ms), backlog %.0f ms, thermal %d",
                            (started - begun) / 1e9, took, core.lastPrepMs, t[0], t[1], t[2], t[3], t[4], core.lastPostMs,
                            backlog * 1000.0 / 48000, thermal)
                        worst.sortByDescending { it.first }
                        if (worst.size > 5) worst.removeAt(worst.size - 1)
                    }
                    if (took > b / 100.0) log(String.format(Locale.US,
                        "slow step: %.3f s for a %.2f s block (prep %.0f, HuBERT %.0f, RMVPE %.0f, enc %.0f, decoder %.0f, other %.0f ms) thermal %d",
                        took, b / 100.0, core.lastPrepMs, t[0], t[1], t[2], t[3], t[4], thermal))
                    recent.addLast(took)
                    while (recent.size > max(1, 500 / b)) recent.removeFirst()
                    // a sound reaches the earphones after: this step's compute, what was queued ahead of it, its
                    // distance from the newest input (the decoded tail minus the SOLA shift) and the device buffers
                    val latency = took + queued / 48000.0 + (tail * 480 - core.lastOffset) / 48000.0 + ioLatency
                    smoothCompute = if (computeAll.size == 1) took else smoothCompute * 0.9 + took * 0.1
                    smoothLatency = if (computeAll.size == 1) latency else smoothLatency * 0.9 + latency * 0.1
                    synchronized(lock) {
                        if (recordingNow) recSamples += b48
                        stats = stats.copy(compute = smoothCompute, computeMax = recent.max(), latency = smoothLatency,
                            steps = computeAll.size, drops = drops, underruns = events, clips = core.clips,
                            inPeakDb = db(inLevels.max()), outPeakDb = db(outLevels.max()),
                            extraCushion = extraCushion / 48000.0, thermal = thermal,
                            inPeaks = inPeaks.toFloatArray(), outPeaks = outPeaks.toFloatArray(),
                            recording = if (recordingNow) recSamples / 48000.0 else -1.0)
                    }
                    lastUnder = events
                    lastMissing = missing
                    val now = System.nanoTime()
                    if (now - lastLog > 1_000_000_000) {
                        lastLog = now
                        val text = csv.toString()
                        csv = StringBuilder()
                        io.submit { blockLog?.write(text); blockLog?.flush() }
                        val s = snapshot()
                        val d = DoubleArray(5) { rvc.timing[it] - lastTiming[it] }
                        lastTiming = rvc.timing.copyOf()
                        log(String.format(Locale.US, "step %d compute %.3f max %.3f latency %.2f under %d drops %d heap %.0f MB thermal %d " +
                            "| seconds: HuBERT %.3f, RMVPE %.3f, enc %.3f, decoder %.3f, other %.3f",
                            s.steps, s.compute, s.computeMax, s.latency, s.underruns, s.drops, memory, thermal,
                            d[0], d[1], d[2], d[3], d[4]))
                    }
                }
            }
        } finally {
            val text = csv.toString()
            io.submit { blockLog?.write(text); blockLog?.flush() }
            val seconds = (System.nanoTime() - begun) / 1e9
            val sorted = computeAll.sorted()
            val mean = if (sorted.isEmpty()) 0.0 else sorted.average()
            val p95 = if (sorted.isEmpty()) 0.0 else sorted[min(sorted.size - 1, sorted.size * 95 / 100)]
            val (events, missing) = synchronized(lock) { underrunEvents to underrunSamples }
            log("  output device underruns: ${trk.underrunCount}")
            log(String.format(Locale.US, "live summary: %.0f s, %d steps, compute mean %.3f p95 %.3f max %.3f for a %.2f s block (load %.0f%%)",
                seconds, sorted.size, mean, p95, sorted.lastOrNull() ?: 0.0, b / 100.0, mean / (b / 100.0) * 100))
            val levels = allIn.sorted()
            if (levels.isNotEmpty()) log(String.format(Locale.US, "  input peak per step: median %.1f, 95%% %.1f, max %.1f dBFS; output boost %+.0f dB; soft-clipped steps %d of %d",
                db(levels[levels.size / 2]), db(levels[min(levels.size - 1, levels.size * 95 / 100)]), db(levels.last()),
                20 * Math.log10(core.gain.toDouble()), core.clips, levels.size))
            log(String.format(Locale.US, "  earphones ran dry %d times (%.2f s of silence), skipped ahead %d times, dropped %.2f s of queued output",
                events, missing / 48000.0, drops, trimmed / 48000.0))
            worst.forEachIndexed { i, (_, text) -> log("  slowest ${i + 1}: $text") }
            log("live stop" + (snapshot().failure?.let { ": $it" } ?: ""))
        }
    }

    companion object {
        const val CSV_HEADER = "t_s,step,backlog_ms,compute_ms,prep_ms,hubert_ms,rmvpe_ms,enc_ms,dec_ms,other_ms,post_ms," +
            "queued_ms,underruns,underrun_ms,drops,trimmed_ms,extra_ms,sola,thermal,memory_mb,in_peak_db,out_peak_db,soft_clipped"

        fun db(x: Float): Double = 20 * Math.log10(max(x, 1e-6f).toDouble())
    }
}

/** The realtime session, process-wide: the screen, the foreground service and its notification all reach it here. */
object LiveSession {
    @Volatile var engine: LiveEngine? = null
    /** The models stay loaded between sessions with the same voice, devices and shapes (loading takes seconds). */
    var rvc: Rvc? = null
    var rvcKey = ""

    val running get() = engine?.isRunning == true

    fun stop(context: Context) {
        engine?.stop()
        engine = null
        context.stopService(android.content.Intent(context, LiveService::class.java))
    }

    /** Frees the models, for memory (file conversion loads its own). */
    fun release() {
        if (running) return
        rvc?.close()
        rvc = null
        rvcKey = ""
    }
}
