package com.simpletavern.app.ui.setup

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.simpletavern.app.StApplication
import com.simpletavern.core.model.ImportReport
import com.simpletavern.core.model.ImportTaskProgress
import com.simpletavern.core.model.LlmProtocol
import com.simpletavern.core.model.StError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ImportState(val running: Boolean = false, val line: String? = null, val failed: Boolean = false)

/** The connection the current chat would use. Keys never leave the core; only whether one is stored. */
data class ConnectionState(
    val chatId: String? = null,
    val loading: Boolean = false,
    val presetId: String? = null,
    val presetName: String = "",
    val protocol: String = "",
    val model: String = "",
    val baseUrl: String = "",
    val credentialId: String? = null,
    val hasKey: Boolean = false,
    val testing: Boolean = false,
    val line: String? = null,
    val failed: Boolean = false,
)

/** Bringing data onto the table: desktop ZIP import and per-connection API keys. Shared by the empty table and settings. */
class SetupViewModel(app: Application) : AndroidViewModel(app) {
    private val core = (app as StApplication).core

    private val _import = MutableStateFlow(ImportState())
    val import: StateFlow<ImportState> = _import.asStateFlow()

    private val _connection = MutableStateFlow(ConnectionState())
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    fun importArchive(uri: Uri) {
        if (_import.value.running) return
        _import.value = ImportState(running = true, line = "正在把牌洗进来…")
        viewModelScope.launch {
            // The progress flow keeps the last task's value; skip it so an old run is not shown as this one.
            val stale = core.observeImportTaskProgress().value?.taskId
            val watch = launch {
                core.observeImportTaskProgress().filterNotNull().collect { p ->
                    if (p.taskId != stale) _import.update { if (it.running) it.copy(line = progressLine(p)) else it }
                }
            }
            runCatching { core.runImport(uri) }
                .onSuccess { r -> _import.value = ImportState(line = summary(r), failed = !r.success) }
                .onFailure { e -> _import.value = ImportState(line = "导入失败：${e.readable()}", failed = true) }
            watch.cancel()
            _connection.value.chatId?.let { refresh(it) }
        }
    }

    fun loadConnection(chatId: String?) {
        if (chatId == null) { _connection.value = ConnectionState(); return }
        if (chatId == _connection.value.chatId && !_connection.value.loading) return
        refresh(chatId)
    }

    private fun refresh(chatId: String) {
        _connection.value = ConnectionState(chatId = chatId, loading = true)
        viewModelScope.launch {
            runCatching {
                val p = core.previewProvider(chatId)
                val name = p.presetId?.let { id -> runCatching { core.getPreset(id).name }.getOrNull() }
                ConnectionState(
                    chatId = chatId,
                    presetId = p.presetId,
                    presetName = name ?: p.presetId ?: "全局连接",
                    protocol = p.protocol.label(),
                    model = p.model,
                    baseUrl = p.baseUrl,
                    credentialId = p.credentialId,
                    hasKey = p.hasCredential,
                )
            }.onSuccess { s -> if (_connection.value.chatId == chatId) _connection.value = s }
                .onFailure { e ->
                    if (_connection.value.chatId == chatId)
                        _connection.value = ConnectionState(chatId = chatId, line = "读不出这局的连接：${e.readable()}", failed = true)
                }
        }
    }

    fun saveKey(key: String) {
        val s = _connection.value
        val id = s.credentialId ?: return
        val value = key.trim()
        if (value.isEmpty()) return
        viewModelScope.launch {
            runCatching { core.setProviderCredential(id, value); core.hasProviderCredential(id) }
                .onSuccess { has -> _connection.update { it.copy(hasKey = has, line = "Key 已存下。", failed = false) } }
                .onFailure { e -> _connection.update { it.copy(line = "没存上：${e.readable()}", failed = true) } }
        }
    }

    /** Sends one real (billable) request, so it only ever runs from the explicit button. */
    fun testConnection() {
        val s = _connection.value
        val presetId = s.presetId ?: run {
            _connection.update { it.copy(line = "这局没有挂模型预设，没法单独测试。", failed = true) }
            return
        }
        if (s.testing) return
        _connection.update { it.copy(testing = true, line = "正在试牌…", failed = false) }
        viewModelScope.launch {
            runCatching { core.testConnection(presetId) }
                .onSuccess { r ->
                    _connection.update {
                        if (r.success) it.copy(testing = false, failed = false,
                            line = "通了 · ${r.latencyMs} ms" + (r.model?.let { m -> " · $m" } ?: ""))
                        else it.copy(testing = false, failed = true,
                            line = "没通 · " + listOfNotNull(r.errorCode, r.message).joinToString(" · ").ifBlank { "未知错误" })
                    }
                }
                .onFailure { e -> _connection.update { it.copy(testing = false, failed = true, line = "没通 · ${e.readable()}") } }
        }
    }

    private fun progressLine(p: ImportTaskProgress): String {
        val pct = (p.fraction * 100).toInt().coerceIn(0, 100)
        val phase = when (p.phase) {
            "queued" -> "排队"
            "input" -> "读取压缩包"
            "done", "done_cleanup_pending" -> "收尾"
            else -> p.phase
        }
        val files = if (p.files > 0) " · ${p.files} 个文件" else ""
        return "正在把牌洗进来… $phase $pct%$files"
    }

    private fun summary(r: ImportReport): String {
        if (r.cancelled) return "导入已取消。"
        val ok = r.items.filter { it.status == "converted" }.groupingBy { it.kind }.eachCount()
        val parts = listOf("character" to "角色", "chat" to "会话", "persona" to "身份", "preset" to "预设", "worldbook" to "世界书")
            .mapNotNull { (k, label) -> ok[k]?.let { "$label $it" } }
        val failed = r.items.count { it.status == "failed" }
        val head = if (r.success) "导入完成" else "导入没有成功"
        val body = parts.joinToString(" · ").ifBlank { "没有新内容" }
        return "$head：$body" + if (failed > 0) "；$failed 项失败" else ""
    }

    private fun LlmProtocol.label() = when (this) {
        LlmProtocol.OPENAI_COMPATIBLE -> "OpenAI 兼容"
        LlmProtocol.OPENAI_RESPONSES -> "OpenAI Responses"
        LlmProtocol.ANTHROPIC_MESSAGES -> "Anthropic"
        LlmProtocol.GEMINI_GENERATE_CONTENT -> "Gemini"
        LlmProtocol.BEDROCK_CONVERSE -> "Bedrock"
    }

    private fun Throwable.readable(): String = (this as? StError)?.message ?: message ?: javaClass.simpleName
}
