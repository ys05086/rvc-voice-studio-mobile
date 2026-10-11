package com.voicestudio.phone

/** Owns one backend at a time. Retry a failed accelerator operation once on CPU. */
internal class BackendFallback<T : AutoCloseable>(
    primary: () -> T,
    private val fallback: (() -> T)? = null,
    private val onFallback: (Throwable) -> Unit = {},
) : AutoCloseable {
    @Volatile var usingFallback = false
        private set
    private var resource: T? = null

    init {
        resource = try { primary() } catch (failure: Throwable) { recover(failure) }
    }

    private fun recover(failure: Throwable): T {
        if (fallback == null || usingFallback || !recoverable(failure)) throw failure
        resource?.let { runCatching { it.close() } }
        resource = null
        usingFallback = true
        val next = try { fallback() } catch (cpuFailure: Throwable) {
            if (cpuFailure !== failure) cpuFailure.addSuppressed(failure)
            throw cpuFailure
        }
        onFallback(failure)
        return next
    }

    fun <R> run(action: (T) -> R): R {
        val current = checkNotNull(resource) { "Backend is closed" }
        return try { action(current) } catch (failure: Throwable) {
            val next = recover(failure)
            resource = next
            action(next)
        }
    }

    override fun close() {
        val previous = resource
        resource = null
        previous?.close()
    }

    companion object {
        fun recoverable(t: Throwable): Boolean = (t is Exception || t is LinkageError) &&
            t !is InterruptedException && t !is java.util.concurrent.CancellationException
    }
}
