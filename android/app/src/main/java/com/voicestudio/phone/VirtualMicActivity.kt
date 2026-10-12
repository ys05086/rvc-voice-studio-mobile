package com.voicestudio.phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import java.net.Socket
import java.net.InetSocketAddress
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** ADB experiment launched by MainActivity's existing opt-in debug hooks. No AudioRecord/AudioTrack in this app. */
class VirtualMicActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private val ticker = object : Runnable {
        override fun run() {
            status.text = VirtualMicSession.status
            ui.postDelayed(this, 500)
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (!File(getExternalFilesDir(null), "debug_hooks").isFile) { finish(); return }
        status = TextView(this).apply { textSize = 18f; setPadding(32, 48, 32, 32) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@VirtualMicActivity).apply { text = str(R.string.ui_voice_studio_virtual_mic_experiment); textSize = 22f; setPadding(32,64,32,16) })
            addView(status)
            addView(Button(this@VirtualMicActivity).apply { text = str(R.string.ui_stop_conversion); setOnClickListener { VirtualMicSession.stop() } })
        })
        ui.post(ticker)
        if (state == null && !VirtualMicSession.running) {
            if (LiveSession.running) { status.text = str(R.string.ui_stop_the_existing_live_conversion_first); ui.removeCallbacks(ticker); return }
            try {
                startForegroundService(Intent(this, LiveService::class.java))
                VirtualMicSession.start(applicationContext, intent)
            } catch (e: Exception) {
                ui.removeCallbacks(ticker)
                status.text = str(R.string.ui_could_not_start_virtual_mic, e.message)
                stopService(Intent(this, LiveService::class.java))
            }
        }
    }
    override fun onDestroy() { ui.removeCallbacks(ticker); super.onDestroy() }
}

object VirtualMicSession {
    data class Meter(val input: Float = 0f, val output: Float = 0f, val computeMs: Double = 0.0, val drops: Int = 0)
    @Volatile var meter = Meter(); private set
    @Volatile var failureMessage: String? = null; private set
    @Volatile var running = false; private set
    @Volatile var status = str(R.string.ui_preparing); private set
    @Volatile private var socket: Socket? = null
    @Volatile private var active = false
    val isActive: Boolean get() = active
    private val wake = Semaphore(0)

    fun stop() {
        running = false
        runCatching { socket?.close() }
        wake.release()
    }

    @Synchronized fun start(context: Context, intent: Intent) {
        check(!active) { "Previous session is still closing" }
        val name = intent.getStringExtra("virtual_mic_socket") ?: error("Missing socket")
        require(name.matches(Regex("virtual-mic-[a-zA-Z0-9-]{8,64}")))
        running = true
        active = true
        meter = Meter()
        failureMessage = null
        status = str(R.string.ui_preparing_the_rvc_model)
        thread(name = "virtual-mic-rvc") { runSession(context, intent, name) }
    }

