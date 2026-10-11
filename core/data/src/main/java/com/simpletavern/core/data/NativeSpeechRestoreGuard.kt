package com.simpletavern.core.data

import com.simpletavern.core.model.*
import kotlinx.serialization.json.*

/** Protect references left outside an explicit archive replacement. No IO publication or old request replay. */
internal class NativeSpeechRestoreGuard(
    private val prior: suspend (String, String, String, Int) -> JsonElement?,
    private val combined: suspend (String, String, String, Int) -> JsonElement?,
    private val included: (String, String) -> Boolean,
    private val incomingFiles: Set<String>,
    private val issue: (NativeRestoreIssue) -> Unit,
) {
    private var readBytes = 0L
    val protectedFiles = linkedMapOf<String, Long>()
    private suspend fun read(table: String, id: String, column: String, limit: Int, incoming: Boolean): JsonElement? {
        val value = (if (incoming) combined else prior)(table, id, column, limit)
        if (value is JsonPrimitive && value.isString) readBytes += value.content.toByteArray(Charsets.UTF_8).size
        return value
    }
    private suspend fun text(table: String, id: String, column: String, incoming: Boolean = false, limit: Int = 1024) =
        read(table, id, column, limit, incoming)?.jsonPrimitive?.contentOrNull
    fun requireBudget() {
        if (readBytes > 67_108_864) throw StError.Validation("retained speech references exceed 64 MiB read budget")
    }
    private fun report(id: String, code: String, blocking: Boolean = true) = issue(NativeRestoreIssue(code,
        "message_candidates", buildJsonObject { put("id", id) }.toString().take(2048), column = "extraJson", blocking = blocking))
    private data class Catalog(val asset: SpeechAsset, val selection: SpeechSelection)
    private suspend fun catalogAsset(id: String, incoming: Boolean): Catalog? {
        if (text("tasks", id, "kind", incoming) != TaskKind.SPEECH_SYNTHESIS.name ||
            text("tasks", id, "status", incoming) != TaskStatus.SUCCEEDED.name) return null
        val raw = text("tasks", id, "payloadJson", incoming, SpeechTaskMetadata.MAX_PAYLOAD_BYTES) ?: return null
        NativeJsonInput.parse(raw)
        val progress = Jsonx.decodeOrNull<SpeechTaskProgress>(raw) ?: return null
        SpeechTaskMetadata.validate(progress)
        if (progress.taskId != id || progress.phase != "done") return null
        val asset = progress.asset ?: return null
        if (text("tasks", id, "chatId", incoming) != asset.chatId || !attachment(asset, incoming)) return null
        return Catalog(asset, progress.selection)
    }
    private suspend fun attachment(asset: SpeechAsset, incoming: Boolean): Boolean {
        val id = "tts:${asset.id}"
        val filename = "tts-${asset.id}.${asset.format}"
        val mime = when (asset.format) { "mp3" -> "audio/mpeg"; "pcm" -> "audio/pcm"; "opus" -> "audio/ogg"; else -> "audio/${asset.format}" }
        return text("attachments", id, "relativePath", incoming) == filename &&
            text("attachments", id, "filename", incoming) == filename && text("attachments", id, "mimeType", incoming) == mime &&
            read("attachments", id, "size", 64, incoming)?.jsonPrimitive?.longOrNull == asset.bytes &&
            text("attachments", id, "chatId", incoming) == asset.chatId &&
            text("attachments", id, "messageCandidateId", incoming) == asset.messageCandidateId &&
            read("attachments", id, "sourceUri", 1024, incoming) == JsonNull
    }
    private data class Reference(val chatId: String, val source: String?, val binding: SpeechMessageBinding, val catalog: Catalog)
    private fun binding(raw: String): SpeechMessageBinding? {
        val extra = NativeJsonInput.parse(raw) as? JsonObject ?: return null
        val value = extra["_tts"]?.takeIf { it != JsonNull } ?: return null
        return (Jsonx.decodeOrNull<SpeechMessageBinding>(value.toString()) ?: throw StError.Validation("invalid retained speech binding"))
            .also(SpeechTaskMetadata::validateBinding)
    }
    private suspend fun original(id: String): Reference? {
        val raw = text("message_candidates", id, "extraJson", limit = 262_144) ?: return null
        val binding = binding(raw) ?: return null
        val content = text("message_candidates", id, "content", limit = 262_144) ?: return null
        if ((binding.messageText ?: binding.sourceText) != content) {
            report(id, "PRESERVED_SPEECH_BINDING_STALE", false); return null // old edit, not new archive damage.
        }
        val chatId = text("message_candidates", id, "chatId") ?: return null
        if (text("chats", chatId, "id") == null) { report(id, "PRESERVED_SPEECH_SOURCE_ABSENT", false); return null }
        val source = text("chats", chatId, "sourceNamespace")
        val catalog = catalogAsset(binding.assetId, false) ?: run { report(id, "PRESERVED_SPEECH_CACHE_ABSENT", false); return null }
        val asset = catalog.asset
        if (asset.version == 2 && (asset.spokenText != binding.sourceText || asset.sourceNamespace != source)) {
            report(id, "PRESERVED_SPEECH_BINDING_INVALID", false); return null
        }
        return Reference(chatId, source, binding, catalog)
    }
    private suspend fun usage(asset: SpeechAsset, incoming: Boolean): SpeechUsageSnapshot? {
        val requestId = asset.requestId ?: return null
        val id = "tts:$requestId"
        val raw = text("usage_events", id, "usageJson", incoming, 65_536) ?: return null
        NativeJsonInput.parse(raw)
        val value = Jsonx.decodeOrNull<SpeechUsageSnapshot>(raw) ?: return null
        SpeechUsageStore.validateSnapshot(value)
        if (text("usage_events", id, "eventId", incoming) != id || text("usage_events", id, "requestId", incoming) != requestId ||
            text("usage_events", id, "provider", incoming) != "tts:${asset.provider}" || text("usage_events", id, "model", incoming, 2048) != asset.model ||
            text("usage_events", id, "chatId", incoming) != asset.chatId || text("usage_events", id, "createdAt", incoming) != value.startedAt ||
            !SpeechUsageStore.matchesAsset(value, asset)) return null
        val cost = text("usage_events", id, "costJson", incoming, 32_768)?.let { Jsonx.decodeOrNull<CostSnapshot>(it) }
        return value.takeIf { cost == SpeechUsageStore.cost(value) }
    }
    private suspend fun preprocessingUsage(catalog: Catalog, incoming: Boolean): SpeechPreprocessingUsage.Proof? {
        val selection = catalog.selection.preprocessing ?: return null
        val request = catalog.asset.preprocessingRequestId ?: return null
        return SpeechPreprocessingUsage.proof(selection, request,
            catalog.selection.preprocessingTaskId ?: catalog.asset.id, catalog.asset.chatId,
            catalog.asset.messageCandidateId) { column, budget -> read("usage_events", request, column, budget, incoming) }
    }
    private suspend fun sourceOwner(messageId: String, table: String, ownerId: String, asset: SpeechAsset) {
        if (!included(table, ownerId)) return
        val beforeExists = text(table, ownerId, "id") != null
        val before = if (beforeExists) text(table, ownerId, "sourceNamespace") else null
        val after = text(table, ownerId, "sourceNamespace", true)
        if (beforeExists && before == after) return // ordinary config/rate/enablement edits do not invalidate audio.
        val expected = if (asset.version == 2) asset.sourceNamespace else before
        if ((asset.version == 2 || beforeExists) && after != expected) report(messageId, "PRESERVED_SPEECH_CONFIGURATION_SOURCE_CHANGED")
    }
    suspend fun inspect(id: String) {
        if (included("message_candidates", id)) return // this message is explicitly replaced; incoming binding checks apply instead.
        val original = try { original(id) }
        catch (_: StError.Validation) { report(id, "PRESERVED_SPEECH_UNREADABLE", false); null }
        catch (_: StError.Unsupported) { report(id, "PRESERVED_SPEECH_UNREADABLE", false); null }
        original ?: return
        val asset = original.catalog.asset
        val originChat = asset.chatId
        val originMessage = asset.messageCandidateId
        val path = "attachments/tts-${asset.id}.${asset.format}"
        if (path in incomingFiles) protectedFiles[path] = asset.bytes // file layer checks old regular-file size and complete bytes separately.
        val affected = included("tasks", asset.id) || included("attachments", "tts:${asset.id}") ||
            included("chats", original.chatId) || originChat?.let { included("chats", it) } == true ||
            asset.version == 1 && originMessage?.let { included("message_candidates", it) } == true ||
            asset.assistantId?.let { included("assistants", it) } == true || included("model_presets", original.catalog.selection.presetId) ||
            asset.requestId?.let { included("usage_events", "tts:$it") } == true ||
            asset.preprocessingRequestId?.let { included("usage_events", it) } == true ||
            original.catalog.selection.preprocessing?.let { included("model_presets", it.llmPresetId) } == true
        if (!affected) return
        try {
            val next = catalogAsset(asset.id, true)
            // Asset identities are immutable; explicit replacement of a task is not consent to rebind a retained message.
            if (next == null || next != original.catalog) report(id, "PRESERVED_SPEECH_ASSET_CHANGED")
            if (text("chats", original.chatId, "id", true) == null ||
                text("chats", original.chatId, "sourceNamespace", true) != original.source)
                report(id, "PRESERVED_SPEECH_SOURCE_CHANGED")
            if (asset.version == 1 && originChat != null && included("chats", originChat) &&
                text("chats", originChat, "sourceNamespace", true) != text("chats", originChat, "sourceNamespace"))
                report(id, "PRESERVED_SPEECH_ORIGIN_CHANGED")
            if (asset.version == 1 && originMessage != null && included("message_candidates", originMessage)) {
                val before = try { text("message_candidates", originMessage, "extraJson", limit = 262_144)?.let(::binding) }
                catch (_: StError.Validation) { null }
                catch (_: StError.Unsupported) { null }
                if (before?.assetId == asset.id && before.sourceText == original.binding.sourceText) {
                    val after = text("message_candidates", originMessage, "extraJson", true, 262_144)?.let(::binding)
                    if (after?.assetId != asset.id || after.sourceText != before.sourceText)
                        report(id, "PRESERVED_SPEECH_TRANSCRIPT_PROOF_CHANGED")
                }
            }
            sourceOwner(id, "model_presets", original.catalog.selection.presetId, asset)
            original.catalog.selection.preprocessing?.let { sourceOwner(id, "model_presets", it.llmPresetId, asset) }
            asset.assistantId?.let { sourceOwner(id, "assistants", it, asset) }
            asset.requestId?.let { request ->
                if (included("usage_events", "tts:$request")) {
                    val before = try { usage(asset, false) } catch (_: StError.Validation) { null } catch (_: StError.Unsupported) { null }
                    if (before == null) report(id, "PRESERVED_SPEECH_USAGE_UNVERIFIED", false)
                    else if (usage(asset, true) != before) report(id, "PRESERVED_SPEECH_USAGE_CHANGED")
                }
            }
            asset.preprocessingRequestId?.let { request ->
                if (included("usage_events", request)) {
                    val before = try { preprocessingUsage(original.catalog, false) } catch (_: StError.Validation) { null }
                    if (before == null) report(id, "PRESERVED_SPEECH_PREPROCESS_USAGE_UNVERIFIED", false)
                    else if (preprocessingUsage(original.catalog, true) != before) report(id, "PRESERVED_SPEECH_PREPROCESS_USAGE_CHANGED")
                }
            }
        } catch (_: StError.Validation) { report(id, "PRESERVED_SPEECH_ASSET_UNREADABLE") }
        catch (_: StError.Unsupported) { report(id, "PRESERVED_SPEECH_ASSET_UNREADABLE") }
    }
}
