package com.voicestudio.phone

import java.net.URI
import java.util.Locale

internal object WebAddress {
    fun parse(text: String): URI? = runCatching {
        val trimmed = text.trim()
        val uri = URI(if ("://" in trimmed) trimmed else "https://$trimmed")
        uri.takeIf { it.scheme.equals("https", true) && !it.host.isNullOrEmpty() &&
            it.rawUserInfo == null && (it.port == -1 || it.port in 1..65535) }
    }.getOrNull()

    fun origin(text: String?): String? = text?.let { parse(it) }?.let {
        "https://${it.host.lowercase(Locale.ROOT)}" + if (it.port == -1 || it.port == 443) "" else ":${it.port}"
    }

    fun desktopAgent(mobile: String): String = mobile
        .replace(Regex("\\(Linux;[^)]*\\)"), "(X11; Linux x86_64)")
        .replace(" Version/4.0", "").replace(" Mobile", "")
}
