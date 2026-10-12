package com.voicestudio.phone

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Process
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Runs as Shizuku's shell UID. Only launches our bundled, fixed microphone entry point. */
class VirtualMicUserService(private val context: Context) : IVirtualMicService.Stub() {
    private val appUid = context.applicationInfo.uid
    private val lines = ArrayDeque<String>()
    @Volatile private var child: java.lang.Process? = null
    @Volatile private var logReader: Thread? = null
    @Volatile private var port = 0
    private var token = ""
    private var owner: IBinder? = null
    private val died = IBinder.DeathRecipient { stopChild() }

    private fun checkCaller() {
        check(Binder.getCallingUid() == appUid) { "Only Voice Studio may control this service" }
    }

    override fun startMic(targetPackage: String, seconds: Int, otherProfile: Boolean, allApps: Boolean, owner: IBinder): String {
        checkCaller()
        check(Process.myUid() == 2000) { "Start Shizuku with wireless debugging (shell UID 2000)" }
        require(targetPackage.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")))
        require(targetPackage != context.packageName && (seconds == 0 || seconds in 5..300))
        require(!allApps || (!otherProfile && seconds == 0))
        synchronized(this) {
            check(child?.isAlive != true) { "Microphone session already running" }
            port = 0
            synchronized(lines) { lines.clear() }
            token = "virtual-mic-${UUID.randomUUID()}"
            this.owner = owner
            owner.linkToDeath(died, 0)
            val command = mutableListOf("/system/bin/app_process", "/", "dev.virtualmic.Main",
                "--package", targetPackage, "--mic", "--seconds", seconds.toString(), "--bridge", token,
                "--watch-parent")
            if (otherProfile) command += "--other-profile"
            if (allApps) command += "--all-apps"
            val builder = ProcessBuilder(command).redirectErrorStream(true)
            builder.environment()["CLASSPATH"] = context.applicationInfo.sourceDir
            try {
                val process = builder.start()
                child = process
                logReader = thread(name = "virtual-mic-shell-log") {
                    process.inputStream.bufferedReader().useLines { stream ->
                        stream.forEach { line ->
                            synchronized(lines) { lines.addLast(line); while (lines.size > 700) lines.removeFirst() }
                            Regex("BRIDGE_LISTENING port=(\\d+)").find(line)?.let { port = it.groupValues[1].toInt() }
                        }
                    }
                }
            } catch (e: Throwable) {
                owner.unlinkToDeath(died, 0); this.owner = null
                throw e
            }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (port == 0 && child?.isAlive == true && System.nanoTime() < deadline) Thread.sleep(50)
        if (port == 0) {
            val detail = synchronized(lines) {
                lines.lastOrNull { "FAILED stage=" in it }?.substringAfter("message=")
                    ?: "Microphone setup failed. Check Shizuku and the destination app."
            }
            stopChild()
            return JSONObject().put("error", detail).toString()
        }
        return snapshot()
    }

    override fun snapshot(): String {
        checkCaller()
        return JSONObject().put("uid", Process.myUid()).put("running", child?.isAlive == true)
            .put("port", port).put("token", token)
            .put("log", synchronized(lines) { lines.joinToString("\n") }).toString()
    }

    override fun stopMic() { checkCaller(); stopChild() }

    @Synchronized private fun stopChild() {
        val process = child
        // EOF on the private pipe asks Main to exit and run its policy cleanup hook.
        runCatching { process?.outputStream?.close() }
        if (process != null && !process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroy()
            if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        logReader?.join(1000)
        owner?.let { runCatching { it.unlinkToDeath(died, 0) } }
        owner = null
    }

    override fun destroy() { stopChild(); kotlin.system.exitProcess(0) }
}
