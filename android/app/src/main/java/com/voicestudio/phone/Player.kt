package com.voicestudio.phone

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper

/**
 * Plays a float clip from any sample through a streaming AudioTrack and moves the waveform's playhead.
 * One clip at a time: starting another stops the current one.
 */
class Player {
    private val ui = Handler(Looper.getMainLooper())
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private var start = 0
    private var view: WaveformView? = null
    private var clip: FloatArray? = null
    var onStateChange: ((WaveformView?, Boolean) -> Unit)? = null

    val playing get() = running

    fun isPlaying(v: WaveformView) = running && view === v

    fun play(v: WaveformView, x: FloatArray, from: Int) {
        stop()
        if (x.isEmpty()) return
        val at = if (from >= x.size - 1) 0 else from.coerceAtLeast(0)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(Rvc.SR).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(Rvc.SR / 5 * 4 * 2)  // 0.4 s
            .build()
        track = t; view = v; clip = x; start = at; running = true
        // fill the buffer before starting, so playback does not open on an underrun (a stutter)
        var i = at + t.write(x, at, minOf(Rvc.SR / 5, x.size - at), AudioTrack.WRITE_NON_BLOCKING).coerceAtLeast(0)
        t.play()
        thread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            while (running && i < x.size) {
                val n = minOf(4800, x.size - i)
                t.write(x, i, n, AudioTrack.WRITE_BLOCKING)
                i += n
            }
            // let the buffered tail play out, then report the end
            while (running && t.playbackHeadPosition < x.size - at) Thread.sleep(20)
            if (t.underrunCount > 0) android.util.Log.w("VoiceStudio", "playback underruns: ${t.underrunCount}")
            if (running) ui.post { finish(v, x.size - 1) }
        }.also { it.start() }
        onStateChange?.invoke(v, true)
        tick()
    }

    private fun tick() {
        val t = track ?: return
        val v = view ?: return
        if (!running) return
        v.position = (start + t.playbackHeadPosition).coerceAtMost((clip?.size ?: 1) - 1)
        ui.postDelayed({ tick() }, 33)
    }

    private fun finish(v: WaveformView, at: Int) {
        stop()
        v.position = at
    }

    fun stop() {
        val v = view
        running = false
        thread?.join(500)
        thread = null
        track?.let { runCatching { it.pause(); it.flush(); it.release() } }
        track = null
        view = null
        clip = null
        if (v != null) onStateChange?.invoke(v, false)
    }
}
