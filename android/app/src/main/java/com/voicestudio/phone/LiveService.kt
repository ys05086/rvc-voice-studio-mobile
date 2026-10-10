package com.voicestudio.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Keeps the app in the foreground (microphone type) while realtime conversion runs, so it goes on with the screen
 * off or another app in front; Android silences the microphone of apps in the background. The conversion itself
 * lives in [LiveSession]; this only holds the notification, whose "끄기" stops it.
 */
class LiveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            LiveSession.stop(this)
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "실시간 변환", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, LiveService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_app_fg)
            .setContentTitle("실시간 변환 중")
            .setContentText("마이크 소리를 바꿔서 내보내고 있어요")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "끄기", stop).build())
            .setOngoing(true)
            .build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        return START_NOT_STICKY
    }

    companion object {
        const val CHANNEL = "live"
        const val STOP = "com.voicestudio.phone.STOP_LIVE"
    }
}
