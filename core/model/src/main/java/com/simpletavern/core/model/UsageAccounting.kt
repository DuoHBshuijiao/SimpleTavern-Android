package com.simpletavern.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ProviderReportedCost(val amount: String, val currency: String? = null)

@Serializable
data class PricingRule(
    val id: String,
    val provider: String? = null,
    val canonicalModelId: String? = null,
    val aliases: List<String> = emptyList(),
    val regexAliases: List<String> = emptyList(),
    val currency: String = "USD",
    val inputPerMillion: String? = null,
    val outputPerMillion: String? = null,
    val cacheReadPerMillion: String? = null,
    val cacheWritePerMillion: String? = null,
    val cacheWriteLongPerMillion: String? = null,
    val serviceTier: String = "standard",
    val enabled: Boolean = true,
    val effectiveFrom: String? = null,
    val effectiveTo: String? = null,
    val sourceUrl: String? = null,
    val source: String = "user",
    val coversMixedModalities: Boolean = false,
)

@Serializable
data class PricingRuleSet(val version: Int = 1, val revision: Long = 0,
                          val rules: List<PricingRule> = emptyList(), val updatedAt: String? = null)
@Serializable
data class PricingVersion(val epoch: String, val revision: Long)
@Serializable
data class PricingSnapshot(val value: PricingRuleSet, val version: PricingVersion)

@Serializable
data class CostComponent(val name: String, val tokens: Long?, val ratePerMillion: String?, val amount: String?)
@Serializable
data class CostSnapshot(
    val version: Int = 1,
    val source: String = "unknown",
    val amount: String? = null,
    val currency: String? = null,
    val providerReported: ProviderReportedCost? = null,
    val estimatedAmount: String? = null,
    val estimatedCurrency: String? = null,
    val partialEstimatedAmount: String? = null,
    val pricingRuleId: String? = null,
    val pricingRevision: Long? = null,
    val pricedAt: String? = null,
    val reason: String? = null,
    val components: List<CostComponent> = emptyList(),
)

@Serializable
data class UsageRecordContext(
    val protocol: String? = null, val status: String? = null, val startedAt: String? = null,
    val requestedModel: String? = null, val resolvedModel: String? = null,
    val firstTokenLatencyMs: Long? = null, val totalDurationMs: Long? = null,
    val purpose: String? = null, val scope: String? = null, val sourceNamespace: String? = null,
    val taskId: String? = null, val messageCandidateId: String? = null,
    val segmentIndex: Int? = null, val roundIndex: Int? = null, val turnIndex: Int? = null,
)

data class UsageAttempt(val requestId: String, val tokens: UsageTokens, val context: UsageRecordContext)

@Serializable
data class UsageFilter(val chatId: String? = null, val provider: String? = null, val model: String? = null,
                       val since: String? = null, val until: String? = null, val kind: String? = null)
@Serializable
data class UsageCursor(val epoch: String, val filter: UsageFilter, val afterSequence: Long = 0)
@Serializable
data class UsageLedgerItem(
    val sequence: Long, val id: String, val eventId: String, val requestId: String,
    val chatId: String?, val provider: String?, val model: String?, val createdAt: String,
    val usage: UsageTokens?, val context: UsageRecordContext? = null, val cost: CostSnapshot? = null,
    val issues: List<String> = emptyList(),
    val kind: String = "llm", val search: SearchUsageSnapshot? = null, val speech: SpeechUsageSnapshot? = null,
    val tokenRequestLifecycle: TokenRequestLifecycle? = null,
    val geminiCache: GeminiCacheTaskProgress? = null,
    val voiceDesign: SpeechVoiceDesignUsage? = null,
    val voiceClone: SpeechVoiceCloneUsage? = null,
)
@Serializable
data class UsagePage(val items: List<UsageLedgerItem>, val nextCursor: UsageCursor,
                     val throughSequence: Long, val hasMore: Boolean, val scanned: Int,
                     val excludedInvalidTime: Int = 0)
@Serializable
data class CurrencyCostTotal(val currency: String, val providerAmount: String,
                            val estimatedOnlyAmount: String, val comparisonEstimatedAmount: String,
                            val totalKnownAmount: String)
@Serializable
data class GeminiCacheQuoteTotal(val currency: String, val creationAmount: String,
    val projectedStorageAmount: String, val creationQuoteCount: Long, val storageQuoteCount: Long)
@Serializable
data class UsageSummary(
    val eventCount: Long, val statusCounts: Map<String, Long>,
    val reportedInputTokens: String, val reportedOutputTokens: String,
    val cacheReadInputTokens: String, val cacheWriteInputTokens: String,
    val unknownInputCount: Long, val unknownOutputCount: Long, val unknownCostCount: Long,
    val costsByCurrency: List<CurrencyCostTotal>, val invalidRecordCount: Long,
    val throughSequence: Long, val epoch: String,
    val excludedInvalidTime: Long = 0,
    val unknownCacheReadCount: Long = 0,
    val unknownCacheWriteCount: Long = 0,
    val searchEventCount: Long = 0,
    val reportedSearchCredits: Map<String, String> = emptyMap(),
    val unknownSearchCreditsCount: Long = 0,
    val speechEventCount: Long = 0,
    val speechInputCharacters: String = "0",
    val reportedSpeechCharacters: Map<String, String> = emptyMap(),
    val unknownReportedSpeechCharactersCount: Long = 0,
    val cacheOperationCount: Long = 0,
    val cacheCreationCount: Long = 0,
    val reportedCacheCreatedTokens: String = "0",
    val unknownCacheCreatedTokensCount: Long = 0,
    // Confirmed CREATE only. Projections are deliberately outside costsByCurrency/totalKnownAmount.
    val cacheQuotesByCurrency: List<GeminiCacheQuoteTotal> = emptyList(),
    val unknownCacheCreationQuoteCount: Long = 0,
    val unknownCacheStorageQuoteCount: Long = 0,
    val cacheListingCount: Long = 0,
    val reportedCacheListedResources: String = "0",
    val unknownCacheListingResultCount: Long = 0,
    val voiceDesignEventCount: Long = 0,
    val voiceDesignInputCharacters: String = "0",
    val voiceCloneEventCount: Long = 0,
    val voiceCloneInputCharacters: String = "0",
)
