package com.voicestudio.phone

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class ModelLivePreferencesTest {
    /** A persistent in-memory store so tests exercise the production SharedPreferences adapter. */
    private fun memory(values: MutableMap<String, Any> = mutableMapOf()): SharedPreferences {
        val pending = mutableMapOf<String, Any>()
        val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putInt", "putBoolean" -> { pending[args!![0] as String] = args[1]; proxy }
                "apply", "commit" -> { values.putAll(pending); pending.clear(); if (method.name == "commit") true else null }
                else -> error(method.name)
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getInt", "getBoolean" -> values[args!![0]] ?: args[1]
                "edit" -> editor
                else -> error(method.name)
            }
        } as SharedPreferences
    }

    @Test fun modelSwitchAndReopenPreserveIndependentSettings() {
        val prefs = memory(mutableMapOf("live_block" to 31, "live_gain_db" to 4))
        val a = ModelLivePreferences(prefs, "목소리 A")
        a.putInt("live_block", 42)
        a.putInt("live_context", 200)
        a.putInt("live_gain_db", -3)
        a.putBoolean("live_split", true)
        a.putBoolean("live_auto_block", true)
        val b = ModelLivePreferences(prefs, "목소리 B")
        assertEquals(31, b.getInt("live_block", 0))
        assertEquals(4, b.getInt("live_gain_db", 0))
        assertFalse(b.getBoolean("live_split", false))
        assertFalse(b.getBoolean("live_auto_block", false))
        b.putInt("live_context", 70)
        val reopened = ModelLivePreferences(prefs, "목소리 A")
        assertEquals(42, reopened.getInt("live_block", 0))
        assertEquals(200, reopened.getInt("live_context", 0))
        assertEquals(-3, reopened.getInt("live_gain_db", 0))
        assertTrue(reopened.getBoolean("live_split", false))
        assertTrue(reopened.getBoolean("live_auto_block", false))
        assertEquals(31, prefs.getInt("live_block", 0))
    }

    @Test fun migrationKeepsAllExistingValuesForBothModes() {
        val prefs = memory(mutableMapOf("live_block" to 32, "live_context" to 80,
            "live_crossfade" to 10, "live_cushion_cs" to 12, "live_threshold_db" to -48, "live_gain_db" to 6))
        val before = LivePreferences.read(getInt = prefs::getInt)
        val profile = ModelLivePreferences(prefs, "Sample32")
        assertEquals(before, LivePreferences.read(getInt = profile::getInt))
        val virtual = ModelLivePreferences(prefs, "Sample32")
        assertEquals(before.copy(skipQuiet = true), LivePreferences.read(true, virtual::getInt))
        profile.putInt("live_context", 150)
        assertEquals(150, virtual.getInt("live_context", 0))
    }

    @Test fun implicitContextIsFrozenAndMigrationDoesNotRepeat() {
        val prefs = memory()
        val a = ModelLivePreferences(prefs, "A")
        val original = LivePreferences.read(getInt = a::getInt)
        a.putInt("live_block", 50)
        prefs.edit().putInt("live_gain_db", 20).apply()
        val reopened = ModelLivePreferences(prefs, "A")
        assertEquals(original.context, reopened.getInt("live_context", -1))
        assertEquals(original.gainDb.toInt(), reopened.getInt("live_gain_db", -1))
    }

    @Test fun noModelCannotOverwriteDefaultsOrAProfile() {
        val prefs = memory()
        ModelLivePreferences(prefs, null).putInt("live_gain_db", 24)
        assertEquals(12, LivePreferences.read(getInt = prefs::getInt).gainDb.toInt())
        val a = ModelLivePreferences(prefs, "A:live_context")
        a.putInt("live_context", 250)
        assertNotEquals(250, ModelLivePreferences(prefs, "A").getInt("live_context", -1))
    }
}
