package com.simpletavern.app.ui.setup

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.simpletavern.app.StApplication
import com.simpletavern.core.model.ImportReport
import com.simpletavern.core.model.ImportTaskProgress
import com.simpletavern.core.model.ModelPreset
import com.simpletavern.core.model.StError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

data class ImportState(val running: Boolean = false, val line: String? = null, val failed: Boolean = false)

data class Line(val text: String, val failed: Boolean)

data class ProtocolChoice(val id: String, val label: String, val base: String)

val Protocols = listOf(
    ProtocolChoice("openai_compatible_chat", "OpenAI 兼容", "https://api.deepseek.com"),
    ProtocolChoice("anthropic_messages", "Anthropic", "https://api.anthropic.com"),
    ProtocolChoice("gemini_generate_content", "Gemini", "https://generativelanguage.googleapis.com"),
    ProtocolChoice("openai_responses", "OpenAI Responses", "https://api.openai.com"),
)

/** A model preset as the settings card shows it. Keys never leave the core; only whether one is stored. */
data class PresetRow(
    val id: String,
    val name: String,
    val protocol: String,
    val baseUrl: String,
    val model: String,
    val credentialId: String,
    val updatedAt: String,
    val imported: Boolean,
)

/** Only what a connection needs: protocol, endpoint, key and model. */
data class PresetDraft(
    val id: String? = null,
    val name: String = "",
    val protocol: String = Protocols.first().id,
    val baseUrl: String = Protocols.first().base,
    val model: String = "",
    val key: String = "",
)

/** Which preset the open session plays on. */
data class ChatConnection(val chatId: String? = null, val presetId: String? = null, val loaded: Boolean = false)

/**
 * Everything the settings card edits: model presets and their keys, which preset the open session uses,
 * and bringing desktop data over.
 */
class SetupViewModel(app: Application) : AndroidViewModel(app) {
    private val core = (app as StApplication).core

    private val _import = MutableStateFlow(ImportState())
    val import: StateFlow<ImportState> = _import.asStateFlow()

    private val _line = MutableStateFlow<Line?>(null)
    val line: StateFlow<Line?> = _line.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val keys = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    val presets: StateFlow<List<PresetRow>> = core.observePresets()
        .map { list -> list.map { row(it) }.also { refreshKeys(it) } }
        .catch { e -> _line.value = Line(e.readable(), true); emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val keyStatus: StateFlow<Map<String, Boolean>> = keys.asStateFlow()

    private val _chat = MutableStateFlow(ChatConnection())
    val chat: StateFlow<ChatConnection> = _chat.asStateFlow()

    fun clearLine() { _line.value = null }

    fun loadChat(chatId: String?) {
        if (chatId == null) { _chat.value = ChatConnection(loaded = true); return }
        viewModelScope.launch {
            runCatching { core.getChat(chatId).overrides.presetId }
                .onSuccess { _chat.value = ChatConnection(chatId, it, true) }
                .onFailure { _chat.value = ChatConnection(chatId, null, true) }
        }
    }

    /** Puts the open session on another preset. */
    fun useForChat(presetId: String) {
        val chatId = _chat.value.chatId ?: return
        work {
            val chat = core.getChat(chatId)
            core.saveChat(chat.copy(overrides = chat.overrides.copy(presetId = presetId), updatedAt = Instant.now().toString()))
            _chat.value = ChatConnection(chatId, presetId, true)
            _line.value = Line("这一局改用「${presets.value.firstOrNull { it.id == presetId }?.name ?: "预设"}」。", false)
        }
    }

    fun draftOf(row: PresetRow) = PresetDraft(row.id, row.name, row.protocol, row.baseUrl, row.model)

    /** Saves a new preset or the connection fields of an existing one; other configuration keys are kept. */
    fun savePreset(d: PresetDraft, onSaved: (String) -> Unit = {}) {
        val isNew = d.id == null
        val missing = listOfNotNull(
            "地址".takeIf { d.baseUrl.isBlank() },
            "模型".takeIf { d.model.isBlank() },
            "API Key".takeIf { isNew && d.key.isBlank() },
        )
        if (missing.isNotEmpty()) { _line.value = Line("还差：${missing.joinToString("、")}", true); return }
        work {
            val now = Instant.now().toString()
            val old = d.id?.let { core.getPreset(it) }
            val config = (old?.configJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject())
                .put("protocol", d.protocol)
                .put("baseUrl", d.baseUrl.trim().trimEnd('/'))
                .put("model", d.model.trim())
            val id = old?.id ?: UUID.randomUUID().toString().replace("-", "")
            val name = d.name.trim().ifBlank { d.model.trim() }
            core.savePreset(
                old?.copy(name = name, configJson = config.toString(), updatedAt = now)
                    ?: ModelPreset(id, name, config.toString(), now, now),
            )
            if (d.key.isNotBlank()) core.setProviderCredential(credentialOf(id, config), d.key.trim())
            refreshKeys(presets.value)
            _line.value = Line(if (isNew) "预设已存下。" else "改好了。", false)
            onSaved(id)
        }
    }

    fun deletePreset(row: PresetRow) {
        work {
            core.deletePreset(row.id, row.updatedAt)
            _line.value = Line("「${row.name}」已删除。", false)
        }
    }

    /** Sends one real (billable) request, so it only ever runs from the explicit button. */
    fun testPreset(id: String) {
        work {
            _line.value = Line("正在试牌…", false)
            val r = core.testConnection(id)
            _line.value = if (r.success) Line("通了 · ${r.latencyMs} ms" + (r.model?.let { " · $it" } ?: ""), false)
            else Line("没通 · " + listOfNotNull(r.errorCode, r.message).joinToString(" · ").ifBlank { "未知错误" }, true)
        }
    }

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
        }
    }

    private fun refreshKeys(rows: List<PresetRow>) {
        viewModelScope.launch {
            keys.value = rows.associate { it.id to runCatching { core.hasProviderCredential(it.credentialId) }.getOrDefault(false) }
        }
    }

    private fun row(p: ModelPreset): PresetRow {
        val c = runCatching { JSONObject(p.configJson) }.getOrDefault(JSONObject())
        val model = c.optString("model").ifBlank { c.optJSONObject("selectedModel")?.optString("modelId").orEmpty() }
            .ifBlank { c.optString("defaultModel") }
        return PresetRow(
            id = p.id,
            name = p.name.ifBlank { model.ifBlank { "未命名预设" } },
            protocol = c.optString("protocol").ifBlank { Protocols.first().id },
            baseUrl = c.optString("baseUrl"),
            model = model,
            credentialId = credentialOf(p.id, c),
            updatedAt = p.updatedAt,
            imported = p.sourceNamespace != null,
        )
    }

    /** Same key slot the core resolves: an explicit credential, the source provider's, else the preset's own ID. */
    private fun credentialOf(id: String, c: JSONObject): String =
        c.optString("credentialId").ifBlank { c.optString("sourceProviderId") }.ifBlank { id }

    private fun work(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            runCatching { block() }.onFailure { e -> _line.value = Line(e.readable(), true) }
            _busy.value = false
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

    private fun Throwable.readable(): String = (this as? StError)?.message ?: message ?: javaClass.simpleName
}
