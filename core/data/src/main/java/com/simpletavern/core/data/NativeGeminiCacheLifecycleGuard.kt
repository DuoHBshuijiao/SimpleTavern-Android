package com.simpletavern.core.data

import com.simpletavern.core.model.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Iterative bounded graph audit. Equal timestamps cannot hide a causal cycle. No recursion or remote work. */
internal class NativeGeminiCacheLifecycleGuard(
    private val read: suspend (String) -> String?, private val issue: (NativeRestoreIssue) -> Unit,
) {
    private val done = mutableSetOf<String>()
    private var bytes = 0L
    private var visited = 0
    suspend fun inspect(id: String) {
        if (id in done) return
        data class Frame(val id: String, val parents: Iterator<String>)
        val path = mutableSetOf<String>()
        val stack = java.util.ArrayDeque<Frame>()
        suspend fun push(key: String) {
            if (++visited > 100000) throw StError.Validation("native cache lifecycle exceeds node budget")
            val raw = read(key)
            if (raw == null) { done.add(key); return }
            bytes += raw.toByteArray(Charsets.UTF_8).size
            if (bytes > 67_108_864) throw StError.Validation("native cache lifecycle exceeds 64 MiB read budget")
            val operation = GeminiCacheAccounting.operation(raw)
            if (operation.taskId != key) throw StError.Validation("native cache lifecycle task identity mismatch")
            val parents = if (operation.lifecycleVersion == 1) operation.lifecyclePreviousTaskIds else listOfNotNull(operation.referenceTaskId)
            path.add(key); stack.addLast(Frame(key, parents.iterator()))
        }
        push(id)
        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val frame = stack.peekLast() ?: throw StError.Validation("cache lifecycle traversal state missing")
            if (!frame.parents.hasNext()) { stack.removeLast(); path.remove(frame.id); done.add(frame.id); continue }
            val parent = frame.parents.next()
            if (parent in path) {
                issue(NativeRestoreIssue("CACHE_LIFECYCLE_CYCLE", "tasks", buildJsonObject { put("id", id) }.toString().take(2048), column = "payloadJson"))
            } else if (parent !in done) push(parent)
        }
    }
}
