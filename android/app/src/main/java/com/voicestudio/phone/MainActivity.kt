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
    private var inputName = "녹음"
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
        if (r == null && LiveSession.running) { toast("실시간 변환을 끄고 녹음하세요"); return }
        if (r == null) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), MIC)
                return
            }
            player.stop()
            val started = Recorder(this).also { it.start() }
            recorder = started
            f<View>(R.id.record_dot).setBackgroundResource(R.drawable.rec_stop)
            f<TextView>(R.id.rec_title).text = "녹음 중…"
            f<TextView>(R.id.rec_info).text = "입력: ${started.sourceName} · 다시 누르면 멈춰요"
            f<WaveformView>(R.id.rec_wave).livePeaks = FloatArray(0)
            listOf(R.id.rec_play, R.id.rec_rewind, R.id.to_convert).forEach { f<Button>(it).isEnabled = false }
        } else {
            val x = r.stop()
            recorder = null
            f<View>(R.id.record_dot).setBackgroundResource(R.drawable.rec_dot)
            if (x.size < Rvc.SR / 5) {
                f<TextView>(R.id.rec_info).text = "녹음이 너무 짧아요"
                f<WaveformView>(R.id.rec_wave).setAudio(take)
                if (take != null) listOf(R.id.rec_play, R.id.rec_rewind, R.id.to_convert).forEach { f<Button>(it).isEnabled = true }
                return
            }
            recordingsDir.mkdirs()
            val name = "녹음_" + SimpleDateFormat("MMdd_HHmmss", Locale.US).format(Date())
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
        f<TextView>(R.id.rec_info).text = "파형을 누르거나 끌어서 원하는 곳부터 들을 수 있어요"
        listOf(R.id.rec_play, R.id.rec_rewind, R.id.to_convert).forEach { f<Button>(it).isEnabled = true }
        showRecTime()
    }

    private fun refreshRecordings() {
        val list = f<LinearLayout>(R.id.rec_list)
        list.removeAllViews()
        val files = recordingsDir.listFiles { file -> file.extension == "wav" }?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (files.isEmpty()) {
            list.addView(TextView(this).apply { text = "아직 녹음이 없어요"; textSize = 12f; setTextColor(getColor(R.color.muted)); setPadding(0, dp(6), 0, dp(6)) })
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
                runCatching { loadTake(AudioIo.readWav(file), file.nameWithoutExtension) }.onFailure { toast("열지 못했어요: ${it.message}") }
            }
            row.setOnLongClickListener {
                AlertDialog.Builder(this).setTitle(file.nameWithoutExtension).setMessage("이 녹음을 지울까요?")
                    .setPositiveButton("지우기") { _, _ -> file.delete(); refreshRecordings() }
                    .setNegativeButton("취소", null).show()
                true
            }
            list.addView(row)
        }
    }

    // ---------------------------------------------------------------- convert page

    private fun setupConvertPage() {
        f<SeekBar>(R.id.pitch).setOnSeekBarChangeListener(slider { p, done ->
            // a shift from the speaker's own voice, as the studio's 음높이: +9 = nine semitones up
            f<TextView>(R.id.pitch_value).text = if (p == 24) "0 반음" else String.format(Locale.US, "%+d 반음", p - 24)
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
        f<Button>(R.id.save).setOnClickListener { save()?.let { toast("음악/VoiceStudio에 저장했어요") } }
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
        Spec("low_pitch_ratio", "저음 보호 · 강도", 1.0, 3.0, 0.1, "", 1.0, "1 = 꺼짐", "3"),
        Spec("low_pitch_knee", "저음 보호 · 기준", 60.0, 200.0, 5.0, "Hz", 120.0, "60", "200"),
        Spec("high_pitch_ratio", "고음 보호 · 강도", 1.0, 3.0, 0.1, "", 1.0, "1 = 꺼짐", "3"),
        Spec("high_pitch_knee", "고음 보호 · 기준", 200.0, 600.0, 10.0, "Hz", 260.0, "200", "600"),
        Spec("intonation_ratio", "억양 넓히기 · 강도", 1.0, 1.5, 0.05, "배", 1.15, "1배", "1.5배"),
        Spec("rms_mix_rate", "볼륨 엔벨로프", 0.0, 1.0, 0.05, "", 0.0, "입력 기준", "변환 기준"),
    )
    private val switchSpecs = listOf("unvoiced_keep" to "숨소리 · 자음 살리기", "intonation_expand" to "억양 넓히기")
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
            header.text = if (open) "▾  세부 설정" else "▸  세부 설정"
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
            text = "\"1 = 꺼짐\"인 항목은 1에서 멀어질수록 강해져요. 바꾼 값은 지금 고른 모델에 바로 저장돼요."
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
        f<TextView>(R.id.voice_hint).text = "${dir.name}에 저장"
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
        if (!busy && !LiveSession.running) sortLooseFiles().takeIf { it.isNotEmpty() }?.let { toast("넣었어요: " + it.joinToString(", ")) }
        val commonOk = File(commonDir, "hubert.onnx").isFile && File(commonDir, "rmvpe.onnx").isFile
        models = modelsDir.listFiles { file -> file.isDirectory && File(file, "model.enc.onnx").isFile && File(file, "model.dec.onnx").isFile }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
        val spinner = f<Spinner>(R.id.model)
        val keep = hooks.getStringExtra("model") ?: prefs.getString("model", null)  // test hook: --es model NAME
        spinner.adapter = ArrayAdapter(this, R.layout.spinner_item, models.map { it.name }).apply { setDropDownViewResource(R.layout.spinner_item) }
        models.indexOfFirst { it.name == keep }.takeIf { it >= 0 }?.let { spinner.setSelection(it) }
        ready = commonOk && models.isNotEmpty()
        val status = f<TextView>(R.id.status)
        status.text = if (ready) "준비됨" else "모델 필요"
        status.setBackgroundResource(if (ready) R.drawable.chip else R.drawable.chip_warn)
        status.setTextColor(getColor(if (ready) R.color.green else R.color.rec))
        // also offered when only the NPU's HuBERT is missing (realtime is ~90 ms a step faster with it)
        val missing = CommonModels.items.filter { !File(commonDir, it.name).isFile }
        f<View>(R.id.common_row).visibility = if (missing.isEmpty() && !downloading) View.GONE else View.VISIBLE
        f<TextView>(R.id.common_text).text = if (commonOk) String.format(Locale.US,
            "NPU용 HuBERT (실시간이 빨라져요, %.0f MB, 와이파이 추천)", missing.sumOf { it.bytes } / 1e6)
            else String.format(Locale.US, "공통 모델 hubert·rmvpe (처음 한 번 %.0f MB, 와이파이 추천)", missing.sumOf { it.bytes } / 1e6)
        f<TextView>(R.id.help).text = if (ready) "다른 목소리는 \"불러오기\"로 변환 도구가 만든 폴더나 zip을 고르세요" else buildString {
            append("\"불러오기\"로 모델 폴더나 zip 파일을 고르세요.\n")
            if (!commonOk) append("· 공통 모델 hubert.onnx, rmvpe.onnx가 필요해요 (처음 한 번, 위의 \"받기\")\n")
            if (models.isEmpty()) append("· 목소리 모델: <이름>.enc.onnx, <이름>.dec.onnx, settings.json\n")
        }
        loadSliders()
        updateConvertButton()
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
        button.text = "멈추기"
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
                button.text = "받기"
                bar.visibility = View.GONE
                refreshModels()
                when {
                    error == null -> toast("공통 모델을 받았어요")
                    downloadStop -> toast("공통 모델 받기를 멈췄어요. 다시 누르면 남은 파일부터 받아요")
                    else -> toast("공통 모델을 받지 못했어요: ${error.message}\n${commonDir.path} 에 hubert.onnx와 rmvpe.onnx를 " +
                        "직접 넣어 주세요. PC 변환 도구(rvc2phone --common)로 만들 수 있어요.")
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
        f<TextView>(R.id.in_title).text = "원본 · $inputName"
        f<TextView>(R.id.in_info).text = ""
        f<Button>(R.id.in_play).isEnabled = true
        f<View>(R.id.result).visibility = View.GONE
        output = null
        showTimes()
        updateConvertButton()
    }

    private fun runConversion(done: ((FloatArray, Double, Double) -> Unit)? = null) {
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
        convert.text = "변환 중…"
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
                    ui.post { convert.text = if (firstNpu) "NPU 준비 중… (처음 한 번은 오래 걸려요)" else "모델 불러오는 중…" }
                    engine?.close()
                    engine = null
                    LiveSession.release()
                    val chunk = hooks.getIntExtra("chunk_s", 3) * Rvc.SR16  // test hook: --ei chunk_s N
                    engine = Rvc(commonDir, dir, threads, ep, dec, front, chunk, seed = seed.toLong(), cpuFixedWindows = fixedWindows, allowFallback = !hooked)
                    engineKey = key
                }
                val loaded = System.currentTimeMillis()
                ui.post { convert.text = "변환 중…" }
                var y = engine!!.convert(x, settings) { d, total -> ui.post { progress.max = total; progress.progress = d } }
                if (normalize) y = AudioIo.peakNormalize(y)
                val took = (System.currentTimeMillis() - loaded) / 1000.0
                val load = (loaded - started) / 1000.0
                done?.invoke(y, load, took)
                ui.post { showResult(y, dir.name, x.size, took, load) }
            } catch (e: Throwable) {
                android.util.Log.e("VoiceStudio", "conversion failed", e)
                ui.post { toast("변환 실패: ${e.message}") }
            } finally {
                ui.post { busy = false; convert.text = "변환하기"; progress.visibility = View.GONE; updateConvertButton() }
            }
        }
    }

    private fun showResult(y: FloatArray, model: String, samples: Int, took: Double, load: Double) {
        output = y
        outputName = "${inputName}_${model}_" + SimpleDateFormat("MMdd_HHmm", Locale.US).format(Date())
        val outWave = f<WaveformView>(R.id.out_wave)
        outWave.setAudio(y)
        outWave.position = f<WaveformView>(R.id.in_wave).position
        f<TextView>(R.id.out_title).text = "변환 결과 · $model"
        f<TextView>(R.id.out_info).text = String.format(Locale.US, "%.1f초 분량을 %.1f초에 변환 · %s%s", samples.toFloat() / Rvc.SR, took,
            "앞 단계 ${engine?.frontDevice ?: "CPU"} · 디코더 ${engine?.decoderDevice ?: "CPU"}" +
                (engine?.fallbackNotice?.let { " · $it" } ?: ""),
            if (load > 0.5) String.format(Locale.US, " · 모델 불러오기 %.0f초", load) else "")
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
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "공유"))
    }

    // ---------------------------------------------------------------- model import

    private fun chooseImport() {
        AlertDialog.Builder(this).setTitle("모델 불러오기")
            .setItems(arrayOf("폴더 고르기", "zip 파일 고르기")) { _, which ->
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
        val loose = base.listFiles { f -> f.isFile && (f.name.endsWith(".onnx") || ".ncnn." in f.name || f.name.endsWith(".settings.json")) }
            ?: return moved
        // a voice the phone does not have yet: both of its main parts arrived
        val names = loose.map { it.name }.toSet()
        for (f in loose) {
            val voice = f.name.substringBefore('.')  // as the parts are matched below (no dots in loose voice names)
            if (f.name == "$voice.enc.onnx" && "$voice.dec.onnx" in names && voice.lowercase() !in voices && voice.isNotBlank())
                voices[voice.lowercase()] = File(modelsDir, voice).apply { mkdirs() }
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
        val folderName = query(DocumentsContract.buildDocumentUriUsingTree(tree, treeId)) ?: "모델"
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
        val zipName = (query(uri) ?: "모델").removeSuffix(".zip").removeSuffix(".vsmodel")
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
                    val e = z.nextEntry ?: throw IllegalStateException("$wanted 없음")
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
            toast("모델 파일(.enc.onnx, .dec.onnx)이나 공통 모델(hubert.onnx, rmvpe.onnx)이 없어요")
            return
        }
        if (!hasModel) { copyIn(null, sources); return }
        val field = EditText(this).apply { setText(suggested.replace(Regex("[\\\\/:*?\"<>|]+"), "_")); setSingleLine() }
        AlertDialog.Builder(this).setTitle("모델 이름").setView(field)
            .setPositiveButton("불러오기") { _, _ ->
                val name = field.text.toString().trim().replace(Regex("[\\\\/:*?\"<>|]+"), "_").ifEmpty { "모델" }
                copyIn(File(modelsDir, name), sources)
            }.setNegativeButton("취소", null).show()
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
                for ((i, pair) in todo.withIndex()) {
                    val (source, target) = pair
                    ui.post { help.text = "불러오는 중 (${i + 1}/${todo.size}) ${source.name}…" }
                    val tmp = File(target.path + ".part")
                    source.open().use { inp -> tmp.outputStream().use { inp.copyTo(it, 1 shl 20) } }
                    target.delete()
                    tmp.renameTo(target)
                }
                if (model != null && !File(model, "model.enc.onnx").isFile) throw IllegalStateException("model.enc.onnx가 없어요")
                ui.post {
                    if (model != null) prefs.edit().putString("model", model.name).apply()
                    if (model != null && engineKey.startsWith(model.path)) { engine?.close(); engine = null; engineKey = "" }
                    toast(if (model != null) "${model.name} 모델을 불러왔어요" else "공통 모델을 불러왔어요")
                }
            } catch (e: Exception) {
                ui.post { toast("불러오지 못했어요: ${e.message}") }
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
        val frontLabel = "앞 단계 " + if (front == "npu") "NPU" else "CPU"
        return when {
            dec == "npu_int" && hasInt -> Devices(f, "qnn_htp", W8A16, "$frontLabel · 디코더 NPU 정수 (8·16비트)")
            dec == "npu16" -> Devices(f, "qnn_htp", "model.dec.onnx", "$frontLabel · 디코더 NPU 16비트 (거칠 수 있어요)")
            dec == "cpu" -> Devices(f, "cpu", "model.dec.onnx", "$frontLabel · 디코더 CPU 32비트")
            else -> Devices(f, "ncnn_gpu", "model.dec.onnx", "$frontLabel · 디코더 GPU 32비트" +
                if (dec == "npu_int") " (이 목소리엔 NPU 정수 디코더 파일이 없어요)" else "")
        }
    }

    private fun liveDevices(dir: File): Devices {
        val selected = fileDevices(dir)
        return if (prefs.getBoolean("live_split", false) && computePolicy.tryNpu && selected.front == "qnn_htp")
            Devices(selected.front, "split", "model.dec.onnx", "앞 단계 NPU · 디코더 NPU+CPU")
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
        // where the models run
        val compute = card(page)
        compute.addView(TextView(this).apply { text = "계산 장치 · 폰 전체 설정"; textSize = 13f; setTypeface(typeface, Typeface.BOLD) })
        val presets = listOf("recommended" to "추천", "full32" to "전부 32비트", "custom" to "직접 고르기")
        val preset = { prefs.getString("compute", "recommended")!! }
        segments += segmented(compute, presets, preset) { prefs.edit().putString("compute", it).apply() } to preset
        val hint = TextView(this).apply { textSize = 11f; setTextColor(getColor(R.color.muted)); setPadding(0, dp(8), 0, 0) }
        compute.addView(hint)
        computeViews["hint"] = hint
        val custom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        compute.addView(custom)
        computeViews["custom"] = custom
        custom.addView(TextView(this).apply { text = "앞 단계 (HuBERT · 음높이 · enc)"; textSize = 12f; setPadding(0, dp(10), 0, 0) })
        val front = { prefs.getString("custom_front", "npu")!! }
        segments += segmented(custom, listOf("cpu" to "CPU", "npu" to "NPU"), front) { prefs.edit().putString("custom_front", it).apply() } to front
        custom.addView(TextView(this).apply { text = "디코더 (소리를 만드는 단계)"; textSize = 12f; setPadding(0, dp(10), 0, 0) })
        val dec = { prefs.getString("custom_dec", "npu_int")!! }
        segments += segmented(custom, listOf("cpu" to "CPU", "gpu" to "GPU", "npu_int" to "NPU 정수", "npu16" to "NPU 16비트"), dec) {
            prefs.edit().putString("custom_dec", it).apply()
        } to dec
        val now = TextView(this).apply { textSize = 12f; setTextColor(getColor(R.color.accent_strong)); setPadding(0, dp(10), 0, 0) }
        compute.addView(now)
        computeViews["now"] = now
        val threadsValue = valueText()
        val threadsBar = sliderRow(compute, "CPU 스레드", threadsValue, "2", "8", 6)
        threadsBar.setOnSeekBarChangeListener(slider { p, done ->
            threadsValue.text = "${p + 2}"
            if (done) prefs.edit().putInt("threads", p + 2).apply()
        })
        threadsBar.progress = hooks.getIntExtra("threads", prefs.getInt("threads", 4)) - 2
        threadsValue.text = "${threadsBar.progress + 2}"
        compute.addView(TextView(this).apply {
            text = "실시간은 속도 때문에 늘 NPU로 돌아요 (정수 디코더가 있으면 그걸로). 바꾼 설정은 다음 변환부터 적용돼요."
            textSize = 11f; setTextColor(getColor(R.color.muted)); setPadding(0, dp(10), 0, 0)
        })
        // records
        val records = card(page)
        records.addView(TextView(this).apply { text = "기록"; textSize = 13f; setTypeface(typeface, Typeface.BOLD) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        row.addView(TextView(this).apply {
            text = "실시간 진단 기록과 마지막 실시간 녹음을 다운로드/VoiceStudio에 넣어요 (PC로 옮겨서 볼 수 있게)"
            textSize = 11f; setTextColor(getColor(R.color.muted))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(Button(this, null, 0, R.style.SoftButton).apply {
            text = "기록 보내기"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginStart = dp(10) }
            setOnClickListener { exportLive() }
        })
        records.addView(row)
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        records.addView(TextView(this).apply {
            text = "앱 폴더: ${base.path}\n버전 $version"
            textSize = 11f; setTextColor(getColor(R.color.muted)); setPadding(0, dp(8), 0, 0)
        })
    }

    /** The compute card's state: which choice is lit, its hint, and what the selected voice will actually run on. */
    private fun showCompute() {
        for ((views, selected) in segments) views.forEach { it.isSelected = it.tag == selected() }
        val preset = prefs.getString("compute", "recommended")
        (computeViews["hint"] as? TextView)?.text = when (preset) {
            "full32" -> "앞 단계는 CPU, 디코더는 GPU 또는 CPU에서 32비트로 계산해요. 속도는 기기마다 달라요."
            "custom" -> "파일·실시간의 계산 장치를 골라요. 사용할 수 없는 가속기는 CPU로 전환해요. NPU 16비트는 비교용이에요."
            else -> "기기에 맞춰 NPU, GPU 또는 CPU를 사용해요. 가속기를 실행하지 못하면 CPU로 전환해요. 실시간 속도는 기기마다 달라요."
        }
        computeViews["custom"]?.visibility = if (preset == "custom") View.VISIBLE else View.GONE
        (computeViews["now"] as? TextView)?.text = currentModel()?.let { "지금 목소리 ${it.name}: ${fileDevices(it).label}" } ?: ""
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

    /** Realtime settings, phone-wide; block, crossfade, cushion and threshold apply at the next start. */
    private fun liveParams() = LiveParams(block = prefs.getInt("live_block", 25), context = 100,
        crossfade = prefs.getInt("live_crossfade", 8), search = 1, cushion = prefs.getInt("live_cushion_cs", 6) / 100.0,
        thresholdDb = prefs.getInt("live_threshold_db", -60).toDouble(), gainDb = prefs.getInt("live_gain_db", 12).toDouble())

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
            isChecked = prefs.getBoolean(key, false)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply(); showLive() }
        })
        parent.addView(row)
    }

    private fun setupLivePage() {
        f<Button>(R.id.live_toggle).setOnClickListener { toggleLive() }
        f<Button>(R.id.live_rec).setOnClickListener { toggleLiveRecording() }
        val page = f<LinearLayout>(R.id.page_live)
        // the voice: output level (phone-wide), pitch and texture (the voice's own settings, shared with 변환)
        val voice = card(page)
        voice.addView(TextView(this).apply { text = "목소리 · 바로 바뀌어요"; textSize = 13f; setTypeface(typeface, Typeface.BOLD) })
        val gainValue = valueText()
        val gain = sliderRow(voice, "출력 크기", gainValue, "−12 dB", "+24 dB", 36)
        gain.setOnSeekBarChangeListener(slider { p, done ->
            gainValue.text = String.format(Locale.US, "%+d dB", p - 12)
            LiveSession.engine?.setGain((p - 12).toDouble())
            if (done) prefs.edit().putInt("live_gain_db", p - 12).apply()
        })
        gain.progress = prefs.getInt("live_gain_db", 12) + 12
        val pitchValue = valueText()
        val pitch = sliderRow(voice, "음높이 · 내 목소리보다", pitchValue, "−24", "+24", 48)
        pitch.setOnSeekBarChangeListener(slider { p, done ->
            pitchValue.text = if (p == 24) "0 반음" else String.format(Locale.US, "%+d 반음", p - 24)
            if (done) { saveSetting("pitch", (p - 24).toDouble()); loadSliders() }
        })
        liveBars["pitch"] = pitch
        val formantValue = valueText()
        val formant = sliderRow(voice, "포먼트 · 목소리 질감", formantValue, "−2", "+2", 40)
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
            header.text = if (open) "▾  세부 설정 · 다음에 켤 때 적용" else "▸  세부 설정 · 다음에 켤 때 적용"
            prefs.edit().putBoolean("live_settings_open", open).apply()
        }
        header.setOnClickListener { setOpen(body.visibility != View.VISIBLE) }
        more.addView(header)
        more.addView(body)
        fun intSlider(key: String, label: String, default: Int, min: Int, max: Int, minLabel: String, maxLabel: String, show: (Int) -> String) {
            val value = valueText()
            val bar = sliderRow(body, label, value, minLabel, maxLabel, max - min)
            bar.setOnSeekBarChangeListener(slider { p, done ->
                value.text = show(p + min)
                if (done) { prefs.edit().putInt(key, p + min).apply(); showLive() }
            })
            bar.progress = prefs.getInt(key, default) - min
            value.text = show(bar.progress + min)
        }
        val seconds = { v: Int -> String.format(Locale.US, "%.2f초", v / 100.0) }
        intSlider("live_block", "블록 · 한 번에 바꾸는 길이", 25, 15, 50, "0.15 빠름", "0.50 여유", seconds)
        intSlider("live_crossfade", "크로스페이드", 8, 4, 12, "0.04", "0.12", seconds)
        intSlider("live_cushion_cs", "출력 여유 · 끊김 대비", 6, 2, 20, "0.02", "0.20", seconds)
        intSlider("live_threshold_db", "무음 기준", -60, -80, -30, "−80 dB", "−30 dB") { "$it dB" }
        switchRow(body, "디코더 분할 (NPU + CPU 32비트)", "조금 덜 거칠지만 느려서 블록 0.4초 이상이 필요해요", "live_split")
        switchRow(body, "스피커로 내보내기", "바뀐 목소리가 다시 마이크로 들어갈 수 있어요", "live_speaker")
        switchRow(body, "변환 없이 통과 (진단)", "모델을 건너뛰고 마이크 소리를 그대로 내보내요", "live_bypass")
        val export = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(14), 0, 0) }
        export.addView(TextView(this).apply {
            text = "진단 기록과 마지막 실시간 녹음을 다운로드/VoiceStudio에 넣어요 (PC로 옮겨서 볼 수 있게)"
            textSize = 11f; setTextColor(getColor(R.color.muted))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        export.addView(Button(this, null, 0, R.style.SoftButton).apply {
            text = "기록 보내기"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginStart = dp(10) }
            setOnClickListener { exportLive() }
        })
        body.addView(export)
        setOpen(prefs.getBoolean("live_settings_open", false))
    }

    /** The live page's pitch and texture from the selected voice's settings.json. */
    private fun loadLiveSliders() {
        val dir = currentModel() ?: return
        val j = settingsJson(dir)
        fun num(k: String) = if (j.has(k)) j.optDouble(k, 0.0) else 0.0
        liveBars["pitch"]?.progress = (num("pitch") + 24).roundToInt()
        liveBars["formant"]?.progress = ((num("formant") + 2) * 10).roundToInt()
    }

    private fun toggleLive() {
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
        val dir = currentModel() ?: run { toast("변환 탭에서 목소리 모델을 먼저 고르세요"); return }
        if (!File(commonDir, "hubert.onnx").isFile || !File(commonDir, "rmvpe.onnx").isFile) { toast("공통 모델(hubert·rmvpe)이 필요해요 (변환 탭의 받기)"); return }
        if (recorder != null) { toast("녹음을 멈추고 켜세요"); return }
        if (busy) { toast("변환이 끝나면 켜세요"); return }
        player.stop()
        val params = liveParams()
        val devices = liveDevices(dir)
        val ep = devices.ep
        val liveDec = if (decFile != "model.dec.onnx") decFile else devices.file
        val front = devices.front
        val threads = prefs.getInt("threads", 4)
        val speaker = prefs.getBoolean("live_speaker", false)
        val bypass = prefs.getBoolean("live_bypass", false)
        val settings = settingsOf(dir)
        livePreparing = true
        liveMessage = "준비 중…"
        lastLive = null
        engine?.close(); engine = null; engineKey = ""  // the file engine makes room for the realtime models
        ui.removeCallbacks(liveTicker)
        ui.post(liveTicker)
        updateConvertButton()
        thread {
            try {
                val key = "${dir.path}|$ep|$liveDec|$front|$threads|${params.block}|${params.tail}"
                if (LiveSession.rvcKey != key) {
                    LiveSession.rvc?.close()
                    LiveSession.rvc = null
                    LiveSession.rvcKey = ""
                    liveMessage = "모델 준비 중… 처음 실행할 때는 시간이 걸릴 수 있어요."
                    LiveSession.rvc = Rvc(commonDir, dir, threads, ep, liveDec, front, live = Rvc.LiveShape(params.block, params.tail), allowFallback = true)
                    LiveSession.rvcKey = key
                }
                val e = LiveEngine(applicationContext, LiveSession.rvc!!, params, settings, speaker, bypass, base)
                e.setGain(params.gainDb)
                liveMessage = "예열 중…"
                e.warmUp()
                startForegroundService(Intent(this, LiveService::class.java))
                e.start()
                LiveSession.engine = e
                liveMessage = null
            } catch (t: Throwable) {
                android.util.Log.e("VoiceStudio", "live start failed", t)
                liveMessage = "켜지 못했어요: ${t.message}"
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
        liveMessage = failure ?: "멈추는 중…"
        thread {
            val saved = e.stopRecording()
            e.stop()
            LiveSession.stop(applicationContext)
            ui.post {
                liveMessage = failure
                if (saved != null) {
                    refreshRecordings()
                    liveMessage = (failure?.let { "$it\n" } ?: "") + String.format(Locale.US, "녹음 탭에 저장했어요: %s (%.1f초)", saved.first.nameWithoutExtension.removeSuffix("_원본"), saved.third)
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
                    if (saved != null) liveMessage = String.format(Locale.US, "녹음 탭에 저장했어요: %s (%.1f초)", saved.first.nameWithoutExtension.removeSuffix("_원본"), saved.third)
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
            ui.post { toast("다운로드/VoiceStudio/live_$stamp 에 파일 ${done}개를 넣었어요") }
        }
    }

    private val thermalNames = listOf("정상", "약간 따뜻함", "따뜻함", "뜨거움 (느려질 수 있어요)", "아주 뜨거움", "위험", "종료 직전")

    /** The live page: buttons, the two scrolling waveforms and the numbers that tell whether the phone keeps up. */
    private fun showLive() {
        val e = LiveSession.engine
        if (e != null && !e.isRunning) {  // the worker stopped by itself (an error)
            stopLive(e.snapshot().failure?.let { "멈췄어요: $it" } ?: "멈췄어요")
            return
        }
        val s = e?.snapshot() ?: lastLive
        val params = liveParams()
        val selected = currentModel()?.let { liveDevices(it) }
        val active = LiveSession.rvc?.takeIf { e != null }
        val deviceLabel = active?.let { "앞 단계 ${it.frontDevice} · 디코더 ${it.decoderDevice}" }
            ?: selected?.label ?: "계산 장치 자동 선택"
        f<TextView>(R.id.live_device).text = "${currentModel()?.name ?: "모델 없음"} · $deviceLabel · " +
            String.format(Locale.US, "블록 %.2f초", params.block / 100.0) +
            (if (prefs.getBoolean("live_bypass", false)) " · 통과(진단)" else "") +
            (s?.output?.takeIf { it.isNotEmpty() }?.let { "\n출력: $it" } ?: "") + "\n위: 마이크 · 아래: 바뀐 목소리"
        val toggle = f<Button>(R.id.live_toggle)
        toggle.text = when { livePreparing -> "준비 중…"; e != null -> "끄기"; else -> "켜기" }
        toggle.isEnabled = !livePreparing
        val rec = f<Button>(R.id.live_rec)
        val recording = (s?.recording ?: -1.0) >= 0 && e != null
        rec.isEnabled = e != null
        rec.text = if (recording) "■ 멈춤" else "● 녹음"
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
            stats.text = String.format(Locale.US, "단계 %.0f ms / 블록 %.0f ms (%.0f%%) · 최대 %.0f\n지연 약 %.2f초 · 끊김 %d · 건너뜀 %d%s\n입력 최대 %.0f dB · 출력 최대 %.0f dB%s · 발열 %s",
                s.compute * 1000, s.block * 1000, load * 100, s.computeMax * 1000, s.latency, s.underruns, s.drops,
                if (s.extraCushion > 0) String.format(Locale.US, " · 여유 +%.2f초", s.extraCushion) else "",
                s.inPeakDb, s.outPeakDb, if (s.clips > 0) " (눌림 ${s.clips})" else "", thermalNames.getOrElse(s.thermal) { "${s.thermal}" })
            stats.setTextColor(getColor(if (load > 1 || s.drops > 0) R.color.rec else R.color.text))
        } else stats.text = ""
        f<TextView>(R.id.live_info).text = liveMessage ?: when {
            e != null && s != null && s.steps >= 3 && s.compute > s.block ->
                "현재 계산 속도가 실시간을 따라가지 못해요. 블록을 늘리거나 파일 변환을 이용하세요."
            e != null && active?.fallbackNotice != null -> active.fallbackNotice
            e != null -> "화면을 꺼도 계속 돌아요. 알림의 \"끄기\"로도 멈출 수 있어요."
            else -> "이어폰을 끼고 켜세요. 이어폰이 없으면 통화 스피커(귀에 대는 곳)로 나와요."
        }
    }

    // ---------------------------------------------------------------- misc

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        val mic = permissions.indexOf(android.Manifest.permission.RECORD_AUDIO).let { results.getOrNull(it) } == PackageManager.PERMISSION_GRANTED
        if (code == MIC && mic) toggleRecording()
        else if (code == MIC) toast("마이크 권한이 있어야 녹음할 수 있어요")
        else if (code == LIVE_MIC && mic) toggleLive()
        else if (code == LIVE_MIC) toast("마이크 권한이 있어야 실시간 변환을 할 수 있어요")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(code: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(code, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (code) {
            PICK_FOLDER -> runCatching { importFolder(uri) }.onFailure { toast("폴더를 읽지 못했어요: ${it.message}") }
            PICK_ZIP -> runCatching { importZip(uri) }.onFailure { toast("zip을 읽지 못했어요: ${it.message}") }
            PICK_AUDIO -> {
                f<TextView>(R.id.in_info).text = "파일 읽는 중…"
                thread {
                    try {
                        val x = AudioIo.decode(this, uri)
                        val name = (query(uri) ?: "파일").substringBeforeLast('.')
                        ui.post { setInput(x, name); inputTake = null }
                    } catch (e: Exception) {
                        ui.post { f<TextView>(R.id.in_info).text = "파일을 읽지 못했어요: ${e.message}" }
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
                    live = Rvc.LiveShape(params.block, params.tail),
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
