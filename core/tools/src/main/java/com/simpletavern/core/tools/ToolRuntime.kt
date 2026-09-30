package com.simpletavern.core.tools

import com.simpletavern.core.data.ApprovalStore
import com.simpletavern.core.data.Jsonx
import com.simpletavern.core.model.*
import com.simpletavern.runtime.sandbox.SandboxManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ToolResult(val output: String, val recordJson: String)

class ToolRuntime(
    private val sandbox: SandboxManager,
    private val approvals: ApprovalStore,
    private val policyState: MutableStateFlow<ApprovalPolicy> = MutableStateFlow(ApprovalPolicy()),
) {
    private val sessionQueues = ConcurrentHashMap<String, Mutex>()
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val executed = ConcurrentHashMap.newKeySet<String>()

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
    ): ToolResult {
        if (!executed.add(callId)) {
            return ToolResult("idempotent: already executed", """{"callId":"$callId","idempotent":true}""")
        }
        val category = categorize(name)
        val needs = needsApproval(category)
        if (needs) {
            val approvalId = UUID.randomUUID().toString().replace("-", "")
            val req = ApprovalRequest(
                id = approvalId,
                sessionId = sessionId,
                sandboxId = sandboxId,
                taskId = taskId,
                category = category,
                toolName = name,
                argumentsJson = argumentsJson,
                editPremiseJson = editPremiseJson,
                status = ApprovalDecision.PENDING,
                createdAt = Instant.now().toString(),
            )
            approvals.upsert(req)
            val deferred = CompletableDeferred<Boolean>()
            waiters[approvalId] = deferred
            val approved = try {
                deferred.await()
            } finally {
                waiters.remove(approvalId)
            }
            val latest = approvals.get(approvalId)
            if (!approved || latest?.status != ApprovalDecision.APPROVED) {
                executed.remove(callId)
                return ToolResult(
                    output = Jsonx.encode(mapOf("ok" to false, "rejected" to true, "approvalId" to approvalId)),
                    recordJson = """{"callId":"$callId","rejected":true}""",
                )
            }
        }
        return dispatch(sessionId, sandboxId, taskId, callId, name, argumentsJson, editPremiseJson)
    }

    suspend fun approve(approvalId: String): ApprovalRequest {
        val req = approvals.get(approvalId) ?: throw StError.NotFound(approvalId)
        if (req.status != ApprovalDecision.PENDING) return req
        // FIFO: only first pending for session may be approved
        val pending = approvals.pendingFifo(req.sessionId)
        if (pending.firstOrNull()?.id != approvalId) {
            throw StError.Conflict("approve out of FIFO order")
        }
        val updated = req.copy(status = ApprovalDecision.APPROVED, decidedAt = Instant.now().toString())
        approvals.upsert(updated)
        waiters[approvalId]?.complete(true)
        return updated
    }

    suspend fun reject(approvalId: String): ApprovalRequest {
        val req = approvals.get(approvalId) ?: throw StError.NotFound(approvalId)
        if (req.status != ApprovalDecision.PENDING) return req
        val updated = req.copy(status = ApprovalDecision.REJECTED, decidedAt = Instant.now().toString())
        approvals.upsert(updated)
        waiters[approvalId]?.complete(false)
        return updated
    }

    suspend fun expireApprovalsForTask(taskId: String) {
        val at = Instant.now().toString()
        approvals.expirePendingForTask(taskId, at)
        waiters.keys.toList().forEach { id ->
            val a = approvals.get(id)
            if (a?.taskId == taskId) waiters.remove(id)?.complete(false)
        }
    }

    fun cancelTask(taskId: String) {
        sandbox.cancelTask(taskId)
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
        "read_file", "list_dir", "stat" -> ApprovalCategory.READ
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
    ): ToolResult {
        val sb = sandboxId ?: throw StError.Validation("sandbox required for tool $name")
        val args = Jsonx.json.parseToJsonElement(argumentsJson).jsonObject
        return when (name) {
            "shell", "bash", "run_terminal" -> {
                val cmd = args["command"]?.jsonPrimitive?.contentOrNull
                    ?: args["cmd"]?.jsonPrimitive?.contentOrNull
                    ?: throw StError.Validation("command required")
                val result = sandbox.runShell(sb, taskId, cmd)
                ToolResult(result.combined, Jsonx.encode(result))
            }
            "read_file" -> {
                val path = args["path"]?.jsonPrimitive?.contentOrNull ?: throw StError.Validation("path")
                ToolResult(sandbox.readFile(sb, path), """{"callId":"$callId","op":"read"}""")
            }
            "list_dir" -> {
                val path = args["path"]?.jsonPrimitive?.contentOrNull ?: "."
                ToolResult(sandbox.listDir(sb, path).joinToString("\n"), """{"callId":"$callId","op":"list"}""")
            }
            "write_file", "create_file" -> {
                val path = args["path"]?.jsonPrimitive?.contentOrNull ?: throw StError.Validation("path")
                val content = args["content"]?.jsonPrimitive?.contentOrNull ?: ""
                sandbox.writeFile(sb, taskId, path, content)
                ToolResult("ok", """{"callId":"$callId","op":"write","path":"$path"}""")
            }
            "edit_file", "apply_patch" -> {
                val path = args["path"]?.jsonPrimitive?.contentOrNull ?: throw StError.Validation("path")
                val expected = args["expected"]?.jsonPrimitive?.contentOrNull
                    ?: editPremiseJson
                val content = args["content"]?.jsonPrimitive?.contentOrNull ?: throw StError.Validation("content")
                sandbox.editFile(sb, taskId, path, expected, content)
                ToolResult("ok", """{"callId":"$callId","op":"edit","path":"$path"}""")
            }
            "delete_file" -> {
                val path = args["path"]?.jsonPrimitive?.contentOrNull ?: throw StError.Validation("path")
                sandbox.deleteFile(sb, taskId, path)
                ToolResult("ok", """{"callId":"$callId","op":"delete","path":"$path"}""")
            }
            "rename_file" -> {
                val from = args["from"]?.jsonPrimitive?.contentOrNull ?: throw StError.Validation("from")
                val to = args["to"]?.jsonPrimitive?.contentOrNull ?: throw StError.Validation("to")
                sandbox.renameFile(sb, taskId, from, to)
                ToolResult("ok", """{"callId":"$callId","op":"rename"}""")
            }
            else -> throw StError.Unsupported("unknown tool $name")
        }
    }
}
