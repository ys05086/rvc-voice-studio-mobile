package com.voicestudio.phone

/** Device hints choose the first attempt; successfully loading/running the model is the real check. */
internal data class ComputePolicy(val tryNpu: Boolean, val tryGpu: Boolean) {
    fun decoder(hasIntegerModel: Boolean, hasGpuModel: Boolean): String = when {
        tryNpu && hasIntegerModel -> "npu_int"
        tryGpu && hasGpuModel -> "gpu"
        else -> "cpu"
    }

    companion object {
        fun from(socManufacturer: String, hardware: String, board: String, vulkan: Boolean): ComputePolicy {
            val vendor = socManufacturer.lowercase(java.util.Locale.ROOT)
            val ids = listOf(hardware, board).map { it.lowercase(java.util.Locale.ROOT) }
            val qualcomm = vendor == "qti" || vendor.contains("qualcomm") || ids.any {
                it.contains("qcom") || it.contains("qualcomm") || it.matches(Regex("(?:msm|sdm|sm)\\d+.*"))
            }
            return ComputePolicy(qualcomm, vulkan)
        }
    }
}
