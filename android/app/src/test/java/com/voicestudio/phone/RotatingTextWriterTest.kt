package com.voicestudio.phone

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class RotatingTextWriterTest {
    @Test fun longDiagnosticSessionKeepsTwoChunksWithHeaders() {
        val dir = Files.createTempDirectory("mic-log-test").toFile()
        try {
            val file = File(dir, "blocks.csv")
            RotatingTextWriter(file, "header\n", 64).use { out -> repeat(1000) { out.write("$it,sample\n") } }
            val previous = File(dir, "blocks.previous.csv")
            assertTrue(file.readText().startsWith("header\n"))
            assertTrue(previous.readText().startsWith("header\n"))
            assertTrue(file.length() <= 64 && previous.length() <= 64)
            assertTrue(file.readText().contains("999,sample"))
            assertEquals(2, dir.listFiles()!!.size)
        } finally { dir.deleteRecursively() }
    }
}
