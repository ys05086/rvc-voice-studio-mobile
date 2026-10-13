package com.voicestudio.phone

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Two pages: 녹음 (record with a live waveform, play and seek, a list of takes) and 변환 (model, pitch and
 * texture, advanced settings, original and converted waveforms with linked playheads, save and share).
 *
 * Files live in the app's own folder (Android/data/com.voicestudio.phone/files):
 *   common/hubert.onnx, common/rmvpe.onnx                         shared by every voice
 *   models/<name>/model.enc.onnx, model.dec.onnx, settings.json   one folder per voice ("불러오기" copies them in)
 *   recordings/<name>.wav                                            the 녹음 page's takes
 * settings.json uses the studio's realtime_settings.json keys and is updated as the sliders move.
 */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val player = Player()
    private val base by lazy { getExternalFilesDir(null)!! }
    private val commonDir by lazy { File(base, "common") }
    private val modelsDir by lazy { File(base, "models") }
    private val recordingsDir by lazy { File(base, "recordings") }
    private val prefs by lazy { getSharedPreferences("phone", MODE_PRIVATE) }
    private val computePolicy by lazy {
        ComputePolicy.from(
            if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MANUFACTURER else "",
            android.os.Build.HARDWARE, android.os.Build.BOARD,
            packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL))
    }
    /** The test hooks' extras (`adb shell am start ... --es convert ...`): only when files/debug_hooks exists, since
     *  the launcher activity takes intents from any app. Otherwise an empty intent, so every hook reads its default. */
    private val hooks: Intent by lazy { if (File(base, "debug_hooks").isFile) intent else Intent() }

    private val views = HashMap<Int, View>()
    @Suppress("UNCHECKED_CAST")
    private fun <T : View> f(id: Int): T = views.getOrPut(id) { findViewById(id) } as T

    // record page
    private var recorder: Recorder? = null
    private var take: FloatArray? = null
    private var takeName = ""
    // convert page
    private var models = listOf<File>()
    private var input: FloatArray? = null
    private var inputName = str(R.string.ui_record)
    private var inputTake: FloatArray? = null  // the 녹음 take the input came from; null for a picked file
    private var output: FloatArray? = null
    private var outputName = ""
    private var engine: Rvc? = null
    private var engineKey = ""
    private var ep = "qnn_htp"  // test hook override (--es ep); the app follows the NPU switch
    private var decFile = "model.dec.onnx"
    private var busy = false
    /** The shared models and at least one voice are there. */
    private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // where the DSP looks for QNN's NPU programs: the app's extracted libraries first
        android.system.Os.setenv("ADSP_LIBRARY_PATH",
            "${applicationInfo.nativeLibraryDir};/system/lib/rfsa/adsp;/system/vendor/lib/rfsa/adsp;/dsp", true)
        setContentView(R.layout.activity_main)
        f<TextView>(R.id.brand).text = SpannableStringBuilder("voice / studio").apply {
            setSpan(ForegroundColorSpan(getColor(R.color.accent)), 5, 8, 0)
        }
        f<View>(R.id.tab_record).setOnClickListener { showPage(RECORD) }
        f<View>(R.id.tab_convert).setOnClickListener { showPage(CONVERT) }
        f<View>(R.id.tab_live).setOnClickListener { showPage(LIVE) }
        f<View>(R.id.gear).setOnClickListener { showPage(if (page == SETTINGS) lastPage else SETTINGS) }
        f<Button>(R.id.settings_close).setOnClickListener { showPage(lastPage) }
        setupRecordPage()
        setupConvertPage()
        setupLivePage()
        setupSettingsPage()
        f<Button>(R.id.get_common).setOnClickListener { toggleCommonDownload() }
        player.onStateChange = { wave, playing -> playButtonFor(wave)?.text = if (playing) "⏸" else "▶" }
        showPage(prefs.getString("page", null) ?: if (prefs.getBoolean("record_page", true)) RECORD else CONVERT)
        refreshModels()
        refreshRecordings()
        if (!ready) showPage(SETTINGS)  // first run: models come first
        decFile = hooks.getStringExtra("dec") ?: decFile
        hooks.getStringExtra("virtual_mic_socket")?.let {
            startActivity(Intent(this, VirtualMicActivity::class.java).putExtras(hooks))
        }
        hooks.getStringExtra("convert")?.let { debugConvert(File(it)) }
        hooks.getStringExtra("stream")?.let { debugStream(File(it)) }
        if (hooks.getBooleanExtra("dspbench", false)) thread { StreamBench.dsp { android.util.Log.i("VoiceStudio", it) } }
        hooks.getStringExtra("bench")?.let { path ->  // test hook: --es bench <dec.onnx> --es ep cpu|qnn_htp|qnn_gpu
            thread {
                val msg = try {
                    val (first, rest) = Rvc.benchDecoder(path, hooks.getStringExtra("ep") ?: ep, hooks.getIntExtra("threads", 4))
                    String.format(Locale.US, "bench %s on %s: first run %.2f s, then %.3f s per run", File(path).name, ep, first, rest)
                } catch (e: Throwable) { "bench failed on $ep: ${e.message}" }
                android.util.Log.i("VoiceStudio", msg)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!busy) refreshModels()
        sharedLiveControls?.refresh()
        liveGainBar?.progress = livePrefs().getInt("live_gain_db", 12) + 12
        if (recorder == null) refreshRecordings()  // realtime recordings may have been saved from the notification
        if (LiveSession.engine != null) { ui.removeCallbacks(liveTicker); ui.post(liveTicker) }  // back from the notification
    }

    override fun onPause() {
        player.stop()
        super.onPause()
    }

    override fun onDestroy() {
        player.stop()
        engine?.close()
        ui.removeCallbacks(liveTicker)
        if (!LiveSession.running) LiveSession.release()  // a running session goes on (its notification stops it)
        super.onDestroy()
    }

    private var page = RECORD
    /** The tab 설정 returns to. */
    private var lastPage = RECORD

    private fun showPage(which: String) {
        // the newest take follows to 변환 by itself, unless a file was picked there since
        val t = take
        if (which == CONVERT && t != null && t !== input && (input == null || inputTake != null) && recorder == null) {
            setInput(t, takeName)
            inputTake = t
        }
        page = which
        if (which != SETTINGS) lastPage = which
        for ((name, pageId, tabId) in listOf(Triple(RECORD, R.id.page_record, R.id.tab_record),
                Triple(CONVERT, R.id.page_convert, R.id.tab_convert), Triple(LIVE, R.id.page_live, R.id.tab_live))) {
            f<View>(pageId).visibility = if (name == which) View.VISIBLE else View.GONE
            f<View>(tabId).isSelected = name == which
        }
        f<View>(R.id.page_settings).visibility = if (which == SETTINGS) View.VISIBLE else View.GONE
        if (which != SETTINGS) prefs.edit().putString("page", which).apply() else showCompute()
        f<ScrollView>(R.id.scroll).scrollTo(0, 0)
        if (which == LIVE) { loadLiveSliders(); showLive() }
    }

    private fun playButtonFor(wave: WaveformView?): Button? = when (wave?.id) {
        R.id.rec_wave -> f(R.id.rec_play)
        R.id.in_wave -> f(R.id.in_play)
        R.id.out_wave -> f(R.id.out_play)
        else -> null
    }

    // ---------------------------------------------------------------- record page

    private fun setupRecordPage() {
        val wave = f<WaveformView>(R.id.rec_wave)
        f<View>(R.id.record).setOnClickListener { toggleRecording() }
        f<Button>(R.id.rec_play).setOnClickListener { togglePlay(wave, take) }
        f<Button>(R.id.rec_rewind).setOnClickListener {
            wave.position = 0
            if (player.isPlaying(wave)) take?.let { player.play(wave, it, 0) }
            showRecTime()
        }
        wave.onSeek = { showRecTime() }
        wave.onSeekDone = { at -> if (player.isPlaying(wave)) take?.let { player.play(wave, it, at) }; showRecTime() }
        f<Button>(R.id.to_convert).setOnClickListener { showPage(CONVERT) }
        ticker()
    }

    /** One loop for the moving parts: live waveform, timers and playheads. */
    private fun ticker() {
        val r = recorder
        if (r != null) {
            f<WaveformView>(R.id.rec_wave).livePeaks = r.peaks()
            f<TextView>(R.id.rec_time).text = clock(r.samples)
        } else if (player.playing) {
            showRecTime(); showTimes()
        }
        ui.postDelayed({ ticker() }, 50)
    }

    private fun showRecTime() {
        val wave = f<WaveformView>(R.id.rec_wave)
        if (recorder == null && take != null) f<TextView>(R.id.rec_time).text = "${clock(wave.position)} / ${clock(wave.samples)}"
    }

    private fun toggleRecording() {
        val r = recorder
        if (r == null && (ShizukuMicController.active || VirtualMicSession.isActive)) { toast(str(R.string.ui_turn_off_the_virtual_microphone_first)); return }
        if (r == null && LiveSession.running) { toast(str(R.string.ui_stop_live_conversion_before_recording)); return }
        if (r == null) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), MIC)
                return
            }
            player.stop()
            val started = Recorder(this).also { it.start() }
            recorder = started
            f<View>(R.id.record_dot).setBackgroundResource(R.drawable.rec_stop)
            f<TextView>(R.id.rec_title).text = str(R.string.ui_recording)
            f<TextView>(R.id.rec_info).text = str(R.string.ui_input_tap_again_to_stop, started.sourceName)
            f<WaveformView>(R.id.rec_wave).livePeaks = FloatArray(0)
            listOf(R.id.rec_play, R.id.rec_rewind, R.id.to_convert).forEach { f<Button>(it).isEnabled = false }
        } else {
            val x = r.stop()
            recorder = null
            f<View>(R.id.record_dot).setBackgroundResource(R.drawable.rec_dot)
            if (x.size < Rvc.SR / 5) {
                f<TextView>(R.id.rec_info).text = str(R.string.ui_the_recording_is_too_short)
                f<WaveformView>(R.id.rec_wave).setAudio(take)
                if (take != null) listOf(R.id.rec_play, R.id.rec_rewind, R.id.to_convert).forEach { f<Button>(it).isEnabled = true }
                return
            }
            recordingsDir.mkdirs()
            val name = str(R.string.ui_recording_2) + SimpleDateFormat("MMdd_HHmmss", Locale.US).format(Date())
            AudioIo.writeWav(File(recordingsDir, "$name.wav"), x)
            loadTake(x, name)
            refreshRecordings()
        }
    }

    private fun loadTake(x: FloatArray, name: String) {
        player.stop()
        take = x
        takeName = name
        f<WaveformView>(R.id.rec_wave).setAudio(x)
        f<TextView>(R.id.rec_title).text = name
        f<TextView>(R.id.rec_info).text = str(R.string.ui_tap_or_drag_the_waveform_to_seek)
        listOf(R.id.rec_play, R.id.rec_rewind, R.id.to_convert).forEach { f<Button>(it).isEnabled = true }
        showRecTime()
    }

    private fun refreshRecordings() {
        val list = f<LinearLayout>(R.id.rec_list)
        list.removeAllViews()
        val files = recordingsDir.listFiles { file -> file.extension == "wav" }?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (files.isEmpty()) {
            list.addView(TextView(this).apply { text = str(R.string.ui_no_recordings_yet); textSize = 12f; setTextColor(getColor(R.color.muted)); setPadding(0, dp(6), 0, dp(6)) })
            return
        }
        for (file in files) {
            val samples = ((file.length() - 44) / 2).toInt()
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(10), dp(4), dp(10))
                isClickable = true
                setBackgroundResource(R.drawable.btn_soft)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            }
            row.addView(TextView(this).apply {
                text = file.nameWithoutExtension; textSize = 14f; setTextColor(getColor(R.color.text)); setPadding(dp(8), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = clock(samples).substringBefore('.'); textSize = 12f; setPadding(0, 0, dp(8), 0)
                typeface = Typeface.MONOSPACE; setTextColor(getColor(R.color.muted))
            })
            row.setOnClickListener {
                runCatching { loadTake(AudioIo.readWav(file), file.nameWithoutExtension) }.onFailure { toast(str(R.string.ui_could_not_open, it.message)) }
            }
            row.setOnLongClickListener {
                AlertDialog.Builder(this).setTitle(file.nameWithoutExtension).setMessage(str(R.string.ui_delete_this_recording))
                    .setPositiveButton(str(R.string.ui_delete)) { _, _ -> file.delete(); refreshRecordings() }
                    .setNegativeButton(str(R.string.ui_cancel), null).show()
                true
            }
            list.addView(row)
        }
    }

    // ---------------------------------------------------------------- convert page

    private fun setupConvertPage() {
        f<SeekBar>(R.id.pitch).setOnSeekBarChangeListener(slider { p, done ->
            // a shift from the speaker's own voice, as the studio's 음높이: +9 = nine semitones up
            f<TextView>(R.id.pitch_value).text = if (p == 24) str(R.string.ui_semitones) else String.format(Locale.US, str(R.string.ui_semitones_2), p - 24)
            if (done) saveSetting("pitch", (p - 24).toDouble())
        })
        f<SeekBar>(R.id.formant).setOnSeekBarChangeListener(slider { p, done ->
            f<TextView>(R.id.formant_value).text = String.format(Locale.US, "%.1f", (p - 20) / 10.0)
            if (done) saveSetting("formant", (p - 20) / 10.0)
        })
        f<Spinner>(R.id.model).onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, i: Int, id: Long) {
                prefs.edit().putString("model", models.getOrNull(i)?.name).apply()
                loadSliders()
                if (page == LIVE) { loadLiveSliders(); showLive() }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        f<Button>(R.id.import_model).setOnClickListener { chooseImport() }
        f<Button>(R.id.pick).setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*"), PICK_AUDIO)
        }
        val inWave = f<WaveformView>(R.id.in_wave)
        val outWave = f<WaveformView>(R.id.out_wave)
        // the two playheads move together, so the same moment can be heard before and after
        inWave.onSeek = { at -> outWave.position = at; showTimes() }
        outWave.onSeek = { at -> inWave.position = at; showTimes() }
        inWave.onSeekDone = { at -> outWave.position = at; if (player.isPlaying(inWave)) input?.let { player.play(inWave, it, at) }; showTimes() }
        outWave.onSeekDone = { at -> inWave.position = at; if (player.isPlaying(outWave)) output?.let { player.play(outWave, it, at) }; showTimes() }
        f<Button>(R.id.in_play).setOnClickListener { togglePlay(inWave, input, outWave) }
        f<Button>(R.id.out_play).setOnClickListener { togglePlay(outWave, output, inWave) }
        f<Button>(R.id.convert).setOnClickListener { runConversion() }
        f<Button>(R.id.save).setOnClickListener { save()?.let { toast(str(R.string.ui_saved_to_music_voicestudio)) } }
        f<Button>(R.id.share).setOnClickListener { save()?.let { share(it) } }
        buildAdvanced()
    }

    private fun slider(changed: (Int, Boolean) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) = changed(p, false)
        override fun onStartTrackingTouch(s: SeekBar) {}
        override fun onStopTrackingTouch(s: SeekBar) = changed(s.progress, true)
    }

    /** Play [wave]'s clip from its playhead; [partner] (the other waveform) follows the same place. */
    private fun togglePlay(wave: WaveformView, clip: FloatArray?, partner: WaveformView? = null) {
        val x = clip ?: return
        if (player.isPlaying(wave)) { player.stop(); return }
        val from = if (wave.position >= x.size - 1) 0 else wave.position
        player.play(wave, x, from)
        partner?.position = from
    }

    private fun showTimes() {
        val inWave = f<WaveformView>(R.id.in_wave)
        val outWave = f<WaveformView>(R.id.out_wave)
        if (player.isPlaying(inWave)) outWave.position = inWave.position
        if (player.isPlaying(outWave)) inWave.position = outWave.position
        if (input != null) f<TextView>(R.id.in_time).text = "${clock(inWave.position)} / ${clock(inWave.samples)}"
        if (output != null) f<TextView>(R.id.out_time).text = "${clock(outWave.position)} / ${clock(outWave.samples)}"
    }

    // advanced settings, built from a spec list like the studio's web page
    private data class Spec(val key: String, val label: String, val min: Double, val max: Double, val step: Double,
                            val unit: String, val default: Double, val minLabel: String, val maxLabel: String)
    private val specs = listOf(
        Spec("low_pitch_ratio", str(R.string.ui_low_pitch_protection_strength), 1.0, 3.0, 0.1, "", 1.0, str(R.string.ui_off), "3"),
        Spec("low_pitch_knee", str(R.string.ui_low_pitch_protection_threshold), 60.0, 200.0, 5.0, "Hz", 120.0, "60", "200"),
        Spec("high_pitch_ratio", str(R.string.ui_high_pitch_protection_strength), 1.0, 3.0, 0.1, "", 1.0, str(R.string.ui_off), "3"),
        Spec("high_pitch_knee", str(R.string.ui_high_pitch_protection_threshold), 200.0, 600.0, 10.0, "Hz", 260.0, "200", "600"),
        Spec("intonation_ratio", str(R.string.ui_intonation_expansion_strength), 1.0, 1.5, 0.05, str(R.string.ui_), 1.15, str(R.string.ui__2), str(R.string.ui__3)),
        Spec("rms_mix_rate", str(R.string.ui_volume_envelope), 0.0, 1.0, 0.05, "", 0.0, str(R.string.ui_follow_input), str(R.string.ui_follow_conversion)),
    )
    private val switchSpecs = listOf("unvoiced_keep" to str(R.string.ui_preserve_breath_and_consonants), "intonation_expand" to str(R.string.ui_expand_intonation))
    private val advSliders = HashMap<String, SeekBar>()
    private val advSwitches = HashMap<String, Switch>()
    private var loadingSliders = false

    private fun buildAdvanced() {
        val page = f<LinearLayout>(R.id.page_convert)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        val header = TextView(this).apply { textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(getColor(R.color.text)) }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        fun setOpen(open: Boolean) {
            body.visibility = if (open) View.VISIBLE else View.GONE
            header.text = if (open) str(R.string.ui_advanced_settings) else str(R.string.ui_advanced_settings_2)
            prefs.edit().putBoolean("advanced_open", open).apply()
        }
        header.setOnClickListener { setOpen(body.visibility != View.VISIBLE) }
        card.addView(header)
        card.addView(body)
        for ((key, label) in switchSpecs) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, 0) }
            row.addView(TextView(this).apply { text = label; textSize = 13f; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
            val sw = Switch(this)
            sw.setOnCheckedChangeListener { _, on -> saveSetting(key, if (on) 1.0 else 0.0) }
            row.addView(sw)
            advSwitches[key] = sw
            body.addView(row)
        }
        for (spec in specs) {
            val value = TextView(this).apply { textSize = 13f; typeface = Typeface.MONOSPACE; setTextColor(getColor(R.color.accent_strong)) }
            val bar = sliderRow(body, spec.label, value, spec.minLabel, spec.maxLabel, ((spec.max - spec.min) / spec.step).roundToInt())
            bar.setOnSeekBarChangeListener(slider { p, done ->
                val x = spec.min + p * spec.step
                value.text = format(x, spec.step) + if (spec.unit.isEmpty()) "" else " ${spec.unit}"
                if (done) saveSetting(spec.key, x)
            })
            advSliders[spec.key] = bar
        }
        body.addView(TextView(this).apply {
            text = str(R.string.ui_for_settings_marked_off_values_further_from)
            textSize = 11f; setTextColor(getColor(R.color.muted)); setPadding(0, dp(10), 0, 0)
        })
        page.addView(card, page.indexOfChild(f(R.id.convert)))
        setOpen(prefs.getBoolean("advanced_open", false))
    }

    private fun sliderRow(parent: LinearLayout, label: String, value: TextView, minLabel: String, maxLabel: String, steps: Int): SeekBar {
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, 0) }
        top.addView(TextView(this).apply { text = label; textSize = 13f; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        top.addView(value)
        val bar = SeekBar(this, null, 0, R.style.Slider).apply {
            max = steps
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30))
        }
        val scale = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, t) in listOf(minLabel, maxLabel).withIndex()) scale.addView(TextView(this).apply {
            text = t; textSize = 10f; setTextColor(getColor(R.color.muted)); typeface = Typeface.MONOSPACE
            gravity = if (i == 0) Gravity.START else Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        parent.addView(top); parent.addView(bar); parent.addView(scale)
        return bar
    }

    private fun format(x: Double, step: Double) = when {
        step >= 1 -> x.roundToInt().toString()
        step >= 0.1 -> String.format(Locale.US, "%.1f", x)
        else -> String.format(Locale.US, "%.2f", x)
    }

    private fun currentModel(): File? = models.getOrNull(f<Spinner>(R.id.model).selectedItemPosition)

    private fun settingsJson(dir: File): JSONObject =
        File(dir, "settings.json").let { if (it.isFile) runCatching { JSONObject(it.readText()) }.getOrDefault(JSONObject()) else JSONObject() }

    private fun saveSetting(key: String, value: Double) {
        if (loadingSliders) return
        val dir = currentModel() ?: return
        File(dir, "settings.json").writeText(settingsJson(dir).put(key, value).toString(1))
        LiveSession.engine?.update(settingsOf(dir))  // realtime follows from its next step
    }

    /** Sliders and switches from the selected model's settings.json. */
    private fun loadSliders() {
        val dir = currentModel() ?: return
        val j = settingsJson(dir)
        fun num(k: String, d: Double) = if (j.has(k)) j.optDouble(k, d) else d
        f<TextView>(R.id.voice_hint).text = str(R.string.ui_saved_to, dir.name)
        loadingSliders = true
        f<SeekBar>(R.id.pitch).progress = (num("pitch", 0.0) + 24).roundToInt()
        f<SeekBar>(R.id.formant).progress = ((num("formant", 0.0) + 2) * 10).roundToInt()
        for (spec in specs) advSliders[spec.key]?.progress = ((num(spec.key, spec.default) - spec.min) / spec.step).roundToInt()
        advSwitches["unvoiced_keep"]?.isChecked = num("unvoiced_keep", 1.0) != 0.0
        advSwitches["intonation_expand"]?.isChecked = num("intonation_expand", 0.0) != 0.0
        loadingSliders = false
    }

    private fun settingsOf(dir: File): Rvc.Settings {
        val j = settingsJson(dir)
        val d = Rvc.Settings()
        fun num(k: String, def: Double) = if (j.has(k)) j.optDouble(k, def) else def
        return Rvc.Settings(
            pitch = num("pitch", d.pitch), formant = num("formant", d.formant),
            lowRatio = num("low_pitch_ratio", d.lowRatio), lowKnee = num("low_pitch_knee", d.lowKnee),
            highRatio = num("high_pitch_ratio", d.highRatio), highKnee = num("high_pitch_knee", d.highKnee),
            unvoicedKeep = num("unvoiced_keep", 1.0) != 0.0,
            expand = num("intonation_expand", 0.0) != 0.0, expandRatio = num("intonation_ratio", d.expandRatio),
            rmsMixRate = num("rms_mix_rate", d.rmsMixRate),
        )
    }

    private fun refreshModels() {
        commonDir.mkdirs(); modelsDir.mkdirs()
        if (!busy && !LiveSession.running) sortLooseFiles().takeIf { it.isNotEmpty() }?.let { toast(str(R.string.ui_imported) + it.joinToString(", ")) }
        val commonOk = File(commonDir, "hubert.onnx").isFile && File(commonDir, "rmvpe.onnx").isFile
        models = modelsDir.listFiles { file -> file.isDirectory && File(file, "model.enc.onnx").isFile && File(file, "model.dec.onnx").isFile }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
        val spinner = f<Spinner>(R.id.model)
        val keep = hooks.getStringExtra("model") ?: prefs.getString("model", null)  // test hook: --es model NAME
        spinner.adapter = ArrayAdapter(this, R.layout.spinner_item, models.map { it.name }).apply { setDropDownViewResource(R.layout.spinner_item) }
        models.indexOfFirst { it.name == keep }.takeIf { it >= 0 }?.let { spinner.setSelection(it) }
        ready = commonOk && models.isNotEmpty()
        val status = f<TextView>(R.id.status)
        status.text = if (ready) str(R.string.ui_ready) else str(R.string.ui_models_needed)
        status.setBackgroundResource(if (ready) R.drawable.chip else R.drawable.chip_warn)
        status.setTextColor(getColor(if (ready) R.color.green else R.color.rec))
        // also offered when only the NPU's HuBERT is missing (realtime is ~90 ms a step faster with it)
        val missing = CommonModels.items.filter { !File(commonDir, it.name).isFile }
        f<View>(R.id.common_row).visibility = if (missing.isEmpty() && !downloading) View.GONE else View.VISIBLE
        f<TextView>(R.id.common_text).text = if (commonOk) String.format(Locale.US,
            str(R.string.ui_hubert_for_npu_faster_live_conversion_mb), missing.sumOf { it.bytes } / 1e6)
            else String.format(Locale.US, str(R.string.ui_shared_models_hubert_and_rmvpe_one_time), missing.sumOf { it.bytes } / 1e6)
        f<TextView>(R.id.help).text = if (ready) str(R.string.ui_to_add_a_voice_use_import_to) else buildString {
            append(str(R.string.ui_use_import_to_select_a_model_folder))
            if (!commonOk) append(str(R.string.ui_shared_models_hubert_onnx_and_rmvpe_onnx))
            if (models.isEmpty()) append(str(R.string.ui_voice_model_name_enc_onnx_name_dec))
        }
        loadSliders()
        updateConvertButton()
        if (page == LIVE) { loadLiveSliders(); showLive() }
    }

    @Volatile private var downloadStop = false
    private var downloading = false

    /** The shared models from the release page; a second press stops. Voice models stay the user's own. */
    private fun toggleCommonDownload() {
        if (downloading) {
            downloadStop = true
            return
        }
        downloading = true
        downloadStop = false
        val button = f<Button>(R.id.get_common)
        val bar = f<ProgressBar>(R.id.common_progress)
        button.text = str(R.string.ui_stop_2)
        bar.progress = 0
        bar.visibility = View.VISIBLE
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        thread {
            var shown = -1
            val error = try {
                CommonModels.download(commonDir, { p ->
                    val step = (p * 1000).toInt()
                    if (step != shown) {
                        shown = step
                        runOnUiThread { bar.progress = step }
                    }
                }, { downloadStop })
                null
            } catch (e: Throwable) {
                e
            }
            runOnUiThread {
                downloading = false
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                button.text = str(R.string.ui_download)
                bar.visibility = View.GONE
                refreshModels()
                when {
                    error == null -> toast(str(R.string.ui_shared_models_downloaded))
                    downloadStop -> toast(str(R.string.ui_download_stopped_tap_again_to_download_the))
                    else -> toast(str(R.string.ui_could_not_download_shared_models_copy_hubert, error.message, commonDir.path) +
                        str(R.string.ui_you_can_create_these_files_with_the))
                }
            }
        }
    }

    private fun updateConvertButton() {
        f<Button>(R.id.convert).isEnabled = input != null && models.isNotEmpty() && !busy && !LiveSession.running
    }

    private fun setInput(x: FloatArray, name: String) {
        player.stop()
        input = x
        inputName = name.replace(Regex("[\\\\/:*?\"<>| ]+"), "_")
        f<WaveformView>(R.id.in_wave).setAudio(x)
        f<TextView>(R.id.in_title).text = str(R.string.ui_original, inputName)
        f<TextView>(R.id.in_info).text = ""
        f<Button>(R.id.in_play).isEnabled = true
        f<View>(R.id.result).visibility = View.GONE
        output = null
        showTimes()
        updateConvertButton()
    }

    private fun runConversion(done: ((FloatArray, Double, Double) -> Unit)? = null) {
        if (ShizukuMicController.active || VirtualMicSession.isActive) { toast(str(R.string.ui_turn_off_the_virtual_microphone_first)); return }
        val x = input ?: return
        val dir = currentModel() ?: return
        val settings = settingsOf(dir)
        val normalize = f<Switch>(R.id.normalize).isChecked
        val threads = hooks.getIntExtra("threads", prefs.getInt("threads", 4))
        val devices = fileDevices(dir)
        val hooked = hooks.getStringExtra("ep") != null || hooks.getStringExtra("front") != null
        val ep = hooks.getStringExtra("ep") ?: devices.ep
        val front = hooks.getStringExtra("front") ?: devices.front
        val dec = if (hooked || decFile != "model.dec.onnx") decFile else devices.file
        val convert = f<Button>(R.id.convert)
        val progress = f<ProgressBar>(R.id.progress)
        player.stop()
        busy = true
        updateConvertButton()
        convert.text = str(R.string.ui_converting)
        progress.visibility = View.VISIBLE
        progress.progress = 0
        f<View>(R.id.result).visibility = View.GONE
        thread {
            try {
                val started = System.currentTimeMillis()
                val seed = hooks.getIntExtra("seed", 0)  // test hook: --ei seed N (another draw of the synthesizer's noise)
                val fixedWindows = hooks.getBooleanExtra("fixed_windows", false)  // test hook: the NPU's windows on the CPU
                val key = "${dir.path}|$threads|$ep|$dec|$front|${hooks.getIntExtra("chunk_s", 3)}|$seed|$fixedWindows"
                if (engineKey != key) {
                    val firstNpu = (ep.startsWith("qnn") && !File(dir, dec.removeSuffix(".onnx") + ".$ep.onnx").isFile) ||
                        (front.startsWith("qnn") && commonDir.listFiles { f -> f.name.startsWith("hubert.") && f.name.contains(".$front.") }.isNullOrEmpty())
                    ui.post { convert.text = if (firstNpu) str(R.string.ui_preparing_npu_the_first_run_may_take) else str(R.string.ui_loading_model) }
                    engine?.close()
                    engine = null
                    LiveSession.release()
                    val chunk = hooks.getIntExtra("chunk_s", 3) * Rvc.SR16  // test hook: --ei chunk_s N
                    engine = Rvc(commonDir, dir, threads, ep, dec, front, chunk, seed = seed.toLong(), cpuFixedWindows = fixedWindows, allowFallback = !hooked)
                    engineKey = key
                }
                val loaded = System.currentTimeMillis()
                ui.post { convert.text = str(R.string.ui_converting) }
                var y = engine!!.convert(x, settings) { d, total -> ui.post { progress.max = total; progress.progress = d } }
                if (normalize) y = AudioIo.peakNormalize(y)
                val took = (System.currentTimeMillis() - loaded) / 1000.0
                val load = (loaded - started) / 1000.0
                done?.invoke(y, load, took)
                ui.post { showResult(y, dir.name, x.size, took, load) }
            } catch (e: Throwable) {
                android.util.Log.e("VoiceStudio", "conversion failed", e)
                ui.post { toast(str(R.string.ui_conversion_failed, e.message)) }
            } finally {
                ui.post { busy = false; convert.text = str(R.string.ui_convert); progress.visibility = View.GONE; updateConvertButton() }
            }
        }
    }

    private fun showResult(y: FloatArray, model: String, samples: Int, took: Double, load: Double) {
        output = y
        outputName = "${inputName}_${model}_" + SimpleDateFormat("MMdd_HHmm", Locale.US).format(Date())
        val outWave = f<WaveformView>(R.id.out_wave)
        outWave.setAudio(y)
        outWave.position = f<WaveformView>(R.id.in_wave).position
        f<TextView>(R.id.out_title).text = str(R.string.ui_converted, model)
        f<TextView>(R.id.out_info).text = String.format(Locale.US, str(R.string.ui_converted_s_of_audio_in_s), samples.toFloat() / Rvc.SR, took,
            str(R.string.ui_front_end_decoder, engine?.frontDevice ?: "CPU", engine?.decoderDevice ?: "CPU") +
                (engine?.fallbackNotice?.let { " · $it" } ?: ""),
            if (load > 0.5) String.format(Locale.US, str(R.string.ui_model_loading_s), load) else "")
        f<View>(R.id.result).visibility = View.VISIBLE
        showTimes()
        val scroll = f<ScrollView>(R.id.scroll)
        scroll.post { scroll.smoothScrollTo(0, f<View>(R.id.result).bottom) }
    }

    /** Copies the result into Music/VoiceStudio (MediaStore: no storage permission needed). */
    private fun save(): Uri? {
        val y = output ?: return null
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$outputName.wav")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/VoiceStudio")
        }
        val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        contentResolver.openOutputStream(uri)?.use { AudioIo.writeWav(it, y) }
        return uri
    }

    private fun share(uri: Uri) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("audio/wav")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), str(R.string.ui_share)))
    }

    // ---------------------------------------------------------------- model import

    private fun chooseImport() {
        AlertDialog.Builder(this).setTitle(str(R.string.ui_import_model))
            .setItems(arrayOf(str(R.string.ui_choose_folder), str(R.string.ui_choose_zip_file))) { _, which ->
                if (which == 0) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_FOLDER)
                else startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK_ZIP)
            }.show()
    }

    /** Where an imported file goes, by its name: <name>.enc.onnx or model.enc.onnx and the like. */
    private fun importTarget(name: String, model: File?): File? {
        val n = name.lowercase()
        return when {
            n == "hubert.onnx" || n == "rmvpe.onnx" || n == "hubert.htp.onnx" -> File(commonDir, n)
            model == null -> null
            n == "model.json" || n.endsWith(".model.json") -> File(model, "model.json")
            n.endsWith(".enc.onnx") -> File(model, "model.enc.onnx")
            n.endsWith(".dec.w8a16.onnx") -> File(model, W8A16)  // the NPU's integer decoder (converter, calib/)
            n.endsWith(".dec.onnx") -> File(model, "model.dec.onnx")
            n.endsWith(".dec.ncnn.param") -> File(model, "model.dec.ncnn.param")
            n.endsWith(".dec.ncnn.bin") -> File(model, "model.dec.ncnn.bin")
            n.endsWith(".dec_a.onnx") -> File(model, "model.dec_a.onnx")  // the split decoder (iPhone for now)
            n.endsWith(".dec_b.onnx") -> File(model, "model.dec_b.onnx")
            n == "settings.json" || n.endsWith(".settings.json") || n == "realtime_settings.json" -> File(model, "settings.json")
            else -> null
        }
    }

    private class Source(val name: String, val open: () -> InputStream)

    /** Model files put loose into the app folder (adb push, the PC converter's 폰으로 보내기): <voice>.dec.w8a16.onnx and
     *  the other parts move into models/<voice>/ (a new voice when its enc and dec came together; its <voice>.settings.json
     *  seeds the new folder only), hubert.htp.onnx and the other shared ones into common/,
     *  as the iPhone app sorts what lands in its Documents. adb can write the app folder but not the voice folders
     *  the app made. A replaced part takes the NPU's compiled copies with it. Returns what moved, for a message. */
    private fun sortLooseFiles(): List<String> {
        val moved = ArrayList<String>()
        val voices = (modelsDir.listFiles { f -> f.isDirectory }?.associateBy { it.name.lowercase() } ?: emptyMap()).toMutableMap()
        val loose = base.listFiles { f -> f.isFile && (f.name.endsWith(".onnx") || ".ncnn." in f.name || f.name.endsWith(".settings.json") || f.name.endsWith(".model.json")) }
            ?: return moved
        // a voice the phone does not have yet: both of its main parts arrived
        val names = loose.map { it.name }.toSet()
        for (f in loose) {
            val voice = f.name.substringBefore('.')  // as the parts are matched below (no dots in loose voice names)
            if (f.name == "$voice.enc.onnx" && "$voice.dec.onnx" in names && voice.lowercase() !in voices && voice.isNotBlank())
                voices[voice.lowercase()] = File(modelsDir, voice).apply { mkdirs() }
        }
        for ((voice, dir) in voices) {
            val incoming = loose.filter { it.name.substringBefore('.').lowercase() == voice }
                .mapNotNull { importTarget(it.name, dir)?.name }.toSet()
            ModelSpec.obsoleteParts(incoming).forEach { File(dir, it).delete() }
        }
        for (file in loose) {
            val shared = file.name.lowercase() in setOf("hubert.onnx", "rmvpe.onnx", "hubert.htp.onnx")
            val dir = if (shared) commonDir else voices[file.name.substringBefore('.').lowercase()] ?: continue
            val target = importTarget(file.name, dir) ?: continue
            if (target.name == "settings.json" && target.isFile) { file.delete(); continue }  // never over a voice's tuned settings
            val stem = target.name.removeSuffix(".onnx")
            dir.listFiles { f -> f.name.startsWith("$stem.") && ".qnn_" in f.name }?.forEach { it.delete() }
            target.delete()
            if (!file.renameTo(target)) {
                runCatching { file.copyTo(target, overwrite = true); file.delete() }.onFailure { continue }
            }
            moved += if (shared) target.name else "${dir.name} ← ${file.name.substringAfter('.')}"
        }
        if (moved.isNotEmpty()) { engine?.close(); engine = null; engineKey = ""; LiveSession.release() }
        return moved
    }

    private fun importFolder(tree: Uri) {
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        val folderName = query(DocumentsContract.buildDocumentUriUsingTree(tree, treeId)) ?: str(R.string.ui_model)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
        val sources = ArrayList<Source>()
        contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                sources.add(Source(c.getString(1)) { contentResolver.openInputStream(uri)!! })
            }
        }
        askNameAndCopy(folderName, sources)
    }

    private fun importZip(uri: Uri) {
        val zipName = (query(uri) ?: str(R.string.ui_model)).removeSuffix(".zip").removeSuffix(".vsmodel")
        val names = ArrayList<String>()
        ZipInputStream(contentResolver.openInputStream(uri)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory) names.add(e.name.substringAfterLast('/'))
            }
        }
        // a fresh pass per file keeps memory flat (the shared models are hundreds of MB)
        val sources = names.map { wanted ->
            Source(wanted) {
                val z = ZipInputStream(contentResolver.openInputStream(uri))
                while (true) {
                    val e = z.nextEntry ?: throw IllegalStateException(str(R.string.ui_not_found, wanted))
                    if (!e.isDirectory && e.name.substringAfterLast('/') == wanted) break
                }
                z
            }
        }
        askNameAndCopy(zipName, sources)
    }

    private fun query(uri: Uri): String? = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }

    private fun askNameAndCopy(suggested: String, sources: List<Source>) {
        val hasModel = sources.any { importTarget(it.name, modelsDir) != null && importTarget(it.name, null) == null }
        val hasCommon = sources.any { importTarget(it.name, null) != null }
        if (!hasModel && !hasCommon) {
            toast(str(R.string.ui_no_voice_model_enc_onnx_dec_onnx))
            return
        }
        if (!hasModel) { copyIn(null, sources); return }
        val field = EditText(this).apply { setText(suggested.replace(Regex("[\\\\/:*?\"<>|]+"), "_")); setSingleLine() }
        AlertDialog.Builder(this).setTitle(str(R.string.ui_model_name)).setView(field)
            .setPositiveButton(str(R.string.ui_import)) { _, _ ->
                val name = field.text.toString().trim().replace(Regex("[\\\\/:*?\"<>|]+"), "_").ifEmpty { str(R.string.ui_model) }
                copyIn(File(modelsDir, name), sources)
            }.setNegativeButton(str(R.string.ui_cancel), null).show()
    }

    private fun copyIn(model: File?, sources: List<Source>) {
        val help = f<TextView>(R.id.help)
        busy = true
        updateConvertButton()
        thread {
            try {
                model?.mkdirs()
                // a new decoder makes the NPU's compiled copy stale
                // (the GPU decoder's files only when new ones come: importing just the NPU decoder keeps them)
                val newNcnn = sources.any { it.name.lowercase().contains(".dec.ncnn.") }
                model?.listFiles { file -> ".qnn_" in file.name || (newNcnn && file.name.startsWith("model.dec.ncnn")) }?.forEach { it.delete() }
                val todo = sources.mapNotNull { s -> importTarget(s.name, model)?.let { s to it } }
                if (model != null) ModelSpec.obsoleteParts(todo.map { it.second.name }.toSet())
                    .forEach { File(model, it).delete() }
                for ((i, pair) in todo.withIndex()) {
                    val (source, target) = pair
                    ui.post { help.text = str(R.string.ui_importing, i + 1, todo.size, source.name) }
                    val tmp = File(target.path + ".part")
                    source.open().use { inp -> tmp.outputStream().use { inp.copyTo(it, 1 shl 20) } }
                    target.delete()
                    tmp.renameTo(target)
                }
                if (model != null && !File(model, "model.enc.onnx").isFile) throw IllegalStateException(str(R.string.ui_model_enc_onnx_is_missing))
                if (model != null) ModelSpec.load(model)
                ui.post {
                    if (model != null) prefs.edit().putString("model", model.name).apply()
                    if (model != null && engineKey.startsWith(model.path)) { engine?.close(); engine = null; engineKey = "" }
                    toast(if (model != null) str(R.string.ui_imported_model, model.name) else str(R.string.ui_shared_models_imported))
                }
            } catch (e: Exception) {
                ui.post { toast(str(R.string.ui_import_failed, e.message)) }
            } finally {
                ui.post { busy = false; refreshModels() }
            }
        }
    }

    // ---------------------------------------------------------------- settings

    /** Shared file/live preference. Device hints avoid unsupported accelerators; Rvc verifies by running them. */
    private class Devices(val front: String, val ep: String, val file: String, val label: String)

    private fun fileDevices(dir: File): Devices {
        val hasInt = File(dir, W8A16).isFile
        val hasGpu = computePolicy.tryGpu && File(dir, "model.dec.ncnn.param").isFile && File(dir, "model.dec.ncnn.bin").isFile
        val (requestedFront, requestedDec) = when (prefs.getString("compute", "recommended")) {
            "full32" -> "cpu" to "gpu"
            "custom" -> prefs.getString("custom_front", "npu")!! to prefs.getString("custom_dec", "npu_int")!!
            else -> (if (computePolicy.tryNpu) "npu" else "cpu") to computePolicy.decoder(hasInt, hasGpu)
        }
        val front = if (requestedFront == "npu" && !computePolicy.tryNpu) "cpu" else requestedFront
        val dec = when {
            requestedDec.startsWith("npu") && !computePolicy.tryNpu -> if (hasGpu) "gpu" else "cpu"
            requestedDec == "npu_int" && !hasInt -> if (hasGpu) "gpu" else "cpu"
            requestedDec == "gpu" && !hasGpu -> "cpu"
            else -> requestedDec
        }
        val f = if (front == "npu") "qnn_htp" else "cpu"
        val frontLabel = str(R.string.ui_front_end) + if (front == "npu") "NPU" else "CPU"
        return when {
            dec == "npu_int" && hasInt -> Devices(f, "qnn_htp", W8A16, str(R.string.ui_integer_npu_decoder_bit, frontLabel))
            dec == "npu16" -> Devices(f, "qnn_htp", "model.dec.onnx", str(R.string.ui_bit_npu_decoder_may_sound_rough, frontLabel))
            dec == "cpu" -> Devices(f, "cpu", "model.dec.onnx", str(R.string.ui_bit_cpu_decoder, frontLabel))
            else -> Devices(f, "ncnn_gpu", "model.dec.onnx", str(R.string.ui_bit_gpu_decoder, frontLabel) +
                if (dec == "npu_int") str(R.string.ui_no_integer_npu_decoder_file_for_this) else "")
        }
    }

    private fun liveDevices(dir: File): Devices {
        val selected = fileDevices(dir)
        return if (ModelLivePreferences(prefs, dir.name).getBoolean("live_split", false) && computePolicy.tryNpu && selected.front == "qnn_htp")
            Devices(selected.front, "split", "model.dec.onnx", str(R.string.ui_front_end_npu_decoder_npu_cpu))
        else selected
    }

    private val computeViews = HashMap<String, View>()

    /** A row of choices that looks like the page tabs. */
    private fun segmented(parent: LinearLayout, items: List<Pair<String, String>>, selected: () -> String, pick: (String) -> Unit): List<TextView> {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.tabbar)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(10) }
        }
        val views = items.map { (key, label) ->
            TextView(this).apply {
                text = label; textSize = 13f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
                setTextColor(getColorStateList(R.color.tab_text)); setBackgroundResource(R.drawable.tab)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                tag = key
                setOnClickListener { pick(key); showCompute() }
            }.also { bar.addView(it) }
        }
        parent.addView(bar)
        computeViews["seg_" + items.joinToString { it.first }] = bar
        return views.onEach { v -> v.isSelected = v.tag == selected() }
    }

    private val segments = ArrayList<Pair<List<TextView>, () -> String>>()

    private fun setupSettingsPage() {
        val page = f<LinearLayout>(R.id.page_settings)
        val guide = card(page)
        guide.addView(Button(this, null, 0, R.style.SoftButton).apply {
            text = str(R.string.usage_title); setOnClickListener { showUsage() }
        })
        guide.addView(TextView(this).apply {
            text = str(R.string.language_system_hint); textSize = 12f
            setTextColor(getColor(R.color.muted)); setPadding(0, dp(10), 0, dp(6))
        })
        if (android.os.Build.VERSION.SDK_INT >= 33) guide.addView(Button(this, null, 0, R.style.SoftButton).apply {
            text = str(R.string.app_language)
            setOnClickListener {
                startActivity(Intent(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS, Uri.parse("package:$packageName")))
            }
        })
        // where the models run
        val compute = card(page)
        compute.addView(TextView(this).apply { text = str(R.string.ui_compute_devices_app_wide_settings); textSize = 13f; setTypeface(typeface, Typeface.BOLD) })
        val presets = listOf("recommended" to str(R.string.ui_recommended), "full32" to str(R.string.ui_all_bit), "custom" to str(R.string.ui_custom))
        val preset = { prefs.getString("compute", "recommended")!! }
        segments += segmented(compute, presets, preset) { prefs.edit().putString("compute", it).apply() } to preset
        val hint = TextView(this).apply { textSize = 11f; setTextColor(getColor(R.color.muted)); setPadding(0, dp(8), 0, 0) }
        compute.addView(hint)
        computeViews["hint"] = hint
        val custom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        compute.addView(custom)
        computeViews["custom"] = custom
        custom.addView(TextView(this).apply { text = str(R.string.ui_front_end_hubert_pitch_encoder); textSize = 12f; setPadding(0, dp(10), 0, 0) })
        val front = { prefs.getString("custom_front", "npu")!! }
        segments += segmented(custom, listOf("cpu" to "CPU", "npu" to "NPU"), front) { prefs.edit().putString("custom_front", it).apply() } to front
        custom.addView(TextView(this).apply { text = str(R.string.ui_decoder_generates_audio); textSize = 12f; setPadding(0, dp(10), 0, 0) })
        val dec = { prefs.getString("custom_dec", "npu_int")!! }
        segments += segmented(custom, listOf("cpu" to "CPU", "gpu" to "GPU", "npu_int" to str(R.string.ui_integer_npu), "npu16" to str(R.string.ui_bit_npu)), dec) {
            prefs.edit().putString("custom_dec", it).apply()
        } to dec
        val now = TextView(this).apply { textSize = 12f; setTextColor(getColor(R.color.accent_strong)); setPadding(0, dp(10), 0, 0) }
        compute.addView(now)
        computeViews["now"] = now
        val threadsValue = valueText()
        val threadsBar = sliderRow(compute, str(R.string.ui_cpu_threads), threadsValue, "2", "8", 6)
        threadsBar.setOnSeekBarChangeListener(slider { p, done ->
            threadsValue.text = "${p + 2}"
            if (done) prefs.edit().putInt("threads", p + 2).apply()
        })
        threadsBar.progress = hooks.getIntExtra("threads", prefs.getInt("threads", 4)) - 2
        threadsValue.text = "${threadsBar.progress + 2}"
        compute.addView(TextView(this).apply {
            text = str(R.string.ui_device_settings_apply_to_the_next_file)
            textSize = 11f; setTextColor(getColor(R.color.muted)); setPadding(0, dp(10), 0, 0)
        })
        // records
        val records = card(page)
        records.addView(TextView(this).apply { text = str(R.string.ui_diagnostics); textSize = 13f; setTypeface(typeface, Typeface.BOLD) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        row.addView(TextView(this).apply {
            text = str(R.string.ui_save_live_diagnostics_and_the_latest_live)
            textSize = 11f; setTextColor(getColor(R.color.muted))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(Button(this, null, 0, R.style.SoftButton).apply {
            text = str(R.string.ui_export_diagnostics)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginStart = dp(10) }
            setOnClickListener { exportLive() }
        })
        records.addView(row)
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        records.addView(TextView(this).apply {
            text = str(R.string.ui_app_folder_version, base.path, version)
            textSize = 11f; setTextColor(getColor(R.color.muted)); setPadding(0, dp(8), 0, 0)
        })
    }

    /** The compute card's state: which choice is lit, its hint, and what the selected voice will actually run on. */
    private fun showCompute() {
        for ((views, selected) in segments) views.forEach { it.isSelected = it.tag == selected() }
        val preset = prefs.getString("compute", "recommended")
        (computeViews["hint"] as? TextView)?.text = when (preset) {
            "full32" -> str(R.string.ui_uses_the_cpu_for_the_front_end)
            "custom" -> str(R.string.ui_choose_devices_for_file_and_live_conversion)
            else -> str(R.string.ui_uses_npu_gpu_or_cpu_based_on)
        }
        computeViews["custom"]?.visibility = if (preset == "custom") View.VISIBLE else View.GONE
        (computeViews["now"] as? TextView)?.text = currentModel()?.let { str(R.string.ui_current_voice, it.name, fileDevices(it).label) } ?: ""
    }

    // ---------------------------------------------------------------- live page

    @Volatile private var livePreparing = false
    @Volatile private var liveMessage: String? = null
    private var lastLive: LiveEngine.Stats? = null
    private val liveBars = HashMap<String, SeekBar>()
    private val liveTicker = object : Runnable {
        override fun run() {
            showLive()
            if (LiveSession.engine != null || livePreparing) ui.postDelayed(this, 100)
        }
    }

    /** Timing is saved per model and applies at the next start. */
    private fun livePrefs() = ModelLivePreferences(prefs, currentModel()?.name)
    private fun liveParams() = LivePreferences.read(getInt = livePrefs()::getInt)
    private var liveSplitSwitch: Switch? = null
    private var refreshingLiveProfile = false
    private var sharedLiveControls: LiveSettingsUi? = null
    private var liveGainBar: SeekBar? = null

    private fun card(parent: LinearLayout) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.card)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        parent.addView(this)
    }

    private fun valueText() = TextView(this).apply { textSize = 13f; typeface = Typeface.MONOSPACE; setTextColor(getColor(R.color.accent_strong)) }

    private fun switchRow(parent: LinearLayout, label: String, hint: String, key: String) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, 0) }
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@MainActivity).apply { text = label; textSize = 13f })
            addView(TextView(this@MainActivity).apply { text = hint; textSize = 11f; setTextColor(getColor(R.color.muted)) })
        })
        row.addView(Switch(this).apply {
            isChecked = if (key == "live_split") livePrefs().getBoolean(key, false) else prefs.getBoolean(key, false)
            if (key == "live_split") liveSplitSwitch = this
            setOnCheckedChangeListener { _, on ->
                if (!refreshingLiveProfile) {
                    if (key == "live_split") livePrefs().putBoolean(key, on) else prefs.edit().putBoolean(key, on).apply()
                    showLive()
                }
            }
        })
        parent.addView(row)
    }

    private fun setupLivePage() {
        f<Button>(R.id.live_toggle).setOnClickListener { toggleLive() }
        f<Button>(R.id.live_rec).setOnClickListener { toggleLiveRecording() }
        val page = f<LinearLayout>(R.id.page_live)
        val web = card(page)
        page.removeView(web); page.addView(web, 0)
        web.addView(Button(this, null, 0, R.style.SoftButton).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(48))
            text = str(R.string.ui_web_calls_desktop_browser)
            setOnClickListener { startActivity(Intent(this@MainActivity, WebCallActivity::class.java)) }
        })
        web.addView(TextView(this).apply {
            text = str(R.string.ui_start_live_conversion_then_connect_your_converted)
            textSize = 12f; setTextColor(getColor(R.color.muted)); setPadding(0,dp(8),0,0)
        })
        val virtualCard = card(page)
        page.removeView(virtualCard); page.addView(virtualCard, 1)
        virtualCard.addView(TextView(this).apply {
            text = str(R.string.ui_send_your_voice_to_other_apps); textSize = 17f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(getColor(R.color.text))
        })
        virtualCard.addView(TextView(this).apply {
            text = str(R.string.ui_virtual_mic_optional_shizuku_connection_no_time); textSize = 12f
            setTextColor(getColor(R.color.muted)); setPadding(0, dp(6), 0, dp(12))
        })
        virtualCard.addView(Button(this, null, 0, R.style.SoftButton).apply {
            text = str(R.string.ui_open_virtual_mic)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(-1, dp(48))
            setOnClickListener {
                if (busy || livePreparing || LiveSession.running || recorder != null) {
                    toast(str(R.string.ui_stop_the_current_recording_or_conversion_first))
                } else {
                    engine?.close(); engine = null; engineKey = ""
                    LiveSession.release()
                    startActivity(Intent(this@MainActivity, ShizukuMicActivity::class.java))
                }
            }
        })
        // Output level, pitch and texture belong to the selected voice.
        val voice = card(page)
        voice.addView(TextView(this).apply { text = str(R.string.ui_voice_changes_apply_instantly); textSize = 13f; setTypeface(typeface, Typeface.BOLD) })
        val gainValue = valueText()
        val gain = sliderRow(voice, str(R.string.ui_output_gain), gainValue, "−12 dB", "+24 dB", 36)
        gain.setOnSeekBarChangeListener(slider { p, done ->
            gainValue.text = String.format(Locale.US, "%+d dB", p - 12)
            LiveSession.engine?.setGain((p - 12).toDouble())
            if (done) livePrefs().putInt("live_gain_db", p - 12)
        })
        liveGainBar = gain
        gain.progress = livePrefs().getInt("live_gain_db", 12) + 12
        val pitchValue = valueText()
        val pitch = sliderRow(voice, str(R.string.ui_pitch_relative_to_your_voice), pitchValue, "−24", "+24", 48)
        pitch.setOnSeekBarChangeListener(slider { p, done ->
            pitchValue.text = if (p == 24) str(R.string.ui_semitones) else String.format(Locale.US, str(R.string.ui_semitones_2), p - 24)
            if (done) { saveSetting("pitch", (p - 24).toDouble()); loadSliders() }
        })
        liveBars["pitch"] = pitch
        val formantValue = valueText()
        val formant = sliderRow(voice, str(R.string.ui_formant_voice_texture), formantValue, "−2", "+2", 40)
        formant.setOnSeekBarChangeListener(slider { p, done ->
            formantValue.text = String.format(Locale.US, "%.1f", (p - 20) / 10.0)
            if (done) { saveSetting("formant", (p - 20) / 10.0); loadSliders() }
        })
        liveBars["formant"] = formant
        // the rest takes effect at the next start
        val more = card(page)
        val header = TextView(this).apply { textSize = 13f; setTypeface(typeface, Typeface.BOLD) }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun setOpen(open: Boolean) {
            body.visibility = if (open) View.VISIBLE else View.GONE
            header.text = if (open) str(R.string.ui_advanced_settings_apply_on_next_start) else str(R.string.ui_advanced_settings_apply_on_next_start_2)
            prefs.edit().putBoolean("live_settings_open", open).apply()
        }
        header.setOnClickListener { setOpen(body.visibility != View.VISIBLE) }
        more.addView(header)
        more.addView(body)
        body.addView(TextView(this).apply {
            text = str(R.string.ui_live_per_voice_help); textSize = 11f; setTextColor(getColor(R.color.muted))
        })
        sharedLiveControls = LiveSettingsUi(this, ::livePrefs, changed = { showLive() }).also { it.timing(body) }
        switchRow(body, str(R.string.ui_split_decoder_npu_bit_cpu), str(R.string.ui_can_sound_smoother_but_is_slower_needs), "live_split")
        switchRow(body, str(R.string.ui_play_through_speaker), str(R.string.ui_converted_audio_may_feed_back_into_the), "live_speaker")
        switchRow(body, str(R.string.ui_bypass_conversion_diagnostics), str(R.string.ui_play_the_original_mic_audio_without_the), "live_bypass")
        val export = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(14), 0, 0) }
        export.addView(TextView(this).apply {
            text = str(R.string.ui_save_diagnostics_and_the_latest_live_recording)
            textSize = 11f; setTextColor(getColor(R.color.muted))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        export.addView(Button(this, null, 0, R.style.SoftButton).apply {
            text = str(R.string.ui_export_diagnostics)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginStart = dp(10) }
            setOnClickListener { exportLive() }
        })
        body.addView(export)
        setOpen(prefs.getBoolean("live_settings_open", false))
    }

    /** The live page's pitch and texture from the selected voice's settings.json. */
    private fun loadLiveSliders() {
        val dir = currentModel() ?: return
        sharedLiveControls?.refresh()
        liveGainBar?.progress = livePrefs().getInt("live_gain_db", 12) + 12
        refreshingLiveProfile = true
        liveSplitSwitch?.isChecked = livePrefs().getBoolean("live_split", false)
        refreshingLiveProfile = false
        val j = settingsJson(dir)
        fun num(k: String) = if (j.has(k)) j.optDouble(k, 0.0) else 0.0
        liveBars["pitch"]?.progress = (num("pitch") + 24).roundToInt()
        liveBars["formant"]?.progress = ((num("formant") + 2) * 10).roundToInt()
    }

    private fun toggleLive() {
        if (ShizukuMicController.active || VirtualMicSession.isActive) { toast(str(R.string.ui_turn_off_the_virtual_microphone_first)); return }
        if (LiveSession.engine != null) { stopLive(); return }
        if (livePreparing) return
        val mic = android.Manifest.permission.RECORD_AUDIO
        if (checkSelfPermission(mic) != PackageManager.PERMISSION_GRANTED) {
            // the notification that keeps it running with the screen off needs its own permission (Android 13+)
            val ask = mutableListOf(mic)
            if (android.os.Build.VERSION.SDK_INT >= 33) ask += android.Manifest.permission.POST_NOTIFICATIONS
            requestPermissions(ask.toTypedArray(), LIVE_MIC)
            return
        }
        val dir = currentModel() ?: run { toast(str(R.string.ui_select_a_voice_model_on_the_convert)); return }
        if (!File(commonDir, "hubert.onnx").isFile || !File(commonDir, "rmvpe.onnx").isFile) { toast(str(R.string.ui_shared_models_hubert_and_rmvpe_are_required)); return }
        if (recorder != null) { toast(str(R.string.ui_stop_recording_first)); return }
        if (busy) { toast(str(R.string.ui_wait_for_conversion_to_finish)); return }
        player.stop()
        var params = liveParams()
        val autoBlock = livePrefs().getBoolean("live_auto_block", false)
        val devices = liveDevices(dir)
        val ep = devices.ep
        val liveDec = if (decFile != "model.dec.onnx") decFile else devices.file
        val front = devices.front
        val threads = prefs.getInt("threads", 4)
        val speaker = prefs.getBoolean("live_speaker", false)
        val bypass = prefs.getBoolean("live_bypass", false)
        val settings = settingsOf(dir)
        livePreparing = true
        liveMessage = str(R.string.ui_preparing)
        lastLive = null
        engine?.close(); engine = null; engineKey = ""  // the file engine makes room for the realtime models
        ui.removeCallbacks(liveTicker)
        ui.post(liveTicker)
        updateConvertButton()
        thread {
            try {
                while (true) {
                    val key = "${dir.path}|$ep|$liveDec|$front|$threads|${params.block}|${params.tail}|${params.window(null)}"
                    if (LiveSession.rvcKey != key) {
                        LiveSession.rvc?.close()
                        LiveSession.rvc = null
                        LiveSession.rvcKey = ""
                        liveMessage = str(R.string.ui_preparing_model_the_first_run_may_take)
                        LiveSession.rvc = Rvc(commonDir, dir, threads, ep, liveDec, front, live = Rvc.LiveShape(params.block, params.tail, windowFrames = params.window(null)), allowFallback = true)
                        LiveSession.rvcKey = key
                    }
                    if (!autoBlock || bypass) break
                    liveMessage = str(R.string.live_auto_block_calibrating, params.block * 10)
                    val timing = AutoBlock.measure(LiveCore(LiveSession.rvc!!, params), settings)
                    val next = AutoBlock.nextBlock(params.block, timing.maximumMs)
                        ?: error(str(R.string.live_auto_block_slow))
                    android.util.Log.i("VoiceStudio", "BLOCK_CALIBRATION block_ms=${params.block*10} steady_ms=${timing.steadyMs} next_ms=${next*10}")
                    if (next == params.block) break
                    params = params.copy(block = next)
                }
                val e = LiveEngine(applicationContext, LiveSession.rvc!!, params, settings, speaker, bypass, base)
                e.setGain(params.gainDb)
                liveMessage = str(R.string.ui_warming_up)
                e.warmUp()
                startForegroundService(Intent(this, LiveService::class.java))
                e.start()
                LiveSession.engine = e
                liveMessage = null
            } catch (t: Throwable) {
                android.util.Log.e("VoiceStudio", "live start failed", t)
                liveMessage = str(R.string.ui_could_not_start, t.message)
                LiveSession.stop(applicationContext)
            } finally {
                livePreparing = false
                ui.post { showLive(); updateConvertButton() }
            }
        }
    }

    private fun stopLive(failure: String? = null) {
        val e = LiveSession.engine ?: return
        lastLive = e.snapshot()
        LiveSession.engine = null  // the ticker stops; the threads end below
        liveMessage = failure ?: str(R.string.ui_stopping)
        thread {
            val saved = e.stopRecording()
            e.stop()
            LiveSession.stop(applicationContext)
            ui.post {
                liveMessage = failure
                if (saved != null) {
                    refreshRecordings()
                    liveMessage = (failure?.let { "$it\n" } ?: "") + String.format(Locale.US, str(R.string.ui_saved_to_record_s), saved.first.nameWithoutExtension.removeSuffix("_원본"), saved.third)
                }
                showLive()
                updateConvertButton()
            }
        }
    }

    private fun toggleLiveRecording() {
        val e = LiveSession.engine ?: return
        if (e.snapshot().recording >= 0) {
            thread {
                val saved = e.stopRecording()
                ui.post {
                    refreshRecordings()
                    if (saved != null) liveMessage = String.format(Locale.US, str(R.string.ui_saved_to_record_s), saved.first.nameWithoutExtension.removeSuffix("_원본"), saved.third)
                    showLive()
                }
            }
        } else {
            val stamp = SimpleDateFormat("MMdd_HHmmss", Locale.US).format(Date())
            recordingsDir.mkdirs()
            e.startRecording(File(recordingsDir, "실시간_${stamp}_원본.wav"), File(recordingsDir, "실시간_${stamp}_변환.wav"))
            liveMessage = null
        }
        showLive()
    }

    /** The realtime diagnosis (logs, block timings) and the newest realtime recording into Download/VoiceStudio/live_<time>,
     *  where a PC can reach them (the app's own folder is closed to other apps and to adb for files it made). */
    private fun exportLive() {
        thread {
            val stamp = SimpleDateFormat("MMdd_HHmmss", Locale.US).format(Date())
            val files = listOf("live_log.txt", "live_log_prev.txt", "live_blocks.csv", "live_blocks_prev.csv").map { File(base, it) }.filter { it.isFile } +
                (recordingsDir.listFiles { f -> f.name.startsWith("실시간_") }?.sortedByDescending { it.lastModified() }?.take(2) ?: emptyList())
            var done = 0
            for (file in files) runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/VoiceStudio/live_$stamp")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@runCatching
                contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                done++
            }
            ui.post { toast(str(R.string.ui_saved_files_to_downloads_voicestudio_live, stamp, done)) }
        }
    }

    private val thermalNames = listOf(str(R.string.ui_normal), str(R.string.ui_slightly_warm), str(R.string.ui_warm), str(R.string.ui_hot_may_slow_down), str(R.string.ui_very_hot), str(R.string.ui_critical), str(R.string.ui_near_shutdown))

    /** The live page: buttons, the two scrolling waveforms and the numbers that tell whether the phone keeps up. */
    private fun showLive() {
        val e = LiveSession.engine
        if (e != null && !e.isRunning) {  // the worker stopped by itself (an error)
            stopLive(e.snapshot().failure?.let { str(R.string.ui_stopped_2, it) } ?: str(R.string.ui_stopped))
            return
        }
        val s = e?.snapshot() ?: lastLive
        val params = liveParams()
        val selected = currentModel()?.let { liveDevices(it) }
        val active = LiveSession.rvc?.takeIf { e != null }
        val deviceLabel = active?.let { str(R.string.ui_front_end_decoder, it.frontDevice, it.decoderDevice) }
            ?: selected?.label ?: str(R.string.ui_automatic_compute_selection)
        f<TextView>(R.id.live_device).text = "${currentModel()?.name ?: str(R.string.ui_no_model)} · $deviceLabel · " +
            String.format(Locale.US, str(R.string.ui_block_s), params.block / 100.0) +
            (if (prefs.getBoolean("live_bypass", false)) str(R.string.ui_bypass_diagnostics) else "") +
            (s?.output?.takeIf { it.isNotEmpty() }?.let { str(R.string.ui_output, it) } ?: "") + str(R.string.ui_top_microphone_bottom_converted_voice)
        val toggle = f<Button>(R.id.live_toggle)
        toggle.text = when { livePreparing -> str(R.string.ui_preparing); e != null -> str(R.string.ui_stop); else -> str(R.string.ui_start) }
        toggle.isEnabled = !livePreparing
        val rec = f<Button>(R.id.live_rec)
        val recording = (s?.recording ?: -1.0) >= 0 && e != null
        rec.isEnabled = e != null
        rec.text = if (recording) str(R.string.ui_stop_3) else str(R.string.ui_record_2)
        val recTime = f<TextView>(R.id.live_rec_time)
        recTime.visibility = if (recording) View.VISIBLE else View.GONE
        if (recording) recTime.text = clock((s!!.recording * Rvc.SR).toInt())
        if (e != null && s != null) {
            f<WaveformView>(R.id.live_in_wave).livePeaks = s.inPeaks
            f<WaveformView>(R.id.live_out_wave).livePeaks = s.outPeaks
        }
        val stats = f<TextView>(R.id.live_stats)
        if (s != null && s.steps > 0) {
            val load = s.compute / s.block
            stats.text = String.format(Locale.US, str(R.string.ui_step_ms_block_ms_peak_est_latency),
                s.compute * 1000, s.block * 1000, load * 100, s.computeMax * 1000, s.latency, s.underruns, s.drops,
                if (s.extraCushion > 0) String.format(Locale.US, str(R.string.ui_buffer_s), s.extraCushion) else "",
                s.inPeakDb, s.outPeakDb, if (s.clips > 0) str(R.string.ui_clipped, s.clips) else "", thermalNames.getOrElse(s.thermal) { "${s.thermal}" })
            stats.setTextColor(getColor(if (load > 1 || s.drops > 0) R.color.rec else R.color.text))
        } else stats.text = ""
        f<TextView>(R.id.live_info).text = liveMessage ?: when {
            e != null && s != null && s.steps >= 3 && s.compute > s.block ->
                str(R.string.ui_conversion_is_not_keeping_up_increase_the)
            e != null && active?.fallbackNotice != null -> active.fallbackNotice
            e != null -> str(R.string.ui_conversion_continues_with_the_screen_off_you)
            else -> str(R.string.ui_use_headphones_without_headphones_audio_plays_through)
        }
    }

    // ---------------------------------------------------------------- misc

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        val mic = permissions.indexOf(android.Manifest.permission.RECORD_AUDIO).let { results.getOrNull(it) } == PackageManager.PERMISSION_GRANTED
        if (code == MIC && mic) toggleRecording()
        else if (code == MIC) toast(str(R.string.ui_microphone_permission_is_required_to_record))
        else if (code == LIVE_MIC && mic) toggleLive()
        else if (code == LIVE_MIC) toast(str(R.string.ui_microphone_permission_is_required_for_live_conversion))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(code: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(code, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (code) {
            PICK_FOLDER -> runCatching { importFolder(uri) }.onFailure { toast(str(R.string.ui_could_not_read_folder, it.message)) }
            PICK_ZIP -> runCatching { importZip(uri) }.onFailure { toast(str(R.string.ui_could_not_read_zip, it.message)) }
            PICK_AUDIO -> {
                f<TextView>(R.id.in_info).text = str(R.string.ui_reading_file)
                thread {
                    try {
                        val x = AudioIo.decode(this, uri)
                        val name = (query(uri) ?: str(R.string.ui_file)).substringBeforeLast('.')
                        ui.post { setInput(x, name); inputTake = null }
                    } catch (e: Exception) {
                        ui.post { f<TextView>(R.id.in_info).text = str(R.string.ui_could_not_read_file, e.message) }
                    }
                }
            }
        }
    }

    /** Test hook: `am start -n com.voicestudio.phone/.MainActivity --es convert <wav> [--ei threads N --es ep cpu|nnapi
     *  --es dec model.dec16.onnx]` converts with the selected model, writes files/test_output.wav, logs the timing. */
    private fun debugConvert(file: File) {
        thread {
            val x = AudioIo.decode(this, Uri.fromFile(file))
            ui.post {
                showPage(CONVERT)
                setInput(x, file.nameWithoutExtension); inputTake = null
                f<Switch>(R.id.normalize).isChecked = false
                runConversion { y, load, took ->
                    AudioIo.writeWav(File(base, "test_output.wav"), y)
                    val t = engine!!.timing
                    android.util.Log.i("VoiceStudio", String.format(Locale.US,
                        "converted %.1f s in %.1f s (model load %.1f s; %s): hubert %.1f, rmvpe %.1f, enc %.1f, dec %.1f, other %.1f",
                        x.size.toFloat() / Rvc.SR, took, load, engineKey.substringAfter('|'), t[0], t[1], t[2], t[3], t[4]))
                }
            }
        }
    }

    /** Test hook: realtime steps over a file, without the audio devices (StreamBench). `am start -n
     *  com.voicestudio.phone/.MainActivity --es stream <wav> [--es front cpu|qnn_htp --es ep cpu|ncnn_gpu|qnn_htp
     *  --ei threads N --ei block 20 --ei context 100 --ei crossfade 8 --ei search 1 --ef cushion 0.06 --ez paced true
     *  --ef seconds 30 --ef gain_db 0 --ef threshold_db -120]` writes files/stream_output.wav and stream_steps.csv
     *  and logs a summary line starting with "stream". */
    private fun debugStream(file: File) {
        val dir = models.firstOrNull { it.name == (hooks.getStringExtra("model") ?: prefs.getString("model", null)) }
            ?: currentModel() ?: return
        val params = LiveParams(hooks.getIntExtra("block", 20), hooks.getIntExtra("context", 100),
            hooks.getIntExtra("crossfade", 8), hooks.getIntExtra("search", 1),
            hooks.getFloatExtra("cushion", 0.06f).toDouble(), hooks.getFloatExtra("threshold_db", -120f).toDouble(),
            hooks.getFloatExtra("gain_db", 0f).toDouble())
        val front = hooks.getStringExtra("front") ?: "cpu"
        val ep = hooks.getStringExtra("ep") ?: "ncnn_gpu"
        val threads = hooks.getIntExtra("threads", 4)
        val log = { m: String -> android.util.Log.i("VoiceStudio", m); Unit }
        thread {
            try {
                val x = AudioIo.decode(this, Uri.fromFile(file))
                val t0 = System.nanoTime()
                Rvc(commonDir, dir, threads, ep, decFile, front, seed = hooks.getIntExtra("seed", 0).toLong(),
                    live = Rvc.LiveShape(params.block, params.tail, windowFrames = params.window(null)),
                    cpuFixedWindows = hooks.getBooleanExtra("fixed_windows", false),
                    profileDir = if (hooks.getBooleanExtra("profile", false)) File(base, "profiles").apply { mkdirs() } else null).use { rvc ->
                    log(String.format(Locale.US, "stream engine ready in %.1f s: front %s, decoder %s (%s)",
                        (System.nanoTime() - t0) / 1e9, front, ep, rvc.decoderDevice))
                    val r = StreamBench.run(rvc, params, settingsOf(dir), x, hooks.getBooleanExtra("paced", true),
                        hooks.getFloatExtra("seconds", (x.size / 48000f)).toDouble(), log = log)
                    AudioIo.writeWav(File(base, "stream_output.wav"), r.output)
                    File(base, "stream_steps.csv").writeText(r.csv)
                    log(r.summary + " | front $front, decoder $ep, threads $threads, model ${dir.name}")
                }
            } catch (e: Throwable) {
                android.util.Log.e("VoiceStudio", "stream failed", e)
            }
        }
    }

    private fun clock(samples: Int): String {
        val s = samples.toDouble() / Rvc.SR
        return String.format(Locale.US, "%d:%04.1f", (s / 60).toInt(), s % 60)
    }

    private fun dp(x: Int) = (x * resources.displayMetrics.density).roundToInt()

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object {
        private const val PICK_AUDIO = 1
        private const val MIC = 2
        private const val PICK_FOLDER = 3
        private const val PICK_ZIP = 4
        private const val LIVE_MIC = 5
        private const val W8A16 = "model.dec.w8a16.onnx"
        private const val RECORD = "record"
        private const val CONVERT = "convert"
        private const val LIVE = "live"
        private const val SETTINGS = "settings"
    }
}
