package com.voicestudio.phone

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioIo {
    /** Any audio the phone can decode (m4a, mp3, ogg, wav...) -> 48 kHz mono float. */
    fun decode(context: Context, uri: Uri): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IllegalArgumentException("오디오가 없는 파일이에요")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        val pcm = ByteArrayOutputStream()
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatPcm = false
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        while (true) {
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val size = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = codec.outputFormat
                rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                    f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
            } else if (o >= 0) {
                val buffer = codec.getOutputBuffer(o)!!
                val bytes = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(bytes)
                pcm.write(bytes)
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        codec.stop(); codec.release(); extractor.release()
        val raw = ByteBuffer.wrap(pcm.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        val samples = if (floatPcm) FloatArray(raw.remaining() / 4) { raw.float }
                      else FloatArray(raw.remaining() / 2) { raw.short / 32768f }
        val frames = samples.size / channels
        val mono = FloatArray(frames) { f -> (0 until channels).sumOf { samples[f * channels + it].toDouble() }.toFloat() / channels }
        return Dsp.resample(mono, rate, Rvc.SR)
    }

    fun writeWav(out: OutputStream, x: FloatArray, rate: Int = Rvc.SR) {
        val data = ByteBuffer.allocate(x.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in x) data.putShort((v.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + x.size * 2).put("WAVE".toByteArray())
            .put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(x.size * 2)
        out.write(header.array())
        out.write(data.array())
    }

    fun writeWav(file: File, x: FloatArray) = file.outputStream().use { writeWav(it, x) }

    /** 16-bit PCM WAV (as [writeWav] makes) -> mono float at the file's rate, resampled to 48 kHz. */
    fun readWav(file: File): FloatArray {
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        b.position(12)
        var channels = 1
        var rate = Rvc.SR
        while (b.remaining() >= 8) {
            val id = String(ByteArray(4).also { b.get(it) })
            val size = b.int
            if (id == "fmt ") {
                b.short; channels = b.short.toInt(); rate = b.int
                b.position(b.position() + size - 8)
            } else if (id == "data") {
                val frames = minOf(size, b.remaining()) / 2 / channels
                val x = FloatArray(frames) { f -> (0 until channels).sumOf { b.short.toDouble() }.toFloat() / channels / 32768f }
                return Dsp.resample(x, rate, Rvc.SR)
            } else b.position(b.position() + size + (size and 1))
        }
        throw IllegalArgumentException("WAV 데이터가 없어요")
    }

    fun peakNormalize(x: FloatArray, peakDb: Double = -1.0, maxGainDb: Double = 18.0): FloatArray {
        val peak = x.maxOfOrNull { kotlin.math.abs(it) } ?: return x
        if (peak < 1e-6f) return x
        val gain = minOf(Math.pow(10.0, peakDb / 20) / peak, Math.pow(10.0, maxGainDb / 20)).toFloat()
        return FloatArray(x.size) { x[it] * gain }
    }
}

/**
 * 48 kHz mono capture with the phone's own processing off where it can be: the UNPROCESSED source when the
 * device supports it (no noise suppression, AGC or echo cancelling, so whispers and breath survive), else
 * VOICE_RECOGNITION, which skips most of it.
 */
class Recorder(context: Context) {
    private val source = if ((context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
        MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
    val sourceName = if (source == MediaRecorder.AudioSource.UNPROCESSED) "처리 없는 원음" else "음성 인식용 입력"
    @Volatile private var running = false
    private var thread: Thread? = null
    private val chunks = ArrayList<ShortArray>()
    private val peaks = ArrayList<Float>()
    @Volatile var level = 0f
        private set
    @Volatile var samples = 0
        private set

    /** One peak per 20 ms read so far, for the live waveform. */
    fun peaks(): FloatArray = synchronized(chunks) { peaks.toFloatArray() }

    @SuppressLint("MissingPermission")  // the activity asks for RECORD_AUDIO first
    fun start() {
        val minSize = AudioRecord.getMinBufferSize(Rvc.SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(source, Rvc.SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minSize, Rvc.SR / 5))
        synchronized(chunks) { chunks.clear(); peaks.clear() }
        samples = 0
        running = true
        record.startRecording()
        thread = Thread {
            val buffer = ShortArray(Rvc.SR / 50)
            while (running) {
                val n = record.read(buffer, 0, buffer.size)
                if (n > 0) {
                    var peak = 0
                    for (i in 0 until n) peak = maxOf(peak, kotlin.math.abs(buffer[i].toInt()))
                    level = peak / 32768f
                    synchronized(chunks) { chunks.add(buffer.copyOf(n)); peaks.add(level) }
                    samples += n
                }
            }
            record.stop(); record.release()
        }.also { it.start() }
    }

    fun stop(): FloatArray {
        running = false
        thread?.join()
        level = 0f
        synchronized(chunks) {
            val total = chunks.sumOf { it.size }
            val out = FloatArray(total)
            var i = 0
            for (c in chunks) for (s in c) out[i++] = s / 32768f
            return out
        }
    }
}

/** A 48 kHz mono 16-bit WAV written as it grows (realtime recordings); the sizes in the header are set by [finish]. */
class WavWriter(val file: File) {
    private val out = java.io.RandomAccessFile(file, "rw").apply { setLength(0); write(ByteArray(44)) }
    var samples = 0
        private set

    fun append(x: FloatArray) {
        val data = ByteBuffer.allocate(x.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in x) data.putShort((v.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        out.write(data.array())
        samples += x.size
    }

    fun finish() {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVE".toByteArray())
            .put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(Rvc.SR).putInt(Rvc.SR * 2)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(samples * 2)
        out.seek(0)
        out.write(header.array())
        out.close()
    }
}
