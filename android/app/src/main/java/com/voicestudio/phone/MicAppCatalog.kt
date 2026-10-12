package com.voicestudio.phone

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest

data class MicTarget(val packageName: String, val label: String, val microphone: Boolean) {
    val verified: Boolean get() = packageName in setOf("com.discord", "com.sec.android.app.voicenote")
}

object MicAppCatalog {
    fun load(context: Context): List<MicTarget> {
        val pm = context.packageManager
        val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        return launchable.map { it.activityInfo.applicationInfo }.distinctBy { it.packageName }
            .filter { it.packageName != context.packageName && it.packageName != "moe.shizuku.privileged.api" && it.uid % 100000 >= 10000 }
            .map { app ->
                val permissions = runCatching { pm.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions }.getOrNull()
                MicTarget(app.packageName, app.loadLabel(pm).toString(), permissions?.contains(Manifest.permission.RECORD_AUDIO) == true)
            }.sortedWith(compareByDescending<MicTarget> { it.verified }
                .thenByDescending { it.packageName == "com.instagram.android" || it.packageName == "com.kakao.talk" }
                .thenByDescending { it.microphone }.thenBy { it.label.lowercase() })
    }
}
