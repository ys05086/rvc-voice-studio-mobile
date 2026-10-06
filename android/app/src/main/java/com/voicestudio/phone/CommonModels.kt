package com.voicestudio.phone

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The two shared models every voice needs (HuBERT content features, RMVPE pitch): downloaded once from the
 * project's release page and checked against their SHA-256. Voice models are never downloaded; users bring their
 * own. When the download fails, the files can still be put into common/ by hand (or made with the PC converter).
 */
object CommonModels {
    class Item(val name: String, val bytes: Long, val sha256: String)

    const val BASE = "https://github.com/ys05086/rvc-voice-studio-mobile/releases/download/common-v1/"
    val items = listOf(
        Item("hubert.onnx", 377_684_408, "97b93aa2bdbd21dd5bc42feab375d95f107f8b7c22ca0fa0a13548eba7d9df50"),
        Item("rmvpe.onnx", 366_167_422, "6ae60d54b5b2f6633079aa8246637486e2fef8cfbbb9a60386f090e0ddcdf1b4"),
    )
    val totalBytes = items.sumOf { it.bytes }

    /** Blocking, so off the main thread. progress gets 0..1; `stop` is polled and ends the download with an exception.
     *  Files already in place are skipped. */
    fun download(dir: File, progress: (Double) -> Unit, stop: () -> Boolean) {
        dir.mkdirs()
        var done = items.filter { File(dir, it.name).isFile }.sumOf { it.bytes }
        for (item in items) {
            val target = File(dir, item.name)
            if (target.isFile) continue
            val part = File(dir, item.name + ".part")
            val digest = MessageDigest.getInstance("SHA-256")
            val conn = URL(BASE + item.name).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true  // GitHub answers with a redirect to its file host
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            try {
                val code = conn.responseCode
                if (code != 200) throw IllegalStateException("내려받기 주소가 HTTP $code 로 답했어요")
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var got = 0L
                        while (true) {
                            if (stop()) throw InterruptedException("멈췄어요")
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            got += n
                            progress((done + got).toDouble() / totalBytes)
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (hash != item.sha256) {
                part.delete()
                throw IllegalStateException("${item.name} 파일이 손상됐어요 (체크섬이 달라요)")
            }
            if (!part.renameTo(target)) throw IllegalStateException("${item.name}을(를) 저장하지 못했어요")
            done += item.bytes
        }
    }
}
