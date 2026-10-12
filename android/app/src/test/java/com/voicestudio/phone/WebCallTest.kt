package com.voicestudio.phone

import org.junit.Assert.*
import org.junit.Test

class WebCallTest {
    @Test fun queueRequiresConnectionAndDisconnectErasesConversation() {
        val q = WebAudioQueue(8)
        q.offer(floatArrayOf(.1f)); assertEquals(0, q.take().size)
        q.enable(); q.offer(floatArrayOf(.2f, .3f)); q.disable()
        q.enable(); assertEquals(0, q.take().size)
        q.offer(floatArrayOf(.4f)); assertArrayEquals(floatArrayOf(.4f), q.take(), 0f)
    }

    @Test fun browserStallKeepsOnlyNewestAudioAndReadsInOrder() {
        val q = WebAudioQueue(4); q.enable()
        q.offer(floatArrayOf(.1f, .2f, .3f)); q.offer(floatArrayOf(.4f, .5f, .6f))
        assertArrayEquals(floatArrayOf(.3f, .4f), q.take(2), 0f)
        assertArrayEquals(floatArrayOf(.5f, .6f), q.take(), 0f)
        assertEquals(0, q.take().size)
    }

    @Test fun pcmSanitizesInvalidSamples() {
        val q = WebAudioQueue(); q.enable(); q.offer(floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, -2f, 2f))
        assertArrayEquals(floatArrayOf(0f, 0f, -1f, 1f), q.take(), 0f)
    }

    @Test fun onlyHttpsSitesCanReceiveAudio() {
        for (url in listOf("http://example.com", "file:///secret", "javascript:alert(1)", "https://a@b.com", "https://", "https://x.com:99999"))
            assertNull(url, WebAddress.parse(url))
        assertEquals("https://discord.com/app", WebAddress.parse(" discord.com/app ").toString())
        assertEquals("https://example.com", WebAddress.origin("https://EXAMPLE.com:443/path"))
        assertEquals("https://example.com:8443", WebAddress.origin("https://example.com:8443/path"))
        assertNotEquals(WebAddress.origin("https://example.com"), WebAddress.origin("https://example.com.evil.test"))
    }

    @Test fun desktopIdentityPreservesInstalledChromiumVersion() {
        val mobile = "Mozilla/5.0 (Linux; Android 16; SM-S916N Build/XYZ; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/140.0.1.2 Mobile Safari/537.36"
        val desktop = WebAddress.desktopAgent(mobile)
        assertTrue(desktop.contains("Chrome/140.0.1.2")); assertTrue(desktop.contains("X11; Linux x86_64"))
        assertFalse(desktop.contains("Android")); assertFalse(desktop.contains("Mobile")); assertFalse(desktop.contains("wv"))
    }
}
