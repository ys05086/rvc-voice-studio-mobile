package com.voicestudio.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock

/** Foreground lifetime and status controls, shared by local and virtual live conversion. */
class LiveService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var virtual = false
    private var startedAt = 0L
    private var lastContent = ""
    private var wakeRenewedAt = 0L
    private var targetPackage = ""
    private var targetLabel = ""
    private lateinit var wakeLock: PowerManager.WakeLock
    private val ticker = object : Runnable {
        override fun run() {
            updateNotification(false)
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceStudio:conversion").apply { setReferenceCounted(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, str(R.string.ui_live_conversion), NotificationManager.IMPORTANCE_LOW).apply {
                description = str(R.string.ui_virtual_microphone_status_and_stop_control)
                setShowBadge(false)
            })
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            ShizukuMicController.stop()
            if (!ShizukuMicController.active) VirtualMicSession.stop()
            LiveSession.stop(this)
            stopSelf()
            return START_NOT_STICKY
        }
        virtual = intent?.getBooleanExtra("virtual_mic", false) == true
        if (startedAt == 0L) startedAt = System.currentTimeMillis()
        updateNotification(true)
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 500)
        return START_NOT_STICKY
    }

    private fun updateNotification(foreground: Boolean) {
        val phase = ShizukuMicController.phase
        val working = if (virtual) phase in setOf(MicPhase.PREPARING, MicPhase.BUFFERING, MicPhase.RUNNING)
            else LiveSession.running
        val now = SystemClock.elapsedRealtime()
        if (working) {
            // Renew a bounded lease only during preparation/capture/conversion, never while idle.
            if (!wakeLock.isHeld || now - wakeRenewedAt >= 5 * 60_000L) {
                wakeLock.acquire(10 * 60_000L); wakeRenewedAt = now
            }
        } else if (wakeLock.isHeld) wakeLock.release()

        val title = if (!virtual) str(R.string.ui_live_conversion_running) else when (phase) {
            MicPhase.WAITING -> str(R.string.ui_virtual_mic_waiting_for_input)
            MicPhase.RUNNING -> str(R.string.ui_virtual_mic_converting)
            MicPhase.BUFFERING -> str(R.string.ui_virtual_mic_buffering)
            MicPhase.STOPPING -> str(R.string.ui_virtual_mic_stopping)
            MicPhase.ERROR -> str(R.string.ui_virtual_mic_check_connection)
            else -> str(R.string.ui_virtual_mic_preparing)
        }
        if (targetPackage != ShizukuMicController.targetPackage) {
            targetPackage = ShizukuMicController.targetPackage
            targetLabel = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(targetPackage, 0)).toString() }
                .getOrDefault(str(R.string.ui_selected_app))
        }
        val destination = if (ShizukuMicController.allApps) str(R.string.ui_all_apps) else targetLabel +
            if (ShizukuMicController.otherProfile) str(R.string.ui_other_profile) else ""
        val body = if (!virtual) str(R.string.ui_converting_and_playing_your_microphone_audio) else when (phase) {
            MicPhase.WAITING -> str(R.string.ui_connects_automatically_when_an_app_uses_the)
            MicPhase.BUFFERING -> str(R.string.ui_audio_will_resume_when_converted_sound_is)
            MicPhase.RUNNING -> str(R.string.ui_sending_converted_voice, destination)
            MicPhase.STOPPING -> str(R.string.ui_restoring_the_original_microphone)
            MicPhase.ERROR -> str(R.string.ui_tap_to_check_the_connection)
            else -> str(R.string.ui_loading_your_voice_model)
        }
        val model = ShizukuMicController.modelName
        val signature = "$title|$body|$destination|$model"
        if (!foreground && signature == lastContent) return
        lastContent = signature
        val open = PendingIntent.getActivity(this, if (virtual) 2 else 0,
            Intent(this, if (virtual) ShizukuMicActivity::class.java else MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, LiveService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic_notification)
            .setContentTitle(title).setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(if (virtual) str(R.string.ui_voice, body, destination, model) else body))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, str(R.string.ui_stop), stop).build())
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setWhen(startedAt).setShowWhen(true).setUsesChronometer(true)
            .setOnlyAlertOnce(true).setOngoing(true)
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (foreground) startForeground(1, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else getSystemService(NotificationManager::class.java).notify(1, builder.build())
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        if (wakeLock.isHeld) wakeLock.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        ShizukuMicController.stop()
        if (!ShizukuMicController.active) VirtualMicSession.stop()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "live"
        const val STOP = "com.voicestudio.phone.STOP_LIVE"
    }
}
