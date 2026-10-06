package com.voicestudio.phone

import java.io.File

/**
 * The voice decoder on ncnn: the GPU through Vulkan in 32 bits (fp16 off), or ncnn's CPU path when there is no GPU.
 * The graph (make_ncnn.py) takes fixed pieces of [Rvc.DEC_FRAMES] frames: z [192 * frames], sine [frames * 480].
 */
class NcnnDecoder(param: File, model: File, gpu: Boolean, threads: Int) : AutoCloseable {
    private var handle = nativeCreate(param.path, model.path, gpu, threads)

    init {
        require(handle != 0L) { "ncnn 디코더를 열지 못했어요 (${param.name})" }
    }

    val usesGpu: Boolean get() = handle != 0L && nativeUsesGpu(handle)

    fun decode(z: FloatArray, frames: Int, sine: FloatArray): FloatArray =
        nativeDecode(handle, z, frames, sine) ?: throw IllegalStateException("ncnn 디코더가 실패했어요")

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    companion object {
        init {
            System.loadLibrary("vsdecoder")
        }

        @JvmStatic private external fun nativeCreate(param: String, model: String, gpu: Boolean, threads: Int): Long
        @JvmStatic private external fun nativeUsesGpu(handle: Long): Boolean
        @JvmStatic private external fun nativeDecode(handle: Long, z: FloatArray, frames: Int, sine: FloatArray): FloatArray?
        @JvmStatic private external fun nativeDestroy(handle: Long)
    }
}
