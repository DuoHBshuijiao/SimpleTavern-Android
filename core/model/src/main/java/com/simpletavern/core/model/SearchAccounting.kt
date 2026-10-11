package com.simpletavern.core.model

import kotlinx.serialization.Serializable

@Serializable
data class SearchPricingRule(val id: String, val provider: String, val currency: String,
    val unit: String, val amountPerUnit: String, val matchParameters: Map<String, String> = emptyMap(),
    val enabled: Boolean = true, val effectiveFrom: String? = null, val effectiveTo: String? = null,
    val source: String = "user")
@Serializable
data class SearchPricingRuleSet(val version: Int = 1, val revision: Long = 0,
    val rules: List<SearchPricingRule> = emptyList(), val updatedAt: String? = null)
@Serializable
data class SearchPricingSnapshot(val value: SearchPricingRuleSet, val version: PricingVersion)
@Serializable
data class SearchCallContext(val chatId: String? = null, val taskId: String? = null, val callId: String? = null)
/** A usage event, not search contents, an API key, a balance snapshot, or a replay instruction. */
@Serializable
data class SearchUsageSnapshot(val version: Int = 1, val _kind: String = "web_search",
    val provider: String, val scope: String, val context: SearchCallContext = SearchCallContext(),
    val status: String = "prepared", val startedAt: String, val endedAt: String? = null,
    val httpAttempted: Boolean = false, val credits: String? = null, val errorCode: String? = null,
    val parameters: Map<String, String> = emptyMap(), val pricingRevision: Long? = null,
    val pricingRule: SearchPricingRule? = null, val pricingReason: String? = null)
