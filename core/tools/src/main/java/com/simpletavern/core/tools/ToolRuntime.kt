package com.simpletavern.core.tools

import com.simpletavern.core.data.ApprovalStore
import com.simpletavern.core.data.Jsonx
import com.simpletavern.core.data.TaskStore
import com.simpletavern.core.model.*
import com.simpletavern.runtime.sandbox.SandboxManager
import com.simpletavern.runtime.sandbox.ShellOutput
import com.simpletavern.runtime.sandbox.ShellResult
import com.simpletavern.runtime.sandbox.FileMutationPremise
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ToolResult(val output: String, val recordJson: String, val rejected: Boolean = false, val shell: ShellResult? = null,
    val media: List<ToolMedia> = emptyList())

class ToolRuntime(
    private val sandbox: SandboxManager,
    private val approvals: ApprovalStore,
    private val policyState: MutableStateFlow<ApprovalPolicy> = MutableStateFlow(ApprovalPolicy()),
    private val tasks: TaskStore? = null,
) {
    // Tool execution is ordered per task; approval decisions still use session-level FIFO checks below.
    private val taskQueues = ConcurrentHashMap<String, Mutex>()
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val executed = ConcurrentHashMap<String, ToolResult>()
    private val attempted = ConcurrentHashMap.newKeySet<String>()
    private val livePremises = ConcurrentHashMap<String, FileMutationPremise>()
    private data class BoundCall(val sessionId: String, val sandboxId: String?, val name: String,
        val arguments: kotlinx.serialization.json.JsonObject, val premise: String?, val searchIdentity: String?, val assistantIdentity: String?)
    private val boundCalls = ConcurrentHashMap<String, BoundCall>()
    private val decisions = Mutex()

    fun policy(): StateFlow<ApprovalPolicy> = policyState
    fun updatePolicy(policy: ApprovalPolicy) { policyState.value = policy }

    suspend fun executeBound(
        sessionId: String,
        sandboxId: String?,
        taskId: String,
        callId: String,
        name: String,
        argumentsJson: String,
        editPremiseJson: String? = null,
        onShellProgress: suspend (ShellOutput) -> Unit = {},
        webSearch: WebSearchSession? = null,
        assistantTools: AssistantToolSession? = null,
    ): ToolResult = taskQueues.getOrPut(taskId) { Mutex() }.withLock {
        currentCoroutineContext().ensureActive()
        val identity = "$taskId:$callId"
        if (argumentsJson.toByteArray(Charsets.UTF_8).size > 1_000_000) throw StError.Validation("tool arguments exceed 1000000 UTF-8 bytes")
        if (editPremiseJson != null && editPremiseJson.length > 2_000_000) throw StError.Validation("explicit edit premise exceeds text budget")
        val owner = tasks?.get(taskId)
        if (tasks != null) {
            if (owner == null) throw StError.NotFound("tool task missing")
            if (owner.status.isTerminal || owner.status == TaskStatus.STOPPING) throw StError.Cancelled("task no longer accepts tools")
            if (owner.sandboxId != sandboxId || owner.chatId != null && owner.chatId != sessionId)
                throw StError.Conflict("tool context differs from the task's fixed chat or sandbox")
        }
        if (name == "web_search" && webSearch == null) throw StError.Validation("web search was not enabled for this invocation")
        val binding = BoundCall(sessionId, sandboxId, name, Jsonx.json.parseToJsonElement(argumentsJson).jsonObject, editPremiseJson,
            if (name == "web_search") webSearch?.identity else null, assistantTools?.identity)
        assistantTools?.validateArguments(name, binding.arguments)
        val questions = if (name == "ask_user" && assistantTools?.handlesUserQuestions == true)
            UserQuestions.parse(argumentsJson) else null
        val previous = boundCalls.putIfAbsent(identity, binding)
        if (previous != null && previous != binding) throw StError.Conflict("tool call id is already bound to different arguments or context")
        executed[identity]?.let { return@withLock it }
        if (name !in SUPPORTED && name !in assistantTools?.categories.orEmpty()) throw StError.Unsupported("unknown tool $name")
        if (!attempted.add(identity)) throw StError.Conflict("tool invocation already attempted; use a new call id")
        val reservation = if (name in MUTATING_TOOLS) sandbox.reserveOperation(sandboxId ?: throw StError.Validation("sandbox required")) else null
        var premise: FileMutationPremise? = null
        var liveApprovalId: String? = null
        var userQuestionId: String? = null
        try {
            if (name in MUTATING_TOOLS) {
                val args = binding.arguments
                fun required(key: String) = args[key]?.jsonPrimitive?.contentOrNull ?: throw StError.Validation(key)
                val relatives = if (name == "rename_file") listOf(required("from"), required("to")) else listOf(required("path"))
                if (name == "edit_file" || name == "apply_patch") required("content")
                val captured = sandbox.captureMutationPremise(sandboxId!!, relatives,
                    allowDirectory = name == "rename_file", followFinalLink = name !in setOf("delete_file", "rename_file"))
                premise = captured
                if (name == "create_file" && captured.overwritesExisting) throw StError.Conflict("create_file target already exists")
                if (name in setOf("edit_file", "apply_patch", "delete_file", "rename_file") && !captured.exists(relatives.first()))
                    throw StError.NotFound("mutation source missing")
                if (name == "rename_file" && captured.exists(relatives.last())) throw StError.Conflict("rename target already exists")
                if (name == "edit_file" || name == "apply_patch") {
                    val expected = args["expected"]?.jsonPrimitive?.contentOrNull ?: editPremiseJson
                    if (expected != null) withContext(kotlinx.coroutines.Dispatchers.IO) { captured.requireExpectedText(relatives.first(), expected) }
                }
            }
            val category = assistantTools?.approvalCategory(name, binding.arguments)
                ?: if (name == "write_file" && premise?.overwritesExisting == true) ApprovalCategory.EDIT else categorize(name)
            val needs = questions != null || assistantTools?.forcesApproval(name) == true || needsApproval(category)
            if (needs) {
                if (questions != null) assistantTools!!.requireUserQuestionScope()
                val approvalId = UUID.randomUUID().toString().replace("-", "")
                if (questions != null) userQuestionId = approvalId
                val req = ApprovalRequest(
                    id = approvalId,
                    sessionId = sessionId,
                    sandboxId = sandboxId,
                    taskId = taskId,
                    category = category,
                    toolName = name,
                    argumentsJson = argumentsJson,
                    editPremiseJson = premise?.describe(limit = 10) ?: editPremiseJson,
                    status = ApprovalDecision.PENDING,
                    createdAt = Instant.now().toString(),
                )
                val deferred = CompletableDeferred<Boolean>()
                decisions.withLock {
                    currentCoroutineContext().ensureActive()
                    // Register before publishing; an immediate UI decision must have a waiter.
                    waiters[approvalId] = deferred
                    premise?.let { livePremises[approvalId] = it; liveApprovalId = approvalId }
                    try {
                        tasks?.update(taskId) { if (it.status == TaskStatus.RUNNING) it.copy(status = TaskStatus.AWAITING_APPROVAL, updatedAt = Instant.now().toString()) else it }
                        approvals.upsert(req)
                    } catch (e: Exception) { waiters.remove(approvalId); throw e }
                }
                val approved = try {
                    deferred.await()
                } finally {
                    withContext(NonCancellable) {
                        decisions.withLock {
                            waiters.remove(approvalId)
                            val pending = approvals.get(approvalId)
                            if (pending?.status == ApprovalDecision.PENDING) approvals.upsert(pending.copy(
                                status = ApprovalDecision.EXPIRED, decidedAt = Instant.now().toString()))
                            tasks?.update(taskId) { if (it.status == TaskStatus.AWAITING_APPROVAL) it.copy(status = TaskStatus.RUNNING, updatedAt = Instant.now().toString()) else it }
                        }
                    }
                }
                val latest = approvals.get(approvalId)
                if (!approved || latest?.status != ApprovalDecision.APPROVED) {
                    val result = ToolResult(
                        output = kotlinx.serialization.json.buildJsonObject {
                            put("ok", kotlinx.serialization.json.JsonPrimitive(false))
                            put("rejected", kotlinx.serialization.json.JsonPrimitive(true))
                            put("approvalId", kotlinx.serialization.json.JsonPrimitive(approvalId))
                        }.toString(),
                        recordJson = kotlinx.serialization.json.buildJsonObject {
                            put("callId", kotlinx.serialization.json.JsonPrimitive(callId))
                            put("rejected", kotlinx.serialization.json.JsonPrimitive(true))
                        }.toString(),
                        rejected = true,
                    )
                    executed[identity] = result
                    return@withLock result
                }
            }
            currentCoroutineContext().ensureActive()
            if (tasks != null) {
                val latestOwner = tasks.get(taskId) ?: throw StError.NotFound("tool task missing")
                if (latestOwner.status.isTerminal || latestOwner.status == TaskStatus.STOPPING)
                    throw StError.Cancelled("task stopped before tool dispatch")
            }
            if (questions != null) {
                assistantTools!!.requireUserQuestionScope()
                val requestId = requireNotNull(userQuestionId)
                val answers = approvals.userAnswers(requestId) ?: throw StError.Conflict("user question has no submitted answers")
                val output = UserQuestions.output(questions, answers)
                val result = ToolResult(output.toString(), kotlinx.serialization.json.buildJsonObject {
                    put("toolName", kotlinx.serialization.json.JsonPrimitive(name))
                    put("callId", kotlinx.serialization.json.JsonPrimitive(callId))
                    put("questionRequestId", kotlinx.serialization.json.JsonPrimitive(requestId))
                    put("userQuestionAnswers", output)
                }.toString())
                executed[identity] = result
                return@withLock result
            }
            assistantTools?.requireScope()
            (if (name in assistantTools?.categories.orEmpty()) assistantTools!!.execute(name, binding.arguments)
                else dispatch(sessionId, sandboxId, taskId, callId, name, argumentsJson, editPremiseJson, onShellProgress, premise, webSearch))
                .also { executed[identity] = it }
        } catch (error: StError.Conflict) {
            liveApprovalId?.let { approvalId -> withContext(NonCancellable) { decisions.withLock {
                approvals.get(approvalId)?.takeIf { it.status == ApprovalDecision.APPROVED }?.let {
                    approvals.upsert(it.copy(status = ApprovalDecision.SUPERSEDED, decidedAt = Instant.now().toString()))
                }
            } } }
            throw error
        } finally {
            withContext(NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
                decisions.withLock { liveApprovalId?.let { livePremises.remove(it) } }
                try { premise?.close() } finally { reservation?.close() }
            }
        }
    }

    suspend fun approvalPremiseManifest(approvalId: String, offset: Int = 0, limit: Int = 100): String = decisions.withLock {
        requireLivePremise(approvalId).describe(offset, limit)
    }

    suspend fun approvalPremiseChunk(approvalId: String, entry: Int, offset: Long = 0, limit: Int = 65_536): com.simpletavern.runtime.sandbox.ApprovalPremiseChunk = decisions.withLock {
        requireLivePremise(approvalId).readChunk(entry, offset, limit)
    }

    private suspend fun requireLivePremise(approvalId: String): FileMutationPremise {
        val request = approvals.get(approvalId) ?: throw StError.NotFound("approval")
        if (request.status != ApprovalDecision.PENDING || !waiters.containsKey(approvalId)) throw StError.Conflict("approval premise is no longer pending")
        val owner = tasks?.get(request.taskId)
        if (tasks != null && (owner == null || owner.status.isTerminal || owner.status == TaskStatus.STOPPING))
            throw StError.Conflict("approval task is no longer live")
        return livePremises[approvalId] ?: throw StError.NotFound("approval has no file premise")
    }

    suspend fun approve(approvalId: String): ApprovalRequest = decisions.withLock {
        val req = approvals.get(approvalId) ?: throw StError.NotFound(approvalId)
        if (req.status != ApprovalDecision.PENDING) return@withLock req
        if (req.toolName == "ask_user") throw StError.Validation("ask_user requires submitUserAnswers, not approval")
        val task = tasks?.get(req.taskId)
        if (!waiters.containsKey(approvalId) || tasks != null && (task == null || task.status.isTerminal || task.status == TaskStatus.STOPPING)) {
            withContext(NonCancellable) {
                approvals.upsert(req.copy(status = ApprovalDecision.EXPIRED, decidedAt = Instant.now().toString()))
                waiters.remove(approvalId)?.complete(false)
            }
            throw StError.Conflict("approval is no longer attached to a live invocation")
        }
        // FIFO: only first pending for session may be approved
        val pending = approvals.pendingFifo(req.sessionId)
        if (pending.firstOrNull()?.id != approvalId) {
            throw StError.Conflict("approve out of FIFO order")
        }
        val updated = req.copy(status = ApprovalDecision.APPROVED, decidedAt = Instant.now().toString())
        // Keep each committed decision and its in-process wakeup together if the caller is cancelled.
        withContext(NonCancellable) {
            approvals.upsert(updated)
            waiters[approvalId]?.complete(true)
        }
        updated
    }

    suspend fun reject(approvalId: String): ApprovalRequest = decisions.withLock {
        val req = approvals.get(approvalId) ?: throw StError.NotFound(approvalId)
        if (req.status != ApprovalDecision.PENDING) return@withLock req
        val updated = req.copy(status = ApprovalDecision.REJECTED, decidedAt = Instant.now().toString())
        withContext(NonCancellable) {
            approvals.upsert(updated)
            waiters[approvalId]?.complete(false)
        }
        updated
    }

    suspend fun expireApprovalsForTask(taskId: String) = decisions.withLock {
        withContext(NonCancellable) {
            val at = Instant.now().toString()
            val expiringIds = waiters.keys.toList().filter { id -> approvals.get(id)?.taskId == taskId }
            approvals.expirePendingForTask(taskId, at)
            expiringIds.forEach { id -> waiters.remove(id)?.complete(false) }
        }
    }

    fun cancelTask(taskId: String) {
        sandbox.cancelTask(taskId)
        taskQueues.remove(taskId)
        executed.keys.removeIf { it.startsWith("$taskId:") }
        attempted.removeIf { it.startsWith("$taskId:") }
        boundCalls.keys.removeIf { it.startsWith("$taskId:") }
    }

    private fun needsApproval(category: ApprovalCategory): Boolean {
        val p = policyState.value
        if (p.mode == "auto") return false
        return when (category) {
            ApprovalCategory.SHELL -> p.shellRequiresApproval
            ApprovalCategory.WRITE -> p.writeRequiresApproval
            ApprovalCategory.EDIT -> p.editRequiresApproval
            ApprovalCategory.READ -> false
        }
    }

    private fun categorize(name: String): ApprovalCategory = when (name) {
        "shell", "bash", "run_terminal" -> ApprovalCategory.SHELL
        "write_file", "create_file", "upload_file" -> ApprovalCategory.WRITE
        "edit_file", "apply_patch", "delete_file", "rename_file" -> ApprovalCategory.EDIT
        "read_file", "list_dir", "stat", "web_search" -> ApprovalCategory.READ
        else -> ApprovalCategory.SHELL
    }

    private suspend fun dispatch(
        sessionId: String,
        sandboxId: String?,
        taskId: String,
        callId: String,
        name: String,
        argumentsJson: String,
        editPremiseJson: String?,
        onShellProgress: suspend (ShellOutput) -> Unit,
        premise: FileMutationPremise?,
        webSearch: WebSearchSession?,
    ): ToolResult {
        val args = Jsonx.json.parseToJsonElement(argumentsJson).jsonObject
        if (name == "web_search") {
            if (args.keys != setOf("query")) throw StError.Validation("web_search only accepts query")
            val query = (args["query"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw StError.Validation("search query must be text")
            val result = (webSearch ?: throw StError.Validation("web search permission missing")).search(query, SearchCallContext(sessionId, taskId, callId))
            return ToolResult(WebSearchService.toolOutput(result), Jsonx.encode(result))
        }
        val sb = sandboxId ?: throw StError.Validation("sandbox required for tool $name")
        return when (name) {
            "shell", "bash", "run_terminal" -> {
                val cmd = args["command"]?.jsonPrimitive?.contentOrNull
                    ?: args["cmd"]?.jsonPrimitive?.contentOrNull
                    ?: throw StError.Validation("command required")
                val timeout = args["timeoutSec"]?.let { it.jsonPrimitive.contentOrNull?.toLongOrNull()
                    ?: throw StError.Validation("timeoutSec must be an integer") } ?: 300L
                val result = sandbox.runShell(sb, taskId, cmd, timeout, onShellProgress)
                ToolResult(result.combined, Jsonx.encode(result), shell = result)
            }
            "read_file" -> {
                val path = args["path"]?.jsonPrimitive?.contentOrNull ?: throw StError.Validation("path")
                ToolResult(sandbox.readFile(sb, path), Jsonx.encode(mapOf("callId" to callId, "op" to "read")))
            }
            "list_dir" -> {
                val path = args["path"]?.jsonPrimitive?.contentOrNull ?: "."
                ToolResult(sandbox.listDir(sb, path).joinToString("\n"), Jsonx.encode(mapOf("callId" to callId, "op" to "list")))
            }
            "write_file", "create_file" -> {
                val path = args["path"]?.jsonPrimitive?.contentOrNull ?: throw StError.Validation("path")
                val content = args["content"]?.jsonPrimitive?.contentOrNull ?: ""
                sandbox.writeFile(sb, taskId, path, content, requireNotNull(premise), createOnly = name == "create_file")
                ToolResult("ok", Jsonx.encode(mapOf("callId" to callId, "op" to "write", "path" to path)))
            }
            "edit_file", "apply_patch" -> {
                val path = args["path"]?.jsonPrimitive?.contentOrNull ?: throw StError.Validation("path")
                val expected = args["expected"]?.jsonPrimitive?.contentOrNull
                    ?: editPremiseJson
                val content = args["content"]?.jsonPrimitive?.contentOrNull ?: throw StError.Validation("content")
                sandbox.editFile(sb, taskId, path, expected, content, requireNotNull(premise))
                ToolResult("ok", Jsonx.encode(mapOf("callId" to callId, "op" to "edit", "path" to path)))
            }
            "delete_file" -> {
                val path = args["path"]?.jsonPrimitive?.contentOrNull ?: throw StError.Validation("path")
                sandbox.deleteFile(sb, taskId, path, requireNotNull(premise))
                ToolResult("ok", Jsonx.encode(mapOf("callId" to callId, "op" to "delete", "path" to path)))
            }
            "rename_file" -> {
                val from = args["from"]?.jsonPrimitive?.contentOrNull ?: throw StError.Validation("from")
                val to = args["to"]?.jsonPrimitive?.contentOrNull ?: throw StError.Validation("to")
                sandbox.renameFile(sb, taskId, from, to, requireNotNull(premise))
                ToolResult("ok", Jsonx.encode(mapOf("callId" to callId, "op" to "rename", "from" to from, "to" to to)))
            }
            else -> throw StError.Unsupported("unknown tool $name")
        }
    }

    suspend fun userQuestion(requestId: String): UserQuestionRequest = decisions.withLock {
        val request = approvals.get(requestId) ?: throw StError.NotFound("user question")
        if (request.toolName != "ask_user") throw StError.Validation("request is not a user question")
        UserQuestionRequest(request, UserQuestions.parse(request.argumentsJson), approvals.userAnswers(requestId))
    }

    suspend fun submitUserAnswers(requestId: String, answers: Map<String, UserQuestionAnswer>): UserQuestionRequest = decisions.withLock {
        val request = approvals.get(requestId) ?: throw StError.NotFound("user question")
        if (request.toolName != "ask_user") throw StError.Validation("request is not a user question")
        val questions = UserQuestions.parse(request.argumentsJson)
        UserQuestions.output(questions, answers)
        if (request.status != ApprovalDecision.PENDING) {
            val previous = approvals.userAnswers(requestId)
            if (request.status == ApprovalDecision.APPROVED && previous == answers)
                return@withLock UserQuestionRequest(request, questions, previous)
            throw StError.Conflict("user question is no longer awaiting an answer")
        }
        val task = tasks?.get(request.taskId)
        if (!waiters.containsKey(requestId) || tasks != null && (task == null || task.status.isTerminal || task.status == TaskStatus.STOPPING)) {
            withContext(NonCancellable) {
                approvals.upsert(request.copy(status = ApprovalDecision.EXPIRED, decidedAt = Instant.now().toString()))
                waiters.remove(requestId)?.complete(false)
            }
            throw StError.Conflict("user question is no longer attached to a live invocation")
        }
        if (approvals.pendingFifo(request.sessionId).firstOrNull()?.id != requestId)
            throw StError.Conflict("answer user questions in session FIFO order")
        val updated = request.copy(status = ApprovalDecision.APPROVED, decidedAt = Instant.now().toString())
        // Commit answers and the decision together, before waking generation.
        withContext(NonCancellable) {
            approvals.answerUserQuestion(updated, answers)
            waiters[requestId]?.complete(true)
        }
        UserQuestionRequest(updated, questions, answers)
    }

    companion object {
        val SUPPORTED = setOf("shell", "bash", "run_terminal", "read_file", "list_dir",
            "write_file", "create_file", "edit_file", "apply_patch", "delete_file", "rename_file", "web_search")
        val SHELL_TOOLS = setOf("shell", "bash", "run_terminal")
        private val MUTATING_TOOLS = setOf("write_file", "create_file", "edit_file", "apply_patch", "delete_file", "rename_file")
    }
}
