package com.voicestudio.phone

import java.io.File
import org.json.JSONObject
import kotlin.math.floor

/** Native decoder geometry. Audio devices and virtual microphone output remain 48 kHz. */
data class ModelSpec(val sampleRate: Int = 48000, val hopLength: Int = sampleRate / 100,
                     val splitHiddenHop: Int = hopLength / 2, val formatVersion: Int = 1) {
    init {
        require(formatVersion == 1) { "Unsupported model metadata version: $formatVersion" }
        require(sampleRate == 32000 || sampleRate == 48000) { "Unsupported model sample rate: $sampleRate" }
        require(hopLength == sampleRate / 100) { "Model sample rate and hop length disagree" }
        require(splitHiddenHop > 0 && hopLength % splitHiddenHop == 0) { "Invalid split decoder geometry" }
    }

    /** Formant restoration and native-rate conversion in one resampling step. */
    fun output48(audio: FloatArray, frames: Int, factor: Double, repeated: Boolean): FloatArray {
        val sourceHop = floor(factor * hopLength).toInt()
        require(sourceHop > 0)
        val input = if (sourceHop == 480) audio else audio.copyOf(minOf(audio.size, frames * sourceHop))
        val output = when {
            sourceHop == 480 -> input
            repeated -> Dsp.resampleRepeated(input, sourceHop, 480)
            else -> Dsp.resample(input, sourceHop, 480)
        }
        return output.copyOf(frames * 480)
    }

    companion object {
        fun load(folder: File): ModelSpec {
            val file = File(folder, "model.json")
            if (!file.exists()) return ModelSpec() // Existing exports are 48 kHz.
            require(file.length() in 1..16384) { "Invalid model.json" }
            val data = JSONObject(file.readText())
            return ModelSpec(data.getInt("sample_rate"), data.getInt("hop_length"),
                data.getInt("split_hidden_hop"), data.getInt("format_version"))
        }

        /** Full replacements must not retain a decoder/metadata from a previous sample rate. */
        fun obsoleteParts(incoming: Set<String>): Set<String> {
            if (!incoming.containsAll(setOf("model.enc.onnx", "model.dec.onnx"))) return emptySet()
            return setOf("model.json", "model.dec.w8a16.onnx", "model.dec.ncnn.param", "model.dec.ncnn.bin",
                "model.dec_a.onnx", "model.dec_b.onnx") - incoming
        }
    }
}
