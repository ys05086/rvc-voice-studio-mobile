package com.voicestudio.phone

import android.content.SharedPreferences
import kotlin.math.roundToInt

/** One persistent realtime profile per model folder, shared by Live and the virtual mic. */
class ModelLivePreferences(private val prefs: SharedPreferences, private val modelName: String?) {
    private fun key(name: String) = "voice_live:${modelName?.length}:$modelName:$name"

    init {
        // Freeze the old phone-wide settings once, including an implicit context default.
        // Later edits to one model must never change another model's initial settings.
        synchronized(ModelLivePreferences::class.java) {
            if (modelName != null && !prefs.getBoolean(key("initialized"), false)) {
                val old = LivePreferences.read(getInt = prefs::getInt)
                prefs.edit()
                    .putInt(key("live_block"), old.block)
                    .putInt(key("live_context"), old.context)
                    .putInt(key("live_crossfade"), old.crossfade)
                    .putInt(key("live_cushion_cs"), (old.cushion * 100).roundToInt())
                    .putInt(key("live_threshold_db"), old.thresholdDb.toInt())
                    .putInt(key("live_gain_db"), old.gainDb.toInt())
                    .putBoolean(key("live_split"), prefs.getBoolean("live_split", false))
                    .putBoolean(key("initialized"), true).apply()
            }
        }
    }

    fun getInt(name: String, default: Int): Int = prefs.getInt(if (modelName == null) name else key(name), default)
    fun getBoolean(name: String, default: Boolean): Boolean = prefs.getBoolean(if (modelName == null) name else key(name), default)
    fun putInt(name: String, value: Int) { if (modelName != null) prefs.edit().putInt(key(name), value).apply() }
    fun putBoolean(name: String, value: Boolean) { if (modelName != null) prefs.edit().putBoolean(key(name), value).apply() }
}
