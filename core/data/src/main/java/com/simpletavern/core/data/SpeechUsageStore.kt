package com.simpletavern.core.data

import androidx.room.withTransaction
import com.simpletavern.core.data.db.AppDatabase
import com.simpletavern.core.data.db.AppSettingsEntity
import com.simpletavern.core.data.db.UsageEventEntity
import com.simpletavern.core.model.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One row per explicit new synthesis. Publication/binding/cache operations are not vendor attempts. */
class SpeechUsageStore(private val db: AppDatabase, private val gate: DataOperationGate) {
    private val processEpoch = UUID.randomUUID().toString()
    private suspend fun epoch() = "$processEpoch:${db.fileChanges().restoreEpoch().orEmpty()}"
    private suspend fun rules(): SpeechPricingRuleSet {
        if ((db.config().settingsBytes(PRICING_ID) ?: 0) > 262_144) throw StError.Validation("speech pricing exceeds 256 KiB")
        val raw = db.config().getSettings(PRICING_ID)?.settingsJson ?: return SpeechPricingRuleSet()
        NativeJsonInput.parse(raw)
        return (Jsonx.decodeOrNull<SpeechPricingRuleSet>(raw) ?: throw StError.Validation("invalid speech pricing")).also(::validateRules)
    }
    suspend fun pricing() = withContext(Dispatchers.IO) { db.withTransaction {
        val value = rules(); SpeechPricingSnapshot(value, PricingVersion(epoch(), value.revision))
    } }
    suspend fun replacePricing(incoming: List<SpeechPricingRule>, expected: PricingVersion, catalog: Boolean = false) =
        withContext(Dispatchers.IO) { db.withTransaction {
            val previous = rules(); val currentEpoch = epoch()
            if (expected != PricingVersion(currentEpoch, previous.revision)) throw StError.Conflict("speech pricing changed; refresh before saving")
            val source = if (catalog) "catalog" else "user"
            if (incoming.any { it.source != source }) throw StError.Validation("speech pricing ownership mismatch")
            if (previous.revision == Long.MAX_VALUE) throw StError.Validation("speech pricing revision exhausted")
            val value = SpeechPricingRuleSet(revision = previous.revision + 1,
                rules = previous.rules.filter { it.source != source } + incoming, updatedAt = Instant.now().toString())
            validateRules(value)
            val raw = Jsonx.encode(value)
            if (raw.toByteArray(Charsets.UTF_8).size > 262_144) throw StError.Validation("speech pricing exceeds 256 KiB")
            db.config().upsertSettings(AppSettingsEntity(PRICING_ID, raw, value.updatedAt!!))
            SpeechPricingSnapshot(value, PricingVersion(currentEpoch, value.revision))
        } }

