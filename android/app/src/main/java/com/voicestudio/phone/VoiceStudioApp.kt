package com.voicestudio.phone

import android.app.Application

/** Application resources also serve UI messages produced by the background conversion workers. */
class VoiceStudioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        appResources = this
    }
}

private lateinit var appResources: Application

internal fun str(id: Int, vararg args: Any?): String =
    if (args.isEmpty()) appResources.getString(id) else appResources.getString(id, *args)
