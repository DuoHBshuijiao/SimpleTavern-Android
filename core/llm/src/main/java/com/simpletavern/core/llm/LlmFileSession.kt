package com.simpletavern.core.llm

import com.simpletavern.core.model.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Runtime-only upload ownership for sequential tool rounds on one frozen provider connection. */
class LlmFileSession internal constructor(private val provider: ProviderConfig, private val client: LlmClient,
    private val files: ProviderFileClient) {
    private val operation = Mutex()
    private val closed = AtomicBoolean(false)

    fun stream(request: LlmRequest, beforeExecute: suspend () -> Unit = {},
        onFileTransfers: suspend (List<ProviderFileTransfer>) -> Unit = {}): Flow<StreamEvent> = flow {
        operation.withLock {
            if (closed.get()) throw StError.Conflict("upload session is closed")
            client.streamWithFiles(provider, request, beforeExecute, onFileTransfers, files).collect { emit(it) }
        }
    }

    suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        operation.withLock { if (closed.compareAndSet(false, true)) files.close() }
    }

    override fun toString() = "LlmFileSession(<private>)"
}
