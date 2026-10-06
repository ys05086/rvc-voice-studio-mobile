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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // where the DSP looks for QNN's NPU programs: the app's extracted libraries first
        android.system.Os.setenv("ADSP_LIBRARY_PATH",
            "${applicationInfo.nativeLibraryDir};/system/lib/rfsa/adsp;/system/vendor/lib/rfsa/adsp;/dsp", true)
        setContentView(R.layout.activity_main)
        f<TextView>(R.id.brand).text = SpannableStringBuilder("voice / studio").apply {
            setSpan(ForegroundColorSpan(getColor(R.color.accent)), 5, 8, 0)
        }
        f<View>(R.id.tab_record).setOnClickListener { showPage(record = true) }
        f<View>(R.id.tab_convert).setOnClickListener { showPage(record = false) }
        setupRecordPage()
        setupConvertPage()
        f<Button>(R.id.get_common).setOnClickListener { toggleCommonDownload() }
        player.onStateChange = { wave, playing -> playButtonFor(wave)?.text = if (playing) "⏸" else "▶" }
        showPage(record = prefs.getBoolean("record_page", true))
        refreshModels()
        refreshRecordings()
        decFile = intent.getStringExtra("dec") ?: decFile
        intent.getStringExtra("convert")?.let { debugConvert(File(it)) }
        intent.getStringExtra("bench")?.let { path ->  // test hook: --es bench <dec.onnx> --es ep cpu|qnn_htp|qnn_gpu
            thread {
                val msg = try {
                    val (first, rest) = Rvc.benchDecoder(path, intent.getStringExtra("ep") ?: ep, intent.getIntExtra("threads", 4))
                    String.format(Locale.US, "bench %s on %s: first run %.2f s, then %.3f s per run", File(path).name, ep, first, rest)
                } catch (e: Throwable) { "bench failed on $ep: ${e.message}" }
                android.util.Log.i("VoiceStudio", msg)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!busy) refreshModels()
    }

    override fun onPause() {
        player.stop()
        super.onPause()
    }

    override fun onDestroy() {
        player.stop()
        engine?.close()
        super.onDestroy()
    }

    private fun showPage(record: Boolean) {
        // the newest take follows to 변환 by itself, unless a file was picked there since
        val t = take
        if (!record && t != null && t !== input && (input == null || inputTake != null) && recorder == null) {
            setInput(t, takeName)
            inputTake = t
        }
        f<View>(R.id.page_record).visibility = if (record) View.VISIBLE else View.GONE
        f<View>(R.id.page_convert).visibility = if (record) View.GONE else View.VISIBLE
        f<View>(R.id.tab_record).isSelected = record
        f<View>(R.id.tab_convert).isSelected = !record
        prefs.edit().putBoolean("record_page", record).apply()
        f<ScrollView>(R.id.scroll).scrollTo(0, 0)
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
        f<Button>(R.id.to_convert).setOnClickListener { showPage(record = false) }
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
        // compute devices: phone settings, not voice ones
        val gpuRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(14), 0, 0) }
        gpuRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@MainActivity).apply { text = "GPU로 변환 (디코더) · 폰 전체 설정"; textSize = 13f })
            addView(TextView(this@MainActivity).apply { text = "소리를 만드는 디코더를 GPU 32비트로 돌려요. 2배쯤 빠르고 소리는 CPU와 같아요. 모델 폴더에 .dec.ncnn 파일이 있어야 해요"; textSize = 11f; setTextColor(getColor(R.color.muted)) })
        })
        val gpu = Switch(this).apply { isChecked = prefs.getBoolean("gpu_dec", true) }
        gpu.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("gpu_dec", on).apply() }
        gpuRow.addView(gpu)
        body.addView(gpuRow)
        val npuRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(14), 0, 0) }
        npuRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@MainActivity).apply { text = "NPU로 변환 (앞 단계) · 폰 전체 설정"; textSize = 13f })
            addView(TextView(this@MainActivity).apply { text = "HuBERT·음높이·enc를 NPU로 돌려요. 소리를 만드는 디코더는 32비트 그대로예요. 처음 한 번은 준비가 오래 걸려요"; textSize = 11f; setTextColor(getColor(R.color.muted)) })
        })
        val npu = Switch(this).apply { isChecked = prefs.getBoolean("npu_front", false) }
        npu.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("npu_front", on).apply() }
        npuRow.addView(npu)
        body.addView(npuRow)
        // CPU threads: a phone setting, not a voice one
        val threadsValue = TextView(this).apply { textSize = 13f; typeface = Typeface.MONOSPACE; setTextColor(getColor(R.color.accent_strong)) }
        val threadsBar = sliderRow(body, "변환 스레드 · 폰 전체 설정", threadsValue, "2", "8", 6)
        threadsBar.setOnSeekBarChangeListener(slider { p, done ->
            threadsValue.text = "${p + 2}"
            if (done) prefs.edit().putInt("threads", p + 2).apply()
        })
        threadsBar.progress = intent.getIntExtra("threads", prefs.getInt("threads", 4)) - 2
        threadsValue.text = "${threadsBar.progress + 2}"
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
    }

    /** Sliders and switches from the selected model's settings.json. */
    private fun loadSliders() {
        val dir = currentModel() ?: return
        val j = settingsJson(dir)
        fun num(k: String, d: Double) = if (j.has(k)) j.optDouble(k, d) else d
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
        val commonOk = File(commonDir, "hubert.onnx").isFile && File(commonDir, "rmvpe.onnx").isFile
        models = modelsDir.listFiles { file -> file.isDirectory && File(file, "model.enc.onnx").isFile && File(file, "model.dec.onnx").isFile }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
        val spinner = f<Spinner>(R.id.model)
        val keep = prefs.getString("model", null)
        spinner.adapter = ArrayAdapter(this, R.layout.spinner_item, models.map { it.name }).apply { setDropDownViewResource(R.layout.spinner_item) }
        models.indexOfFirst { it.name == keep }.takeIf { it >= 0 }?.let { spinner.setSelection(it) }
        val ready = commonOk && models.isNotEmpty()
        val status = f<TextView>(R.id.status)
        status.text = if (ready) "준비됨" else "모델 필요"
        status.setBackgroundResource(if (ready) R.drawable.chip else R.drawable.chip_warn)
        status.setTextColor(getColor(if (ready) R.color.green else R.color.rec))
        f<View>(R.id.common_row).visibility = if (commonOk && !downloading) View.GONE else View.VISIBLE
        f<TextView>(R.id.common_text).text = String.format(Locale.US, "공통 모델 hubert·rmvpe (처음 한 번 %.0f MB, 와이파이 추천)",
            CommonModels.totalBytes / 1e6)
        f<TextView>(R.id.help).text = if (ready) "모델 폴더: ${base.path}" else buildString {
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
        f<Button>(R.id.convert).isEnabled = input != null && models.isNotEmpty() && !busy
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
        val threads = intent.getIntExtra("threads", prefs.getInt("threads", 4))
        // the decoder stays 32-bit (fp16 on the NPU roughens the waveform); the feature models may use the NPU
        val ep = intent.getStringExtra("ep") ?: if (prefs.getBoolean("gpu_dec", true)) "ncnn_gpu" else "cpu"
        val front = intent.getStringExtra("front") ?: if (prefs.getBoolean("npu_front", false)) "qnn_htp" else "cpu"
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
                val key = "${dir.path}|$threads|$ep|$decFile|$front|${intent.getIntExtra("chunk_s", 3)}"
                if (engineKey != key) {
                    val firstNpu = (ep.startsWith("qnn") && !File(dir, "model.dec.$ep.onnx").isFile) ||
                        (front.startsWith("qnn") && commonDir.listFiles { f -> f.name.startsWith("hubert.$front.") }.isNullOrEmpty())
                    ui.post { convert.text = if (firstNpu) "NPU 준비 중… (처음 한 번은 오래 걸려요)" else "모델 불러오는 중…" }
                    engine?.close()
                    engine = null
                    val chunk = intent.getIntExtra("chunk_s", 3) * Rvc.SR16  // test hook: --ei chunk_s N
                    engine = Rvc(commonDir, dir, threads, ep, decFile, front, chunk)
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
            "앞 단계 " + (if (engineKey.split("|").getOrNull(4)?.startsWith("qnn") == true) "NPU" else "CPU") + " · 디코더 " + (engine?.decoderDevice ?: "CPU"),
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
            n == "hubert.onnx" || n == "rmvpe.onnx" -> File(commonDir, n)
            model == null -> null
            n.endsWith(".enc.onnx") -> File(model, "model.enc.onnx")
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
                model?.listFiles { file -> file.name.startsWith("model.dec.qnn") || file.name.startsWith("model.dec.ncnn") || file.name.startsWith("model.enc.qnn") }?.forEach { it.delete() }
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

    // ---------------------------------------------------------------- misc

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        if (code == MIC && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) toggleRecording()
        else if (code == MIC) toast("마이크 권한이 있어야 녹음할 수 있어요")
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
                showPage(record = false)
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
    }
}
