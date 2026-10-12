package com.voicestudio.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** App-side lifetime owner; no Activity is held while Discord is in the foreground. */
object ShizukuMicController {
    @Volatile var active = false; private set
    @Volatile var status = str(R.string.ui_virtual_microphone_is_off); private set
    @Volatile var shellLog = ""; private set
    @Volatile var targetPackage = ""; private set
    @Volatile var modelName = ""; private set
    @Volatile var startedAt = 0L; private set
    @Volatile var error: String? = null; private set
    @Volatile var otherProfile = false; private set
    @Volatile var allApps = false; private set
    @Volatile private var cancelled = AtomicBoolean(false)
    @Volatile private var remote: IVirtualMicService? = null
    val phase: MicPhase get() = MicPhase.resolve(active, cancelled.get(), error, shellLog)

    @Synchronized fun start(context: Context, target: String, model: String, useOtherProfile: Boolean = false, useAllApps: Boolean = false) {
        check(!active && !VirtualMicSession.isActive && !LiveSession.running)
        check(Shizuku.pingBinder() && Shizuku.checkSelfPermission() == 0)
        check(Shizuku.getUid() == 2000) { str(R.string.ui_start_shizuku_using_wireless_debugging_2) }
        val app = context.applicationContext
        active = true
        targetPackage = target
        modelName = model
        otherProfile = useOtherProfile
        allApps = useAllApps
        startedAt = android.os.SystemClock.elapsedRealtime()
        error = null
        val cancel = AtomicBoolean(false)
        cancelled = cancel
        status = str(R.string.ui_preparing_the_microphone_with_shizuku)
        shellLog = ""
        val ready = CountDownLatch(1)
        val owner = Binder()
        val args = Shizuku.UserServiceArgs(ComponentName(app, VirtualMicUserService::class.java))
            .daemon(false).processNameSuffix("virtual_mic_service").debuggable(false).version(3)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder != null) remote = IVirtualMicService.Stub.asInterface(binder)
                ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                remote = null
                if (active) { status = str(R.string.ui_shizuku_disconnected); cancel.set(true); VirtualMicSession.stop() }
                ready.countDown()
            }
        }
        try { Shizuku.bindUserService(args, connection) }
        catch (e: Throwable) { active = false; throw e }
        thread(name = "shizuku-mic-controller") {
            var converterStarted = false
            val log = File(app.getExternalFilesDir(null), "virtual_mic/shizuku-${System.currentTimeMillis()}.log")
            log.parentFile?.mkdirs()
            try {
                check(ready.await(20, TimeUnit.SECONDS)) { str(R.string.ui_shizuku_service_connection_timed_out) }
                if (cancel.get()) return@thread
                val service = remote ?: error(str(R.string.ui_could_not_connect_to_the_shizuku_service))
                val state = JSONObject(service.startMic(target, 0, useOtherProfile, useAllApps, owner))
                if (state.has("error")) error(state.getString("error"))
                shellLog = state.getString("log")
                if (cancel.get()) return@thread
                val intent = Intent().putExtra("virtual_mic_socket", state.getString("token"))
                    .putExtra("virtual_mic_port", state.getInt("port")).putExtra("model", model)
                    .putExtra("bounded_logs", true).putExtra("persistent_mic", true)
                VirtualMicSession.start(app, intent)
                converterStarted = true
                while (!cancel.get() && VirtualMicSession.isActive) {
                    val next = JSONObject(service.snapshot())
                    shellLog = next.getString("log")
                    status = VirtualMicSession.status
                    if (!next.getBoolean("running")) VirtualMicSession.stop()
                    Thread.sleep(500)
                }
                status = if (cancel.get()) str(R.string.ui_virtual_microphone_is_off_apps_return_to)
                    else VirtualMicSession.status + str(R.string.ui_apps_return_to_their_original_microphone)
            } catch (e: Throwable) {
                if (!cancel.get()) error = e.message ?: str(R.string.ui_check_the_connection)
                status = if (cancel.get()) str(R.string.ui_virtual_microphone_is_off_2) else str(R.string.ui_could_not_start_3, e.message)
                android.util.Log.e("ShizukuMic", status, e)
            } finally {
                runCatching { remote?.stopMic() }
                VirtualMicSession.stop()
                runCatching { remote?.snapshot()?.let { shellLog = JSONObject(it).getString("log") } }
                if (!cancel.get()) shellLog.lineSequence().lastOrNull { "FAILED stage=" in it }?.let {
                    error = it.substringAfter("message=")
                }
                if (converterStarted) VirtualMicSession.failureMessage?.let { error = it }
                runCatching { log.writeText(shellLog + "\nAPP_STATUS: " + status) }
                runCatching { Shizuku.unbindUserService(args, connection, true) }
                remote = null
                app.stopService(Intent(app, LiveService::class.java))
                active = false
            }
        }
    }

    fun stop() {
        cancelled.set(true)
        if (!active) VirtualMicSession.stop()
    }
}