    private fun runSession(context: Context, intent: Intent, name: String) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        val base = context.getExternalFilesDir(null)!!
        val dir = File(base, "virtual_mic/${System.currentTimeMillis()}").apply { mkdirs() }
        val logFile = File(dir, "session.log")
        fun log(s: String) {
            Log.i("VirtualMic", s)
            if (intent.getBooleanExtra("bounded_logs", false) && logFile.length() > 1024 * 1024) {
                logFile.copyTo(File(dir, "session.previous.log"), overwrite = true); logFile.writeText("")
            }
            logFile.appendText(s + "\n")
        }
        var reader: Thread? = null
        var raw: WavWriter? = null
        var processed: WavWriter? = null
        var engine: Rvc? = null
        var normalEnd = false
        val captureFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        try {
            val prefs = context.getSharedPreferences("phone", Context.MODE_PRIVATE)
            val modelName = intent.getStringExtra("model") ?: prefs.getString("model", null)
            val models = File(base, "models").listFiles()?.filter { it.isDirectory && File(it, "model.enc.onnx").isFile } ?: emptyList()
            val model = models.firstOrNull { it.name == modelName } ?: models.firstOrNull() ?: error(str(R.string.ui_no_models_available))
            val j = File(model, "settings.json").takeIf { it.isFile }?.readText()?.let { JSONObject(it) } ?: JSONObject()
            fun n(key: String, value: Double) = j.optDouble(key, value)
            val settings = Rvc.Settings(n("pitch",0.0), n("formant",0.0), n("low_pitch_ratio",1.0), n("low_pitch_knee",120.0),
                n("high_pitch_ratio",1.0), n("high_pitch_knee",260.0), n("unvoiced_keep",1.0) != 0.0,
                n("intonation_expand",0.0) != 0.0, n("intonation_ratio",1.15), n("rms_mix_rate",0.0))
            val saved = LivePreferences.read(virtualMic = true, getInt = prefs::getInt)
            val block = intent.getIntExtra("block",saved.block).also { require(it in 15..50) }
            val inputDb = intent.getFloatExtra("input_gain_db",12f).also { require(it in 0f..36f) }
            val threshold = intent.getFloatExtra("threshold_db",saved.thresholdDb.toFloat()).also { require(it in -120f..-30f) }
            val outputDb = intent.getFloatExtra("gain_db",saved.gainDb.toFloat()).also { require(it in -12f..36f) }
            val params = saved.copy(block = block,
                context = intent.getIntExtra("context", prefs.getInt("live_context", LiveParams.defaultContext(block, saved.crossfade))), thresholdDb = threshold.toDouble(), gainDb = outputDb.toDouble(),
                skipQuiet = !intent.getBooleanExtra("no_quiet_skip",false))
            val dec = if (File(model,"model.dec.w8a16.onnx").isFile) "model.dec.w8a16.onnx" else "model.dec.onnx"
            val threads = intent.getIntExtra("threads",1).also { require(it in 1..8) }
            android.system.Os.setenv("ADSP_LIBRARY_PATH", "${context.applicationInfo.nativeLibraryDir};/system/lib/rfsa/adsp;/system/vendor/lib/rfsa/adsp;/dsp", true)
            log("CONFIG model=${model.name} block_ms=${block*10} context_ms=${params.context*10} window_ms=${params.window(null)*10} crossfade_ms=${params.crossfade*10} cushion_ms=${(params.cushion*1000).roundToInt()} threshold_db=${params.thresholdDb} input_gain_db=$inputDb output_gain_db=${params.gainDb} decoder=$dec threads=$threads bypass=false")
            engine = Rvc(File(base,"common"), model, threads, "qnn_htp", dec, "qnn_htp",
                live=Rvc.LiveShape(block,params.tail,settings.formant,params.window(null)))
            log("OPTIMIZATION fixed_formant=${settings.formant} decoder_frames=${engine.decFrames} skip_quiet=${params.skipQuiet}")
            val core = LiveCore(engine,params)
            var warmupMaxMs = 0.0
            repeat(3) {
                if (!running) throw InterruptedException()
                val begun=System.nanoTime()
                core.step(FloatArray(core.w48),settings,allowQuietSkip=false)
                warmupMaxMs=max(warmupMaxMs,(System.nanoTime()-begun)/1e6)
            }
            // Quiet blocks finish early; retain room for a full inference when speech begins.
            val bufferMs = (params.cushion * 1000).roundToInt() + 20
            val startDelayMs=(block*10+warmupMaxMs+bufferMs).roundToInt().coerceIn(block*10+bufferMs,1500)
            core.reset()
            if (!running) throw InterruptedException()
            val port = intent.getIntExtra("virtual_mic_port",0).also { require(it in 1024..65535) }
            val client = Socket()
            socket = client
            client.connect(InetSocketAddress("127.0.0.1",port),5000)
            client.tcpNoDelay = true
            client.soTimeout = 45000
            val input = DataInputStream(client.inputStream)
            val output = DataOutputStream(client.outputStream)
            output.writeInt(0x564d4932); output.writeUTF(name); output.writeInt(startDelayMs); output.flush()
            check(input.readInt() == 0x564d4932)
            log("CONNECTED directory=${dir.name} warmup_max_ms=$warmupMaxMs start_delay_ms=$startDelayMs")
            status = str(R.string.ui_ready_start_a_new_recording_in_the, model.name)
            val save = intent.getBooleanExtra("save_reference",false)
            if (save) { raw = WavWriter(File(dir,"input.wav")); processed = WavWriter(File(dir,"converted.wav")) }
            val ring = SampleRing(48000*8)
            val persistent = intent.getBooleanExtra("persistent_mic", false)
            val resetRequested = java.util.concurrent.atomic.AtomicBoolean(false)
            val gain = 10.0.pow(inputDb/20.0).toFloat()
            reader = thread(name="virtual-mic-input") {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
                try {
                    while (running) {
                        val count = input.readInt()
                        if (persistent && count == 0) continue
                        if (persistent && count == -1) { resetRequested.set(true); wake.release(); continue }
                        require(count in 1..48000)
                        val bytes = ByteArray(count*2); input.readFully(bytes)
                        val pcm = FloatArray(count) { i -> ((bytes[2*i].toInt() and 255) or (bytes[2*i+1].toInt() shl 8)).toShort()/32768f }
                        raw?.append(pcm)
                        for (i in pcm.indices) pcm[i] = LiveCore.softClip(pcm[i]*gain)
                        ring.write(pcm); wake.release()
                    }
                } catch (e: EOFException) { normalEnd = true }
                  catch (e: Throwable) { if (running) captureFailure.set(e) }
                finally { running = false; wake.release() }
            }
            var consumed = 0L
            var contextStart = 0L
            var lastHeartbeat = 0L
            var steps = 0
            var drops = 0
            var computeTotal = 0.0
            var computeMax = 0.0
            var inputPeak = 0f
            var outputPeak = 0f
            var quietSteps = 0
            var lastReport = 0L
            val csvHeader = "step,compute_ms,backlog_ms,input_peak,output_peak,clips,quiet_skip,prep_ms,hubert_ms,rmvpe_ms,enc_ms,dec_ms,other_ms,post_ms\n"
            val csvWriter = if (intent.getBooleanExtra("bounded_logs", false)) RotatingTextWriter(File(dir,"blocks.csv"), csvHeader)
                else File(dir,"blocks.csv").bufferedWriter().also { it.write(csvHeader) }
            csvWriter.use { csv ->
                while (running) {
                    wake.tryAcquire(100,TimeUnit.MILLISECONDS); wake.drainPermits()
                    if (resetRequested.getAndSet(false)) {
                        consumed = ring.total; contextStart = consumed; core.reset(); meter = Meter()
                        output.writeInt(-1); output.flush()
                        log("IDLE_RESET input_position=$consumed")
                    }
                    if (persistent && System.nanoTime() - lastHeartbeat >= 1_000_000_000L) {
                        output.writeInt(0); output.flush(); lastHeartbeat = System.nanoTime()
                    }
                    while (running && ring.total-consumed >= core.b48) {
                        if (resetRequested.get()) break
                        val have = ring.total
                        if (have-consumed > core.b48*3) { consumed = have-core.b48; core.reset(); drops++ }
                        consumed += core.b48
                        val x = ring.copy(consumed-core.w48,core.w48,contextStart)
                        val start = System.nanoTime()
                        val before = engine.timing.copyOf()
                        val y = core.step(x,settings)
                        val ms = (System.nanoTime()-start)/1e6
                        // A stalled inference must not replay an old block before jumping forward.
                        if (persistent && ring.total-consumed > core.b48*3) {
                            consumed = ring.total-core.b48; core.reset(); drops++
                            log("STALE_OUTPUT_DISCARDED compute_ms=$ms drops=$drops")
                            continue
                        }
                        meter = Meter(core.lastInPeak, core.lastRawPeak, ms, drops)
                        if (!running) break
                        val pcm = ByteArray(y.size*2)
                        for (i in y.indices) {
                            val v = (y[i]*32768).roundToInt().coerceIn(-32768,32767)
                            pcm[2*i]=v.toByte(); pcm[2*i+1]=(v shr 8).toByte()
                        }
                        val sourceStart=consumed-params.tail*480L+(if(core.lastSkippedQuiet) 0 else core.lastOffset)
                        output.writeInt(y.size); output.writeLong(sourceStart); output.write(pcm); output.flush()
                        processed?.append(y)
                        steps++; computeTotal+=ms; computeMax=max(computeMax,ms)
                        if (core.lastSkippedQuiet) quietSteps++
                        inputPeak=max(inputPeak,core.lastInPeak); outputPeak=max(outputPeak,core.lastRawPeak)
                        val backlog=(have-consumed)/48.0
                        val stage = engine.timing.indices.joinToString(",") { ((engine.timing[it]-before[it])*1000).toString() }
                        csv.write("$steps,$ms,$backlog,${core.lastInPeak},${core.lastRawPeak},${core.clips},${core.lastSkippedQuiet},${core.lastPrepMs},$stage,${core.lastPostMs}\n")
                        val now=System.nanoTime()
                        if (now-lastReport>1_000_000_000L) {
                            status=String.format(Locale.US,str(R.string.ui_virtual_mic_converting_ms_block_processing_ms),model.name,block*10,ms,backlog,drops)
                            log("STREAM steps=$steps compute_ms=$ms backlog_ms=$backlog in_peak=${core.lastInPeak} out_peak=${core.lastRawPeak} drops=$drops quiet_steps=$quietSteps")
                            lastReport=now
                        }
                    }
                }
            }
            captureFailure.get()?.let { throw it }
            log("SUMMARY steps=$steps mean_ms=${computeTotal/max(1,steps)} max_ms=$computeMax drops=$drops input_peak=$inputPeak output_peak=$outputPeak clips=${core.clips} quiet_steps=$quietSteps bridge_eof=$normalEnd")
            status=str(R.string.ui_virtual_mic_conversion_ended_processed_blocks_skips, steps, drops)
        } catch (e: Throwable) {
            if (running || captureFailure.get() != null) { failureMessage=e.message ?: str(R.string.ui_an_error_occurred_during_conversion); status=str(R.string.ui_conversion_error, e.message); Log.e("VirtualMic","failed",e); log("FAILED ${e.javaClass.simpleName}: ${e.message}") }
            else status=str(R.string.ui_virtual_mic_conversion_ended)
        } finally {
            stop()
            reader?.join(2000)
            runCatching { raw?.finish() }; runCatching { processed?.finish() }
            engine?.close()
            // The Shizuku controller owns its foreground lifetime and error reporting.
            if (!ShizukuMicController.active) context.stopService(Intent(context,LiveService::class.java))
            log("CLOSED")
            active = false
        }
    }
}
