package com.simpletavern.core.data

import com.simpletavern.core.model.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job

/** First-cause cancellation can precede the Room registration/write; never persists or replays work. */
class WorkerStopSignals {
    private val owned = ConcurrentHashMap.newKeySet<String>()
    private val reasons = ConcurrentHashMap<String, TaskTerminalReason>()
    @Synchronized fun register(id: String) { owned += id }
    @Synchronized fun forget(id: String) { owned.remove(id); reasons.remove(id) }
    @Synchronized fun first(id: String, reason: TaskTerminalReason): TaskTerminalReason =
        if (id in owned) reasons.putIfAbsent(id, reason) ?: reason else reason
    fun bind(id: String, worker: Job) { if (reasons.containsKey(id)) worker.cancel() }
    fun interrupt(reason: TaskTerminalReason, workers: Map<String, Job>) {
        require(reason in setOf(TaskTerminalReason.HOST_TIMEOUT, TaskTerminalReason.HOST_UNAVAILABLE))
        owned.forEach { first(it, reason) }
        workers.forEach { (id, job) -> first(id, reason); job.cancel() }
    }
    fun effective(task: TaskSnapshot): TaskSnapshot = reasons[task.id]?.let {
        if (task.status.isTerminal || task.status == TaskStatus.STOPPING) task
        else task.copy(status = TaskStatus.STOPPING, terminalReason = it)
    } ?: task
    fun completionReason(task: TaskSnapshot, status: TaskStatus) =
        if (status == TaskStatus.SUCCEEDED) TaskTerminalReason.COMPLETED else effective(task).completionReason(status)
}
