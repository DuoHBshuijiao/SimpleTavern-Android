package com.simpletavern.core.data

import com.simpletavern.core.model.*
import kotlinx.serialization.json.*

/** Bounded native historical link proof; no credential lookup, current-price recalculation or HTTP. */
internal object SpeechPreprocessingUsage {
    data class Proof(val usage: JsonObject, val cost: JsonObject, val startedAt: String)
    suspend fun proof(selection: SpeechPreprocessSelection, requestId: String, taskId: String,
        chatId: String?, candidateId: String?, read: suspend (String, Int) -> JsonElement?): Proof? {
        SpeechPreprocessMetadata.validate(selection)
        if (!SpeechPreprocessMetadata.validRequestId(requestId)) return null
        suspend fun text(column: String, budget: Int = 1024): String? = read(column, budget)?.takeIf { it != JsonNull }?.let {
            (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw StError.Validation("preprocessing usage column must be text")
        }
        if (text("id") != requestId || text("eventId") != requestId || text("requestId") != requestId ||
            text("provider") != selection.credentialId || text("model", 2048) != selection.llmModel || text("chatId") != chatId) return null
        val raw = text("usageJson", 65_536) ?: return null
        val usage = NativeJsonInput.parse(raw) as? JsonObject ?: return null
        if (usage["_kind"]?.let { it != JsonPrimitive("llm") } == true) return null
        Jsonx.decodeOrNull<UsageTokens>(raw) ?: return null
        val context = usage["_recordContext"]?.let { Jsonx.decodeOrNull<UsageRecordContext>(it.toString()) } ?: return null
        if (context.purpose != "tts_preprocess" || context.status != "completed" || context.protocol != selection.protocol.name ||
            context.requestedModel != selection.llmModel || context.sourceNamespace != selection.sourceNamespace ||
            context.scope != (selection.assistantId?.let { "assistant:$it" } ?: "global") || context.taskId != taskId ||
            context.messageCandidateId != candidateId || context.startedAt != text("createdAt", 64)) return null
        val at = context.startedAt ?: return null
        PricingEngine.instant(at)
        val costRaw = text("costJson", 32_768) ?: return null
        val cost = NativeJsonInput.parse(costRaw) as? JsonObject ?: return null
        val value = Jsonx.decodeOrNull<CostSnapshot>(costRaw) ?: return null
        if (value.version != 1 || value.source !in setOf("unknown", "provider", "estimated")) return null
        if (value.source != "unknown" && (value.currency?.let(PricingEngine::currency) != true ||
                value.amount?.let { runCatching { PricingEngine.decimal(it) }.isSuccess } != true)) return null
        if (usage.containsKey("_requestLifecycle")) {
            val durable = TokenRequestAccounting.parse(com.simpletavern.core.data.db.UsageEventEntity(requestId, requestId, requestId,
                chatId, selection.credentialId, selection.llmModel, raw, costRaw, at)) ?: return null
            if (!durable.lifecycle.httpAttempted || durable.context.status != "completed") return null
        }
        return Proof(usage, cost, at)
    }
}
