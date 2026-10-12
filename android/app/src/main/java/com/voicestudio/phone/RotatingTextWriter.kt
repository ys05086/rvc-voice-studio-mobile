package com.voicestudio.phone

import java.io.File
import java.io.Writer

/** Keeps the current and previous diagnostic chunk, regardless of session length. */
class RotatingTextWriter(private val file: File, private val header: String = "", private val limit: Int = 4 * 1024 * 1024) : Writer() {
    private var writer = file.bufferedWriter()
    private var count = header.length
    init { writer.write(header) }
    override fun write(chars: CharArray, offset: Int, length: Int) {
        if (count + length > limit && count > header.length) {
            writer.close()
            val previous = File(file.parentFile, file.nameWithoutExtension + ".previous." + file.extension)
            file.copyTo(previous, overwrite = true)
            writer = file.bufferedWriter()
            writer.write(header)
            count = header.length
        }
        writer.write(chars, offset, length)
        count += length
    }
    override fun flush() = writer.flush()
    override fun close() = writer.close()
}