    suspend fun execute(provider: String, model: String, scope: String, sourceNamespace: String?,
        parameters: Map<String, String>, inputCharacters: Long, context: SpeechCallContext,
        run: suspend (beforeExecute: suspend () -> Unit) -> SpeechAudio): SpeechAudio = withContext(Dispatchers.IO) {
        val lease = gate.sharedLease() // prevents snapshot/restore splitting an event, not parallel network calls.
        try {
            currentCoroutineContext().ensureActive()
            val requestId = UUID.randomUUID().toString()
            val eventId = "tts:$requestId"
            var snapshot = withContext(NonCancellable) { db.withTransaction {
                val at = Instant.now().toString()
                val prices = try { rules() } catch (_: StError.Validation) { null }
                val probe = SpeechUsageSnapshot(provider = provider, model = model, scope = scope, sourceNamespace = sourceNamespace,
                    context = context, startedAt = at, inputCharacters = inputCharacters, parameters = parameters)
                val matching = prices?.rules.orEmpty().filter { matches(it, probe) }
                val preferred = matching.filter { it.source == "user" }.ifEmpty { matching }
                val maxSpecificity = preferred.maxOfOrNull(::specificity)
                val selected = preferred.filter { specificity(it) == maxSpecificity }.singleOrNull()
                val reason = when { prices == null -> "pricing_configuration_invalid"; preferred.isEmpty() -> "no_matching_price";
                    selected == null -> "ambiguous_speech_price"; else -> null }
                val value = probe.copy(pricingRevision = prices?.revision, pricingRule = selected, pricingReason = reason)
                validateSnapshot(value)
                val row = UsageEventEntity(eventId, eventId, requestId, context.chatId, "tts:$provider", model, Jsonx.encode(value), null, at)
                if (listOfNotNull(row.id, row.eventId, row.requestId, row.chatId, row.provider, row.model, row.createdAt)
                        .sumOf { it.toByteArray(Charsets.UTF_8).size } > 4096) throw StError.Validation("speech usage identity exceeds budget")
                if (db.usage().insertIgnore(row) == -1L) throw StError.Conflict("speech usage identity collision")
                value
            } }
            suspend fun save(next: SpeechUsageSnapshot, cost: CostSnapshot? = null) {
                validateSnapshot(next)
                withContext(NonCancellable) {
                    if (db.usage().finishSpeech(eventId, Jsonx.encode(snapshot), Jsonx.encode(next), cost?.let { Jsonx.encode(it) }) != 1)
                        throw StError.Conflict("speech usage changed before finalization")
                    snapshot = next // assignment protected from cancellation after the actual DB commit.
                }
            }
            var response: SpeechAudio? = null
            var failure: Throwable? = null
            try {
                currentCoroutineContext().ensureActive()
                response = run {
                    save(snapshot.copy(status = "running", httpAttempted = true))
                    currentCoroutineContext().ensureActive()
                }
                currentCoroutineContext().ensureActive()
            } catch (error: Throwable) { failure = error }
            val status = when (failure) { null -> "completed"; is CancellationException -> "cancelled"; else -> "failed" }
            val error = when (val e = failure) { null -> null; is CancellationException -> "cancelled";
                is StError.Speech -> e.code.takeIf { it in SAFE_ERRORS } ?: "tts_failed"; else -> "tts_failed" }
            val final = snapshot.copy(status = status, endedAt = maxOf(Instant.now(), PricingEngine.instant(snapshot.startedAt)).toString(),
                reportedCharacters = response?.reportedUsageCharacters, audioBytes = response?.bytes?.size?.toLong(), errorCode = error)
            try { withContext(NonCancellable) { save(final, cost(final)) } }
            catch (_: Exception) {
                val accountingError = StError.Speech("tts_accounting_failed", "speech usage finalization failed", mapOf("requestId" to requestId))
                if (failure != null) failure.addSuppressed(accountingError) else throw accountingError
            }
            failure?.let { throw it }
            response!!.let { SpeechAudio(it.provider, it.model, it.voiceId, it.format, it.sampleRate, it.bytes,
                it.reportedUsageCharacters, it.warnings, requestId, it.spokenText, context.preprocessingRequestId) }
        } finally { lease.close() }
    }

