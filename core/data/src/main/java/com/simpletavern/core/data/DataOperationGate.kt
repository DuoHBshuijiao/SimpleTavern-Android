package com.simpletavern.core.data

import com.simpletavern.core.model.StError

/** Concurrent ordinary writes; exclusive native snapshot/restore refuses busy operations. No replay queue. */
class DataOperationGate {
    private val monitor = Any()
    private var shared = 0
    private var exclusiveOwner: String? = null
    private var recoveryRequired = false

    /** An uncommitted restore with incomplete undo must not be reused by another writer/restore. */
    fun requireRecovery() = synchronized(monitor) { recoveryRequired = true }

    fun sharedLease(): AutoCloseable = synchronized(monitor) {
        if (recoveryRequired) throw StError.Conflict("pending data recovery must be resolved on process start")
        if (exclusiveOwner != null) throw StError.Conflict("native backup or restore currently owns the data snapshot")
        shared++
        var closed = false
        AutoCloseable { synchronized(monitor) { if (!closed) { closed = true; shared-- } } }
    }

    fun exclusiveLease(owner: String): AutoCloseable = synchronized(monitor) {
        if (recoveryRequired) throw StError.Conflict("pending data recovery must be resolved on process start")
        if (exclusiveOwner != null || shared != 0) throw StError.Conflict("data operations prevent a consistent native snapshot")
        exclusiveOwner = owner
        var closed = false
        AutoCloseable { synchronized(monitor) { if (!closed) { closed = true; exclusiveOwner = null } } }
    }

    suspend fun <T> write(block: suspend () -> T): T {
        val lease = sharedLease()
        try { return block() } finally { lease.close() }
    }
}
