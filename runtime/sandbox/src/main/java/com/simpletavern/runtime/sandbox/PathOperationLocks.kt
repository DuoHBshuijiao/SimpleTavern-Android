package com.simpletavern.runtime.sandbox

import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Only overlapping paths conflict; a directory rename also excludes its descendants. */
internal class PathOperationLocks {
    private val monitor = Any()
    private val active = linkedMapOf<String, List<String>>()
    private val waiters = mutableSetOf<CompletableDeferred<Unit>>()

    suspend fun acquire(paths: List<String>): AutoCloseable {
        while (true) {
            currentCoroutineContext().ensureActive()
            val waiter = CompletableDeferred<Unit>()
            val lease = synchronized(monitor) {
                val conflict = active.values.any { held -> held.any { a -> paths.any { b ->
                    a == b || a.startsWith(b + File.separator) || b.startsWith(a + File.separator)
                } } }
                if (conflict) { waiters += waiter; null } else {
                    val id = UUID.randomUUID().toString()
                    active[id] = paths
                    AutoCloseable {
                        val notify = synchronized(monitor) {
                            active.remove(id)
                            waiters.toList().also { waiters.clear() }
                        }
                        notify.forEach { it.complete(Unit) }
                    }
                }
            }
            if (lease != null) return lease
            try { waiter.await() }
            finally { synchronized(monitor) { waiters.remove(waiter) } }
        }
    }
}
