package com.simpletavern.core.tools

import com.simpletavern.core.data.ChatRepository
import com.simpletavern.core.data.FileChangeStore
import com.simpletavern.core.data.Jsonx
import com.simpletavern.core.data.TaskStore
import com.simpletavern.core.data.WorkerStopSignals
import com.simpletavern.core.model.*
import com.simpletavern.runtime.sandbox.SandboxManager
import com.simpletavern.runtime.sandbox.ShellOutput
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class SandboxTaskProgress(
    val taskId: String, val sandboxId: String, val toolName: String, val argumentsJson: String,
    val approvalSessionId: String, val callId: String,
    val shellOutput: ShellOutput? = null, val resultJson: String? = null,
    val resultText: String? = null, val updatedAt: String,
    val resultTextTruncated: Boolean = false,
)

/** Detached from caller/UI lifetime, but not resumed or replayed after process death. */
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
class SandboxTaskService(
    private val tasks: TaskStore, private val sandbox: SandboxManager, private val tools: ToolRuntime,
    private val chats: ChatRepository, private val changes: FileChangeStore, private val scope: CoroutineScope,
) {
    private val control = Mutex()
    private val workers = ConcurrentHashMap<String, Job>()
    private val stopSignals = WorkerStopSignals()
    fun signalHostInterruption(reason: TaskTerminalReason) = stopSignals.interrupt(reason, workers)

    suspend fun start(sandboxId: String, toolName: String, argumentsJson: String, chatId: String? = null,
                      requestedId: String? = null): TaskSnapshot =
        control.withLock {
            if (toolName !in ToolRuntime.SUPPORTED) throw StError.Unsupported("unknown sandbox tool")
            if (toolName == "web_search") throw StError.Unsupported("use explicit search API or enable search in a generation")
            if (argumentsJson.toByteArray(Charsets.UTF_8).size > 100_000 || Jsonx.json.parseToJsonElement(argumentsJson) !is JsonObject)
                throw StError.Validation("tool arguments must be a JSON object within budget")
            val args = Jsonx.json.parseToJsonElement(argumentsJson).toString()
            sandbox.requireSandbox(sandboxId)
            if (chatId != null && chats.getSession(chatId)?.sandboxId != sandboxId)
                throw StError.Conflict("chat is not bound to this sandbox")
            currentCoroutineContext().ensureActive()
            if (!scope.isActive) throw StError.Interrupted("task scope stopped")
            withContext(NonCancellable) {
                val reservation = sandbox.reserveOperation(sandboxId)
                try {
                val at = now()
                val id = requestedId ?: UUID.randomUUID().toString().replace("-", "")
                if (!id.matches(Regex("[A-Za-z0-9_-]{1,128}")) || tasks.get(id) != null) throw StError.Conflict("invalid or already registered task id")
                val task = TaskSnapshot(id, if (toolName in ToolRuntime.SHELL_TOOLS) TaskKind.SANDBOX_SHELL else TaskKind.SANDBOX_FILE,
                    TaskStatus.QUEUED, chatId = chatId, sandboxId = sandboxId, createdAt = at, updatedAt = at)
                val progress = SandboxTaskProgress(id, sandboxId, toolName, args, chatId ?: "sandbox-task:$id", "$id:tool", updatedAt = at)
                stopSignals.register(id)
                try { tasks.upsert(task, encodeProgress(progress)) }
                catch (error: Throwable) { stopSignals.forget(id); throw error }
                val ready = CompletableDeferred<Unit>()
                val worker = scope.launch(start = CoroutineStart.ATOMIC) { run(task, progress, ready) }
                workers[id] = worker
                worker.invokeOnCompletion { workers.remove(id, worker); stopSignals.forget(id) }
                stopSignals.bind(id, worker)
                ready.complete(Unit)
                task
                } finally { reservation.close() }
            }
        }

    suspend fun progress(id: String): SandboxTaskProgress {
        val task = tasks.get(id) ?: throw StError.NotFound(id)
        if (task.kind !in setOf(TaskKind.SANDBOX_SHELL, TaskKind.SANDBOX_FILE)) throw StError.Validation("not a sandbox task")
        return Jsonx.decodeOrNull<SandboxTaskProgress>(tasks.payloadJson(id)) ?: throw StError.NotFound("legacy sandbox task has no result")
    }
    suspend fun await(id: String): SandboxTaskProgress {
        workers[id]?.join()
        val task = tasks.get(id) ?: throw StError.NotFound(id)
        if (!task.status.isTerminal) throw StError.Conflict("sandbox task has not completed")
        return progress(id)
    }
    suspend fun stop(id: String, reason: TaskTerminalReason = TaskTerminalReason.USER_STOP): TaskSnapshot = withContext(NonCancellable) {
        val worker = control.withLock {
            val task = tasks.get(id) ?: throw StError.NotFound(id)
            if (task.kind !in setOf(TaskKind.SANDBOX_SHELL, TaskKind.SANDBOX_FILE)) throw StError.Validation("not a sandbox task")
            if (task.status.isTerminal) return@withLock null
            val first = stopSignals.first(id, reason)
            val worker = workers[id]?.also { it.cancel() }
            if (tasks.requestStop(id, first).status.isTerminal) null else worker
        }
        tools.cancelTask(id)
        tools.expireApprovalsForTask(id)
        worker?.join()
        tasks.update(id) { it.copy(status = it.stoppingStatus(), terminalReason = it.completionReason(it.stoppingStatus()), updatedAt = now()) }
    }
    private suspend fun checkpoint(progress: SandboxTaskProgress) = chats.transaction {
        val task = tasks.get(progress.taskId) ?: return@transaction
        if (!task.status.isTerminal) tasks.upsert(task.copy(updatedAt = progress.updatedAt), encodeProgress(progress))
    }
    private suspend fun run(initial: TaskSnapshot, original: SandboxTaskProgress, ready: Deferred<Unit>) {
        var progress = original
        var result: ToolResult? = null
        var failure: Exception? = null
        var cancelled = false
        try {
            ready.await()
            val task = tasks.update(initial.id) {
                if (it.status == TaskStatus.STOPPING) it else it.copy(status = TaskStatus.RUNNING, updatedAt = now())
            }
            if (task.status.isTerminal || task.status == TaskStatus.STOPPING) throw CancellationException("sandbox task stopped")
            result = tools.executeBound(original.approvalSessionId, initial.sandboxId, initial.id, original.callId,
                original.toolName, original.argumentsJson, onShellProgress = { output ->
                    val candidate = progress.copy(shellOutput = output, updatedAt = now())
                    checkpoint(candidate)
                    progress = candidate
                })
            currentCoroutineContext().ensureActive()
            val candidate = progress.copy(
                resultText = if (result.shell == null) result.output.take(100_000) else null,
                resultTextTruncated = result.shell == null && result.output.length > 100_000,
                resultJson = result.shell?.let { Jsonx.encode(it.copy(stdout = "", stderr = "", combined = "")) } ?: result.recordJson,
                updatedAt = now())
            encodeProgress(candidate)
            progress = candidate
        } catch (error: CancellationException) { cancelled = true }
        catch (error: Exception) { if (!currentCoroutineContext().isActive) cancelled = true else failure = error }
        finally {
            withContext(NonCancellable) {
                tools.cancelTask(initial.id)
                tools.expireApprovalsForTask(initial.id)
                control.withLock {
                    chats.transaction {
                        val task = tasks.get(initial.id)?.let(stopSignals::effective) ?: return@transaction
                        if (task.status.isTerminal) return@transaction
                        val shell = result?.shell
                        val status = when {
                            task.status == TaskStatus.STOPPING -> task.stoppingStatus()
                            cancelled -> TaskStatus.INTERRUPTED
                            result?.rejected == true || shell?.cancelled == true -> TaskStatus.STOPPED
                            failure != null || result == null || shell?.timedOut == true || shell != null && shell.exitCode != 0 -> TaskStatus.FAILED
                            else -> TaskStatus.SUCCEEDED
                        }
                        val reason = if (result?.rejected == true && task.status != TaskStatus.STOPPING)
                            TaskTerminalReason.APPROVAL_REJECTED else stopSignals.completionReason(task, status)
                        val records = changes.taskSummary(initial.id)
                        val error = task.errorMessage ?: failure?.let { (it as? StError)?.message ?: "sandbox tool failed (${it.javaClass.simpleName})" }
                            ?: if (shell?.timedOut == true) "shell timed out" else if (shell != null && shell.exitCode != 0) "shell exited with ${shell.exitCode}" else null
                        tasks.upsert(task.copy(status = status, terminalReason = reason, errorMessage = error,
                            resultSummary = if (result?.rejected == true) "tool approval rejected" else if (shell != null) "shell exit=${shell.exitCode}" else original.toolName,
                            fileChangeIds = records.ids, incompleteFileChanges = records.incomplete ||
                                records.total > records.ids.size || shell?.cleanupIncomplete == true || cancelled,
                            updatedAt = now()), encodeProgress(progress.copy(updatedAt = now())))
                    }
                }
            }
        }
    }
    private fun now() = Instant.now().toString()
    private fun encodeProgress(progress: SandboxTaskProgress): String = Jsonx.encode(progress).also {
        if (it.toByteArray(Charsets.UTF_8).size > 1_700_000) throw StError.Validation("sandbox task diagnostics exceed storage budget")
    }
}