    /** Terminate unfinished records only. Nothing here can send a vendor request. */
    suspend fun interruptPending() = withContext(Dispatchers.IO) { gate.write {
        var after = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val rows = db.withTransaction {
                if (db.usage().pendingSpeechBudgets(after, 200).any { it.identityBytes > 4096 || it.usageBytes > 65_536 })
                    throw StError.Validation("pending speech usage exceeds row budget")
                db.usage().pendingSpeechPage(after, 200).also { rows -> rows.forEach { row ->
                    val envelope = NativeJsonInput.parse(row.event.usageJson) as? JsonObject ?: throw StError.Validation("invalid pending speech envelope")
                    if (envelope["_kind"] != JsonPrimitive("tts")) return@forEach
                    val before = Jsonx.decode<SpeechUsageSnapshot>(row.event.usageJson).also(::validateSnapshot)
                    if (before.status !in setOf("prepared", "running") || !consistent(row.event, before))
                        throw StError.Validation("invalid unfinished speech usage")
                    val final = before.copy(status = "interrupted", endedAt = maxOf(Instant.now(), PricingEngine.instant(before.startedAt)).toString(),
                        errorCode = "process_interrupted")
                    if (db.usage().finishSpeech(row.event.id, row.event.usageJson, Jsonx.encode(final), Jsonx.encode(cost(final))) != 1)
                        throw StError.Conflict("pending speech usage changed")
                } }
            }
            if (rows.isEmpty()) break
            after = rows.last().sequence
        }
    } }

    companion object {
        private const val PRICING_ID = "speech_pricing_v1"
        private val PROVIDERS = SpeechProviders.supported
        val PARAMETERS = setOf("format", "voice_id", "speed", "volume", "pitch", "emotion", "sample_rate", "gain", "endpoint_host", "stability", "similarity_boost", "language", "speech_rate", "resource_id")
        private val SAFE_ERRORS = setOf("tts_auth_failed", "tts_quota", "tts_redirect_refused", "tts_http_error", "tts_invalid_response",
            "tts_response_too_large", "tts_timeout", "tts_network_error", "tts_provider_error", "tts_format_mismatch")
        private fun identity(id: String) = id.isNotBlank() && id.toByteArray(Charsets.UTF_8).size <= 1024 && id.none(Char::isISOControl)
        private fun scope(value: String) = value == "global" || value.startsWith("assistant:") && identity(value.removePrefix("assistant:"))
        private fun parameters(value: Map<String, String>) = value.all { (key, text) -> key in PARAMETERS &&
            (text.isNotEmpty() || key == "voice_id") && text.length <= 512 && text.none(Char::isISOControl) }
        private fun specificity(rule: SpeechPricingRule) = rule.matchParameters.size + listOfNotNull(rule.model, rule.scope, rule.sourceNamespace).size
        private fun matches(rule: SpeechPricingRule, value: SpeechUsageSnapshot) = rule.enabled && rule.provider == value.provider &&
            (rule.model == null || rule.model == value.model) && (rule.scope == null || rule.scope == value.scope) &&
            (rule.sourceNamespace == null || rule.sourceNamespace == value.sourceNamespace) &&
            rule.matchParameters.all { (key, text) -> value.parameters[key] == text } &&
            (rule.effectiveFrom?.let { PricingEngine.instant(value.startedAt) >= PricingEngine.instant(it) } != false) &&
            (rule.effectiveTo?.let { PricingEngine.instant(value.startedAt) < PricingEngine.instant(it) } != false)
        fun validateRules(value: SpeechPricingRuleSet) {
            if (value.version != 1 || value.revision < 0 || value.rules.size > 200) throw StError.Validation("invalid speech pricing version/budget")
            value.updatedAt?.let { time(it) }
            val ids = mutableSetOf<String>()
            value.rules.forEach { rule ->
                if (!rule.id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{2,127}")) || !ids.add(rule.id) ||
                    rule.provider !in PROVIDERS || rule.unit !in setOf("request", "input_character", "reported_character") ||
                    !PricingEngine.currency(rule.currency) || rule.source !in setOf("user", "catalog") ||
                    (rule.source == "catalog") != rule.id.startsWith("catalog:") ||
                    rule.model?.let { it.isBlank() || it.length > 512 || it.any(Char::isISOControl) } == true ||
                    rule.scope?.let { !scope(it) } == true || rule.sourceNamespace?.let { !identity(it) } == true || !parameters(rule.matchParameters))
                    throw StError.Validation("invalid speech pricing identity/conditions/unit")
                if (PricingEngine.decimal(rule.amountPerUnit, 12) > BigDecimal("1000000000000")) throw StError.Validation("speech pricing rate exceeds budget")
                val from = rule.effectiveFrom?.let(::time)
                val to = rule.effectiveTo?.let(::time)
                if (from != null && to != null && from >= to) throw StError.Validation("invalid speech pricing interval")
            }
            if (Jsonx.encode(value).toByteArray(Charsets.UTF_8).size > 262_144) throw StError.Validation("speech pricing exceeds 256 KiB")
        }
        private fun time(value: String): Instant {
            if (value.length > 64) throw StError.Validation("speech accounting time exceeds budget")
            return PricingEngine.instant(value)
        }
        fun validRequestId(value: String?) = value?.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) == true
        fun consistent(row: UsageEventEntity, value: SpeechUsageSnapshot) = row.provider == "tts:${value.provider}" &&
            row.model == value.model && row.chatId == value.context.chatId && row.createdAt == value.startedAt &&
            validRequestId(row.requestId) && row.eventId == "tts:${row.requestId}" && row.id == row.eventId
        fun matchesAsset(value: SpeechUsageSnapshot, asset: SpeechAsset): Boolean {
            val spoken = asset.spokenText
            return value.provider == asset.provider && value.model == asset.model && value.status == "completed" &&
                value.scope == (asset.assistantId?.let { "assistant:$it" } ?: "global") && value.context.taskId == asset.id &&
                value.context.chatId == asset.chatId && value.context.messageCandidateId == asset.messageCandidateId &&
                value.context.preprocessingRequestId == asset.preprocessingRequestId &&
                value.reportedCharacters == asset.reportedUsageCharacters && value.audioBytes == asset.bytes &&
                value.parameters["format"] == asset.format && value.parameters["voice_id"] == asset.voiceId &&
                (asset.version != 2 || value.sourceNamespace == asset.sourceNamespace && spoken != null &&
                    value.inputCharacters == spoken.codePointCount(0, spoken.length).toLong())
        }
        fun validateSnapshot(value: SpeechUsageSnapshot) {
            if (value.version != 1 || value._kind != "tts" || value.provider !in PROVIDERS || !scope(value.scope) ||
                value.model.isBlank() || value.model.length > 512 || value.model.any(Char::isISOControl) ||
                listOfNotNull(value.sourceNamespace, value.context.chatId, value.context.taskId, value.context.messageCandidateId).any { !identity(it) } ||
                value.context.messageCandidateId != null && value.context.chatId == null || !parameters(value.parameters) ||
                value.context.preprocessingRequestId?.let { !validRequestId(it) } == true ||
                value.inputCharacters !in 1..10_000 || value.reportedCharacters?.let { it < 0 } == true ||
                value.audioBytes?.let { it !in 1..16_777_216 } == true || (value.pricingRevision ?: 0) < 0 ||
                value.errorCode?.matches(Regex("[a-z_]{1,64}")) == false || value.pricingReason?.matches(Regex("[a-z_]{1,64}")) == false ||
                value.status !in setOf("prepared", "running", "completed", "failed", "cancelled", "interrupted"))
                throw StError.Validation("invalid speech usage identity/version/budget")
            val pending = value.status in setOf("prepared", "running")
            if (pending && (value.endedAt != null || value.reportedCharacters != null || value.audioBytes != null || value.errorCode != null) ||
                !pending && value.endedAt == null || value.status == "prepared" && value.httpAttempted ||
                value.status in setOf("running", "completed") && !value.httpAttempted ||
                value.status == "completed" && (value.errorCode != null || value.audioBytes == null) ||
                (value.reportedCharacters != null || value.audioBytes != null) && !value.httpAttempted)
                throw StError.Validation("invalid speech usage lifecycle")
            time(value.startedAt)
            value.endedAt?.let { if (time(it) < time(value.startedAt)) throw StError.Validation("invalid speech usage time") }
            value.pricingRule?.let {
                validateRules(SpeechPricingRuleSet(rules = listOf(it)))
                if (value.pricingRevision == null || value.pricingReason != null || !matches(it, value)) throw StError.Validation("speech pricing snapshot mismatch")
            }
            if (Jsonx.encode(value).toByteArray(Charsets.UTF_8).size > 65_536) throw StError.Validation("speech usage exceeds 64 KiB")
        }
        fun cost(value: SpeechUsageSnapshot): CostSnapshot {
            val base = CostSnapshot(pricedAt = value.startedAt, pricingRevision = value.pricingRevision, pricingRuleId = value.pricingRule?.id)
            if (!value.httpAttempted) return base.copy(reason = "speech_request_not_sent")
            val rule = value.pricingRule ?: return base.copy(reason = value.pricingReason ?: "no_matching_price")
            // Only a successful response establishes our request/input-character estimate. Errors may still be billed.
            val quantity = when (rule.unit) {
                "reported_character" -> value.reportedCharacters?.let { BigDecimal.valueOf(it) }
                "input_character" -> BigDecimal.valueOf(value.inputCharacters).takeIf { value.status == "completed" }
                else -> BigDecimal.ONE.takeIf { value.status == "completed" }
            } ?: return base.copy(reason = if (value.status == "completed") "speech_characters_not_reported" else "speech_charge_unknown")
            val amount = PricingEngine.money(quantity.multiply(PricingEngine.decimal(rule.amountPerUnit)))
            if (runCatching { PricingEngine.decimal(amount) }.isFailure) return base.copy(reason = "speech_cost_exceeds_budget")
            return base.copy(source = "estimated", amount = amount, currency = rule.currency, estimatedAmount = amount, estimatedCurrency = rule.currency)
        }
    }
}
