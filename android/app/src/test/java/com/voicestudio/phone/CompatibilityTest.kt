package com.voicestudio.phone

import org.junit.Assert.*
import org.junit.Test

class CompatibilityTest {
    private class FakeBackend(val label: String) : AutoCloseable {
        var closes = 0
        override fun close() { closes++ }
    }

    @Test fun nonQualcommPhonesChoosePortableBackends() {
        for (vendor in listOf("Samsung", "MediaTek", "Google", "unknown")) {
            val policy = ComputePolicy.from(vendor, "tensor", "board", true)
            assertFalse(policy.tryNpu)
            assertEquals("gpu", policy.decoder(true, true))
            assertEquals("cpu", policy.decoder(true, false))
        }
    }

    @Test fun olderAndroidQualcommHintsAndMissingGpuModels() {
        val policy = ComputePolicy.from("", "qcom", "kona", true)
        assertTrue(policy.tryNpu)
        assertEquals("npu_int", policy.decoder(true, true))
        assertEquals("gpu", policy.decoder(false, true))
        assertEquals("cpu", policy.decoder(false, false))
        assertTrue(ComputePolicy.from("QTI", "", "", false).tryNpu)
        assertFalse(ComputePolicy.from("Samsung", "exynos", "", false).tryNpu)
    }

    @Test fun missingAcceleratorLibraryFallsBackAtLoad() {
        val cpu = FakeBackend("cpu")
        var notices = 0
        BackendFallback(primary = { throw UnsatisfiedLinkError("vendor library missing") },
            fallback = { cpu }, onFallback = { notices++ }).use {
            assertEquals("cpu", it.run { backend -> backend.label })
            assertTrue(it.usingFallback)
        }
        assertEquals(1, notices)
        assertEquals(1, cpu.closes)
    }

    @Test fun executionFailureReplaysSameInputOnceAndReleasesAccelerator() {
        val npu = FakeBackend("npu")
        val cpu = FakeBackend("cpu")
        val calls = mutableListOf<String>()
        BackendFallback({ npu }, { cpu }).use { selected ->
            fun calculate(input: Int) = selected.run {
                calls.add("${it.label}:$input")
                if (it === npu) throw IllegalStateException("execution failed")
                input * 2
            }
            assertEquals(14, calculate(7))
            assertEquals(18, calculate(9))
            assertEquals(listOf("npu:7", "cpu:7", "cpu:9"), calls)
            assertEquals(1, npu.closes)
        }
        assertEquals(1, npu.closes)
        assertEquals(1, cpu.closes)
    }

    @Test fun cpuFailureIsNotRetriedForever() {
        val npu = FakeBackend("npu")
        val cpu = FakeBackend("cpu")
        var cpuLoads = 0
        BackendFallback({ npu }, { cpuLoads++; cpu }).use { selected ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                selected.run { throw IllegalArgumentException(it.label) }
            }
            assertEquals("cpu", failure.message)
            assertEquals(1, cpuLoads)
        }
        assertEquals(1, cpu.closes)
    }

    @Test fun strictDiagnosticModeDoesNotFallback() {
        val failure = assertThrows(IllegalStateException::class.java) {
            BackendFallback<FakeBackend>({ throw IllegalStateException("npu failure") })
        }
        assertEquals("npu failure", failure.message)
    }

    @Test fun fatalMemoryFailureDoesNotStartAnotherModel() {
        var attempts = 0
        assertThrows(OutOfMemoryError::class.java) {
            BackendFallback({ throw OutOfMemoryError() }, { attempts++; FakeBackend("cpu") })
        }
        assertEquals(0, attempts)
    }

    @Test fun bothInitializationErrorsArePreserved() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            BackendFallback<FakeBackend>({ throw IllegalStateException("npu") }, { throw IllegalArgumentException("cpu") })
        }
        assertEquals("npu", failure.suppressed.single().message)
    }
}
