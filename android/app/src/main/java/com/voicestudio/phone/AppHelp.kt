package com.voicestudio.phone

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.widget.ScrollView
import android.widget.TextView

internal fun Activity.showUsage() {
    val content = TextView(this).apply {
        text = str(R.string.usage_steps)
        textSize = 15f
        val padding = (24 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding / 2, padding, padding)
        setTextIsSelectable(true)
    }
    AlertDialog.Builder(this).setTitle(str(R.string.usage_title))
        .setView(ScrollView(this).apply { addView(content) })
        .setPositiveButton(str(R.string.ui_close), null)
        .setNeutralButton(str(R.string.shizuku_setup_guide)) { _, _ ->
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/guide/setup/")))
        }.show()
}
