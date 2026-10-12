package com.voicestudio.phone

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale

/** Shared controls for the Live page and the virtual microphone's settings dialog. */
class LiveSettingsUi(private val context: Context, private val prefs: SharedPreferences,
                     private val virtualMic: Boolean = false, private val changed: () -> Unit = {}) {
    private val refreshers = ArrayList<() -> Unit>()
    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()

    fun refresh() { refreshers.forEach { it() } }

    fun slider(parent: LinearLayout, label: String, min: Int, max: Int, minLabel: String, maxLabel: String,
               read: () -> Int, write: (Int) -> Unit, format: (Int) -> String) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        row.addView(TextView(context).apply { text = label; textSize = 13f }, LinearLayout.LayoutParams(0, -2, 1f))
        val value = TextView(context).apply {
            textSize = 13f; typeface = Typeface.MONOSPACE; setTextColor(context.getColor(R.color.accent_strong))
        }
        row.addView(value); parent.addView(row)
        val bar = SeekBar(context, null, 0, R.style.Slider).apply {
            this.max = max - min; contentDescription = label
        }
        parent.addView(bar, LinearLayout.LayoutParams(-1, dp(40)))
        val scale = LinearLayout(context)
        for ((i, labelText) in listOf(minLabel, maxLabel).withIndex()) scale.addView(TextView(context).apply {
            text = labelText; textSize = 10f; setTextColor(context.getColor(R.color.muted))
            gravity = if (i == 0) Gravity.START else Gravity.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(scale)
        val refresh = {
            val n = read().coerceIn(min, max)
            bar.progress = n - min; value.text = format(n)
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                value.text = format(progress + min)
                if (fromUser) { write(progress + min); changed() }
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) { refresh() }
        })
        refreshers.add(refresh); refresh()
    }

    private fun params() = LivePreferences.read(virtualMic, prefs::getInt)

    fun timing(parent: LinearLayout) {
        fun setting(key: String, label: Int, min: Int, max: Int, low: String, high: String,
                    read: () -> Int, format: (Int) -> String) = slider(parent, str(label), min, max, low, high,
            read, { prefs.edit().putInt(key, it).apply(); refresh() }, format)
        val seconds = { n: Int -> String.format(Locale.US, str(R.string.ui_s), n / 100.0) }
        setting("live_block", R.string.ui_block_audio_converted_per_step, 15, 50,
            str(R.string.ui_faster), str(R.string.ui_more_headroom), { params().block }, seconds)
        setting("live_context", R.string.ui_additional_context, 0, LiveParams.MAX_CONTEXT, "0", "3.00",
            { params().context }, seconds)
        parent.addView(TextView(context).apply {
            text = str(R.string.ui_additional_context_help); textSize = 11f; setTextColor(context.getColor(R.color.muted))
        })
        setting("live_crossfade", R.string.ui_crossfade, 4, 12, "0.04", "0.12", { params().crossfade }, seconds)
        setting("live_cushion_cs", R.string.ui_output_buffer_reduce_dropouts, 2, 20, "0.02", "0.20",
            { prefs.getInt("live_cushion_cs", 6) }, seconds)
        setting("live_threshold_db", R.string.ui_silence_threshold, -80, -30, "−80 dB", "−30 dB",
            { params().thresholdDb.toInt() }, { "$it dB" })
    }

    fun outputGain(parent: LinearLayout) = slider(parent, str(R.string.ui_output_gain), -12, 24, "−12 dB", "+24 dB",
        { params().gainDb.toInt() }, { prefs.edit().putInt("live_gain_db", it).apply() },
        { String.format(Locale.US, "%+d dB", it) })
}
