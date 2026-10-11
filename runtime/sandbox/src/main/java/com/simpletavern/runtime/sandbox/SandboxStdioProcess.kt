package com.simpletavern.runtime.sandbox

import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Runtime-only pipes and owned cleanup; never a persisted process/restart handle. */
class SandboxStdioProcess internal constructor(val stdin: OutputStream, val stdout: InputStream,
    val stderr: InputStream, private val release: suspend () -> Unit) {
    private val closing = Mutex()
    private var closed = false
    suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        closing.withLock { if (!closed) { closed = true; release() } }
    }
    override fun toString() = "SandboxStdioProcess(<private>)"
}
