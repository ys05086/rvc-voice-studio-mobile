package com.voicestudio.phone

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import rikka.shizuku.Shizuku
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.roundToInt
import org.json.JSONObject

/** Phone-only control surface. Technical details stay behind a disclosure. */
class ShizukuMicActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("phone", MODE_PRIVATE) }
    private val purple get() = getColor(R.color.accent_strong)
    private val ink get() = getColor(R.color.text)
    private val muted get() = getColor(R.color.muted)
    private var targets = emptyList<MicTarget>()
    private var loadingApps = true
    private var models = emptyList<String>()
    private var selectedPackage = "com.discord"
    private var selectedModel = ""
    private var otherProfile = false
    private var allApps = false
    private lateinit var allAppsSwitch: Switch
    private var localError: String? = null
    private lateinit var connection: TextView
    private lateinit var notificationHint: TextView
    private var nextNotificationCheck = 0L
    private lateinit var headline: TextView
    private lateinit var detail: TextView
    private lateinit var timer: TextView
    private lateinit var targetName: TextView
    private lateinit var targetBadge: TextView
    private lateinit var targetChange: TextView
    private lateinit var targetIcon: ImageView
    private lateinit var voiceName: TextView
    private lateinit var profileHint: TextView
    private lateinit var profile: Switch
    private lateinit var primary: Button
    private lateinit var openTarget: Button
    private lateinit var targetCard: LinearLayout
    private lateinit var voiceCard: LinearLayout
    private lateinit var metrics: TextView
    private lateinit var inputBar: ProgressBar
    private lateinit var outputBar: ProgressBar
    private lateinit var inputValue: TextView
    private lateinit var outputValue: TextView
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { code, grant ->
        if (code == 71) ui.post { if (grant == 0) begin() else showError(str(R.string.ui_allow_voice_studio_in_shizuku)) }
    }
    private val ticker = object : Runnable {
        override fun run() { render(); ui.postDelayed(this, 250) }
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun shape(color: Int, radius: Int = 20, stroke: Boolean = false) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (stroke) setStroke(dp(1), getColor(R.color.line))
    }
    private fun text(value: String, size: Float = 14f, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun LinearLayout.space(height: Int) { addView(View(this@ShizukuMicActivity), LinearLayout.LayoutParams(1, dp(height))) }
    private fun card(parent: LinearLayout, color: Int = getColor(R.color.panel)) = column().apply {
        background = shape(color, 22, true); setPadding(dp(18), dp(17), dp(18), dp(17))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }
    private fun button(label: String, filled: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 15f; isAllCaps = false
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (filled) Color.WHITE else purple)
        background = shape(if (filled) purple else getColor(R.color.lavender), 16)
        minHeight = dp(50); setPadding(dp(12), 0, dp(12), 0)
        setOnClickListener { action() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        selectedPackage = if (ShizukuMicController.active) ShizukuMicController.targetPackage else prefs.getString("virtual_mic_target", "com.discord")!!
        otherProfile = if (ShizukuMicController.active) ShizukuMicController.otherProfile else prefs.getBoolean("virtual_mic_other_profile", false)
        allApps = if (ShizukuMicController.active) ShizukuMicController.allApps else prefs.getBoolean("virtual_mic_all_apps", false)
        models = File(getExternalFilesDir(null), "models").listFiles()?.filter { it.isDirectory && File(it, "model.enc.onnx").isFile }?.map { it.name }?.sorted() ?: emptyList()
        selectedModel = if (ShizukuMicController.active) ShizukuMicController.modelName else prefs.getString("virtual_mic_model", prefs.getString("model", null)) ?: ""
        if (selectedModel !in models) selectedModel = models.firstOrNull() ?: ""
        val root = column().apply { setBackgroundColor(getColor(R.color.bg)); fitsSystemWindows = true }
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val content = column().apply { setPadding(dp(20), dp(12), dp(20), dp(12)) }
        val top = row()
        top.addView(text("‹", 30f, purple).apply {
            contentDescription = str(R.string.ui_back); gravity = Gravity.CENTER; setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(40), dp(48)))
        top.addView(text("VOICE / STUDIO", 12f, purple, true), LinearLayout.LayoutParams(0, -2, 1f))
        connection = text(str(R.string.ui_checking_connection), 11f, purple, true).apply {
            background = shape(getColor(R.color.lavender), 30); setPadding(dp(10), dp(7), dp(10), dp(7))
            contentDescription = str(R.string.ui_shizuku_connection_status_tap_to_open_shizuku)
            setOnClickListener { launch("moe.shizuku.privileged.api") }
        }
        top.addView(connection)
        content.addView(top); content.space(12)
        content.addView(text(str(R.string.ui_virtual_microphone), 30f, ink, true))
        content.space(3)
        content.addView(text(str(R.string.ui_send_your_converted_voice_to_other_apps), 14f, muted))
        content.addView(text(str(R.string.usage_title), 12f, purple, true).apply {
            setPadding(0, dp(8), 0, dp(8)); setOnClickListener { showUsage() }
        })
        notificationHint = text(str(R.string.ui_status_notifications_are_off_tap_to_enable), 12f, purple).apply {
            setPadding(0, dp(10), 0, 0)
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    && !prefs.getBoolean("notification_permission_requested", false)) {
                    prefs.edit().putBoolean("notification_permission_requested", true).apply()
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 74)
                } else startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
            }
        }
        content.addView(notificationHint)
        content.space(20)
        val hero = card(content, getColor(R.color.lavender))
        val statusRow = row()
        statusRow.addView(View(this).apply { background = shape(purple, 8) },
            LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(12) })
        headline = text(str(R.string.ui_ready_to_start), 20f, ink, true)
        statusRow.addView(headline, LinearLayout.LayoutParams(0, -2, 1f))
        timer = text("", 13f, purple, true)
        statusRow.addView(timer)
        hero.addView(statusRow); hero.space(7)
        detail = text(str(R.string.ui_choose_an_app_and_voice_then_turn), 13f, muted)
        hero.addView(detail); hero.space(16)
        fun level(label: String): Pair<ProgressBar, TextView> {
            val labels = row()
            labels.addView(text(label, 11f, muted), LinearLayout.LayoutParams(0, -2, 1f))
            val value = text("—", 11f, purple)
            labels.addView(value); hero.addView(labels); hero.space(5)
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; progress = 0
                progressTintList = android.content.res.ColorStateList.valueOf(purple)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.panel))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            hero.addView(bar, LinearLayout.LayoutParams(-1, dp(6))); hero.space(9)
            return bar to value
        }
        level(str(R.string.ui_mic_input)).let { inputBar = it.first; inputValue = it.second }
        level(str(R.string.ui_converted_output)).let { outputBar = it.first; outputValue = it.second }
        targetCard = card(content)
        allAppsSwitch = Switch(this).apply {
            text = str(R.string.ui_replace_input_for_all_apps); textSize = 15f; setTextColor(ink); isChecked = allApps
            setOnCheckedChangeListener { _, checked ->
                allApps = checked
                if (checked) { otherProfile = false; profile.isChecked = false }
                localError = null; saveSelection(); updateSelection()
            }
        }
        targetCard.addView(allAppsSwitch); targetCard.space(8)
        targetCard.addView(text(str(R.string.ui_destination_app), 11f, muted, true)); targetCard.space(10)
        val appRow = row().apply { setOnClickListener { chooseTarget() }; contentDescription = str(R.string.ui_choose_destination_app) }
        targetIcon = ImageView(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        appRow.addView(targetIcon, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(12) })
        val appLabels = column()
        targetName = text(str(R.string.ui_choose_app), 18f, ink, true); appLabels.addView(targetName)
        targetBadge = text("", 11f, muted); appLabels.addView(targetBadge)
        appRow.addView(appLabels, LinearLayout.LayoutParams(0, -2, 1f))
        targetChange = text(str(R.string.ui_change), 13f, purple, true); appRow.addView(targetChange)
        targetCard.addView(appRow, LinearLayout.LayoutParams(-1, dp(58)))
        targetCard.space(8)
        profile = Switch(this).apply {
            text = str(R.string.ui_secure_folder_other_profile); textSize = 13f; setTextColor(ink)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL); isChecked = otherProfile
            setOnCheckedChangeListener { _, checked ->
                otherProfile = checked; localError = null; saveSelection(); updateSelection()
            }
        }
        targetCard.addView(profile)
        profileHint = text("", 11f, muted); targetCard.addView(profileHint)
        voiceCard = card(content)
        voiceCard.addView(text(str(R.string.ui_voice_model), 11f, muted, true)); voiceCard.space(8)
        val voiceRow = row().apply { setOnClickListener { chooseModel() }; contentDescription = str(R.string.ui_choose_voice) }
        voiceRow.addView(text("≈", 28f, purple, true), LinearLayout.LayoutParams(dp(44), dp(44)))
        voiceName = text("", 18f, ink, true)
        voiceRow.addView(voiceName, LinearLayout.LayoutParams(0, -2, 1f)); voiceRow.addView(text(str(R.string.ui_change), 13f, purple, true))
        voiceCard.addView(voiceRow)
        voiceCard.space(5); voiceCard.addView(text(str(R.string.ui_uses_the_saved_pitch_and_formant_settings), 11f, muted))
        voiceCard.space(10)
        voiceCard.addView(button(str(R.string.ui_voice_live_settings)) { showVoiceSettings() }, LinearLayout.LayoutParams(-1, dp(46)))
        val diagnostic = text(str(R.string.ui_show_processing_details), 12f, muted).apply { setPadding(dp(4), dp(8), 0, dp(8)) }
        metrics = text("", 12f, muted).apply { visibility = View.GONE; setPadding(dp(4), dp(8), dp(4), dp(8)) }
        diagnostic.setOnClickListener {
            metrics.visibility = if (metrics.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            diagnostic.text = if (metrics.visibility == View.VISIBLE) str(R.string.ui_hide_processing_details) else str(R.string.ui_show_processing_details)
        }
        content.addView(diagnostic); content.addView(metrics)
        scroll.addView(content); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = column().apply { setPadding(dp(20), dp(12), dp(20), dp(12)); setBackgroundColor(getColor(R.color.panel)) }
        primary = button(str(R.string.ui_start_virtual_mic), true) {
            if (ShizukuMicController.active) ShizukuMicController.stop() else begin()
        }
        footer.addView(primary, LinearLayout.LayoutParams(-1, dp(52))); footer.space(8)
        openTarget = button(str(R.string.ui_open_selected_app)) {
            if (allApps) startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            else if (otherProfile) showError(str(R.string.ui_open_secure_folder_yourself_then_open_the)) else launch(selectedPackage)
        }
        footer.addView(openTarget, LinearLayout.LayoutParams(-1, dp(44))); footer.space(8)
        footer.addView(text(str(R.string.ui_no_time_limit_stop_to_restore_the), 10f, muted).apply { gravity = Gravity.CENTER })
        root.addView(footer); setContentView(root)
        updateSelection(); ui.post(ticker)
        thread(name = "mic-app-list") {
            val found = runCatching { MicAppCatalog.load(applicationContext) }.getOrDefault(emptyList())
            ui.post { if (!isDestroyed) { targets = found; loadingApps = false; updateSelection() } }
        }
    }
    private fun saveSelection() {
        prefs.edit().putString("virtual_mic_target", selectedPackage).putString("virtual_mic_model", selectedModel)
            .putBoolean("virtual_mic_other_profile", otherProfile).apply()
        prefs.edit().putBoolean("virtual_mic_all_apps", allApps).apply()
    }
    private fun updateSelection() {
        val app = targets.firstOrNull { it.packageName == selectedPackage }
        targetName.text = if (allApps) str(R.string.ui_all_apps) else app?.label ?: runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(selectedPackage, 0)).toString() }.getOrDefault(selectedPackage)
        targetIcon.setImageDrawable(runCatching { packageManager.getApplicationIcon(if (allApps) packageName else selectedPackage) }.getOrNull())
        targetBadge.text = if (allApps) str(R.string.ui_includes_secure_folder_supported_mic_inputs) else if (otherProfile) str(R.string.ui_other_profile_active_input_required) else if (app?.verified == true) str(R.string.ui_tested_on_this_device) else str(R.string.ui_compatibility_not_verified)
        targetBadge.setTextColor(if (!allApps && !otherProfile && app?.verified == true) getColor(R.color.green) else muted)
        targetChange.visibility = if (allApps) View.GONE else View.VISIBLE
        voiceName.text = selectedModel.ifEmpty { str(R.string.ui_add_a_model_first) }
        profile.visibility = if (allApps) View.GONE else View.VISIBLE
        profileHint.text = if (allApps) str(R.string.ui_send_converted_input_without_choosing_an_app)
            else if (otherProfile) str(R.string.ui_start_a_call_or_recording_in_that)
            else str(R.string.ui_connect_to_an_app_installed_in_the)
        openTarget.text = if (allApps) str(R.string.ui_go_to_home_screen) else if (otherProfile) str(R.string.ui_open_app_in_secure_folder_yourself) else str(R.string.ui_open, targetName.text)
    }
    private fun chooseTarget() {
        if (ShizukuMicController.active || allApps) return
        if (loadingApps) {
            Toast.makeText(this, str(R.string.ui_loading_the_app_list_try_again_in), Toast.LENGTH_SHORT).show()
            return
        }
        val body = column().apply { setPadding(dp(20), dp(4), dp(20), dp(6)) }
        val search = EditText(this).apply { hint = str(R.string.ui_search_app_name_or_package); textSize = 15f; setSingleLine() }
        body.addView(search)
        body.addView(text(str(R.string.ui_choose_an_installed_app_input_compatibility_varies), 12f, muted))
        val list = ListView(this).apply { divider = null }
        body.addView(list, LinearLayout.LayoutParams(-1, dp(340)))
        var visible = targets
        val adapter = object : BaseAdapter() {
            override fun getCount() = visible.size
            override fun getItem(position: Int) = visible[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, old: View?, parent: ViewGroup): View {
                val app = visible[position]
                val line = row().apply { setPadding(0, dp(10), 0, dp(10)) }
                line.addView(ImageView(this@ShizukuMicActivity).apply {
                    setImageDrawable(runCatching { packageManager.getApplicationIcon(app.packageName) }.getOrNull())
                }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(12) })
                val labels = column(); labels.addView(text(app.label, 15f, ink, true))
                labels.addView(text(if (app.verified) str(R.string.ui_tested_in_the_main_profile) else if (app.microphone) str(R.string.ui_uses_microphone_not_verified) else str(R.string.ui_compatibility_not_verified), 11f, muted))
                labels.addView(text(app.packageName, 10f, muted)); line.addView(labels)
                return line
            }
        }
        list.adapter = adapter
        val dialog = AlertDialog.Builder(this).setTitle(str(R.string.ui_choose_destination_app)).setView(body).setNegativeButton(str(R.string.ui_close), null).create()
        list.setOnItemClickListener { _, _, position, _ ->
            selectedPackage = visible[position].packageName; localError = null; saveSelection(); updateSelection(); dialog.dismiss()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s.toString().trim()
                visible = targets.filter { it.label.contains(query, true) || it.packageName.contains(query, true) }
                adapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        dialog.show()
    }
    private fun chooseModel() {
        if (ShizukuMicController.active) return
        if (models.isEmpty()) { showError(str(R.string.ui_add_a_model_on_the_convert_tab)); return }
        AlertDialog.Builder(this).setTitle(str(R.string.ui_choose_voice))
            .setSingleChoiceItems(models.toTypedArray(), models.indexOf(selectedModel)) { dialog, which ->
                selectedModel = models[which]; localError = null; saveSelection(); updateSelection(); dialog.dismiss()
            }.setNegativeButton(str(R.string.ui_close), null).show()
    }
    private fun showVoiceSettings() {
        val model = File(getExternalFilesDir(null), "models").listFiles()
            ?.firstOrNull { it.isDirectory && it.name == selectedModel } ?: return
        val file = File(model, "settings.json")
        val body = column().apply { setPadding(dp(20), dp(8), dp(20), dp(16)) }
        body.addView(text(str(R.string.ui_virtual_settings_help), 12f, muted))
        val controls = LiveSettingsUi(this, prefs, virtualMic = true)
        fun readVoice(): JSONObject = if (file.isFile) JSONObject(file.readText()) else JSONObject()
        fun voice(key: String, label: Int, min: Int, max: Int, scale: Double) {
            controls.slider(body, str(label), min, max, if (scale == 1.0) min.toString() else (min / scale).toString(),
                if (scale == 1.0) "+$max" else (max / scale).toString(),
                { (readVoice().optDouble(key, 0.0) * scale).roundToInt() },
                { value ->
                    try {
                        val data = readVoice().put(key, value / scale).toString(1).toByteArray(Charsets.UTF_8)
                        val atomic = android.util.AtomicFile(file)
                        val stream = atomic.startWrite()
                        try { stream.write(data); atomic.finishWrite(stream) }
                        catch (e: Throwable) { atomic.failWrite(stream); throw e }
                    } catch (e: Exception) { showError(e.message ?: str(R.string.ui_check_connection)) }
                }, { value -> if (scale == 1.0) String.format(Locale.US, str(R.string.ui_semitones_2), value)
                    else String.format(Locale.US, "%.1f", value / scale) })
        }
        try {
            voice("pitch", R.string.ui_pitch_relative_to_your_voice, -24, 24, 1.0)
            voice("formant", R.string.ui_formant_voice_texture, -20, 20, 10.0)
        } catch (e: Exception) { showError(e.message ?: str(R.string.ui_check_connection)); return }
        controls.outputGain(body)
        controls.timing(body)
        val scroll = ScrollView(this).apply { addView(body) }
        val dialog = AlertDialog.Builder(this).setTitle(str(R.string.ui_voice_live_settings) + " · " + model.name)
            .setView(scroll).setNegativeButton(str(R.string.ui_close), null)
        if (ShizukuMicController.active) dialog.setPositiveButton(str(R.string.ui_apply_restart)) { _, _ ->
            ShizukuMicController.stop()
            val deadline = SystemClock.elapsedRealtime() + 15000
            val restart = object : Runnable {
                override fun run() {
                    if (isFinishing || isDestroyed) return
                    if (!ShizukuMicController.active && !VirtualMicSession.isActive) begin()
                    else if (SystemClock.elapsedRealtime() < deadline) ui.postDelayed(this, 200)
                    else showError(str(R.string.ui_restart_after_stopping))
                }
            }
            ui.post(restart)
        }
        dialog.show()
    }

    private fun showError(message: String) { localError = message; render() }
    private fun launch(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) showError(str(R.string.ui_could_not_open_the_app_check_that)) else startActivity(intent)
    }
    private fun render() {
        val alive = Shizuku.pingBinder()
        val allowed = alive && runCatching { Shizuku.checkSelfPermission() == 0 }.getOrDefault(false)
        connection.text = if (allowed) str(R.string.ui_shizuku_connected) else if (alive) str(R.string.ui_shizuku_approval_needed) else str(R.string.ui_connect_shizuku)
        connection.setTextColor(if (allowed) getColor(R.color.green) else purple)
        val active = ShizukuMicController.active
        val phase = ShizukuMicController.phase
        val stream = phase == MicPhase.RUNNING
        val ready = phase == MicPhase.WAITING
        if (SystemClock.elapsedRealtime() >= nextNotificationCheck) {
            val manager = getSystemService(android.app.NotificationManager::class.java)
            val enabled = manager.areNotificationsEnabled() && manager.getNotificationChannel(LiveService.CHANNEL)?.importance != android.app.NotificationManager.IMPORTANCE_NONE
            notificationHint.visibility = if (enabled) View.GONE else View.VISIBLE
            nextNotificationCheck = SystemClock.elapsedRealtime() + 2000
        }
        val failure = localError ?: ShizukuMicController.error
        headline.text = when {
            failure != null -> str(R.string.ui_check_connection)
            phase == MicPhase.STOPPING -> str(R.string.ui_stopping_the_microphone)
            phase == MicPhase.BUFFERING -> str(R.string.ui_preparing_audio)
            stream -> str(R.string.ui_sending_your_voice)
            ready -> str(R.string.ui_waiting_for_microphone_input)
            active -> str(R.string.ui_preparing_your_voice)
            !allowed -> str(R.string.ui_connect_to_shizuku_first)
            else -> str(R.string.ui_ready_to_start)
        }
        detail.text = when {
            failure != null -> failure.substringAfter("Microphone bridge did not start: ").takeLast(350)
            phase == MicPhase.STOPPING -> str(R.string.ui_restoring_the_original_microphone_2)
            phase == MicPhase.BUFFERING -> str(R.string.ui_audio_resumes_when_converted_sound_is_ready)
            stream -> if (allApps) str(R.string.ui_sending_converted_input_to_apps_using_the) else str(R.string.ui_sending_converted_input_to, targetName.text, if (otherProfile) str(R.string.ui_other_profile) else "")
            ready -> str(R.string.ui_waiting_without_a_time_limit_start_a)
            active -> str(R.string.ui_loading_the_selected_voice_please_wait)
            !alive -> str(R.string.ui_tap_the_connection_button_above_and_start)
            !allowed -> str(R.string.ui_the_start_button_will_request_shizuku_access)
            else -> str(R.string.ui_choose_an_app_and_voice_then_turn)
        }
        val seconds = if (active) (SystemClock.elapsedRealtime() - ShizukuMicController.startedAt) / 1000 else 0L
        timer.text = if (active) String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60) else ""
        primary.text = if (active) str(R.string.ui_stop_virtual_mic) else str(R.string.ui_start_virtual_mic)
        primary.isEnabled = active || (!VirtualMicSession.isActive && selectedModel.isNotEmpty())
        primary.alpha = if (primary.isEnabled) 1f else .45f
        profile.isEnabled = !active; targetCard.alpha = if (active) .75f else 1f; voiceCard.alpha = 1f
        allAppsSwitch.isEnabled = !active
        val meter = if (stream) VirtualMicSession.meter else VirtualMicSession.Meter()
        fun showLevel(value: Float, bar: ProgressBar, label: TextView) {
            val db = if (value > 0) (20 * log10(value.toDouble())).coerceIn(-60.0, 0.0) else -60.0
            bar.progress = ((db + 60) / 60 * 100).toInt()
            label.text = if (value > 0) "${db.toInt()} dB" else "—"
        }
        showLevel(meter.input, inputBar, inputValue); showLevel(meter.output, outputBar, outputValue)
        metrics.text = str(R.string.ui_processing_ms_ms_block_skipped_inputs_capture, meter.computeMs.toInt(), meter.drops)
    }
    private fun begin(skipNotificationPermission: Boolean = false) {
        if (ShizukuMicController.active || VirtualMicSession.isActive) return
        localError = null
        if (!Shizuku.pingBinder()) { showError(str(R.string.ui_start_shizuku_using_wireless_debugging)); return }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            if (Shizuku.shouldShowRequestPermissionRationale()) showError(str(R.string.ui_allow_voice_studio_in_shizuku_s_authorized))
            else Shizuku.requestPermission(71)
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 72); return
        }
        if (!skipNotificationPermission && Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            && !prefs.getBoolean("notification_permission_requested", false)) {
            prefs.edit().putBoolean("notification_permission_requested", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 73); return
        }
        if (selectedModel.isEmpty()) return
        saveSelection()
        try {
            check(!LiveSession.running) { str(R.string.ui_stop_the_existing_live_conversion_first) }
            startForegroundService(Intent(this, LiveService::class.java).putExtra("virtual_mic", true))
            ShizukuMicController.start(applicationContext, selectedPackage, selectedModel, otherProfile, allApps)
        } catch (e: Throwable) {
            showError(e.message ?: str(R.string.ui_could_not_start_2)); stopService(Intent(this, LiveService::class.java))
        }
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 72 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) begin()
        if (code == 73) begin(skipNotificationPermission = true)
        nextNotificationCheck = 0L
    }
    override fun onDestroy() {
        ui.removeCallbacks(ticker); Shizuku.removeRequestPermissionResultListener(permissionResult); super.onDestroy()
    }
}
