package com.simpletavern.core.model

import kotlinx.serialization.Serializable

@Serializable
data class SpeechPricingRule(val id: String, val provider: String, val currency: String,
    val unit: String, val amountPerUnit: String, val model: String? = null, val scope: String? = null,
    val sourceNamespace: String? = null, val matchParameters: Map<String, String> = emptyMap(),
    val enabled: Boolean = true, val effectiveFrom: String? = null, val effectiveTo: String? = null,
    val source: String = "user")
@Serializable
data class SpeechPricingRuleSet(val version: Int = 1, val revision: Long = 0,
    val rules: List<SpeechPricingRule> = emptyList(), val updatedAt: String? = null)
@Serializable
data class SpeechPricingSnapshot(val value: SpeechPricingRuleSet, val version: PricingVersion)
@Serializable
data class SpeechCallContext(val chatId: String? = null, val taskId: String? = null, val messageCandidateId: String? = null,
    val preprocessingRequestId: String? = null)
/** No text, key, HTTP body, audio bytes, balance, or instruction to retry a prior request. */
@Serializable
data class SpeechUsageSnapshot(val version: Int = 1, val _kind: String = "tts", val provider: String,
    val model: String, val scope: String, val sourceNamespace: String? = null,
    val context: SpeechCallContext = SpeechCallContext(), val status: String = "prepared",
    val startedAt: String, val endedAt: String? = null, val httpAttempted: Boolean = false,
    val inputCharacters: Long, val reportedCharacters: Long? = null, val audioBytes: Long? = null,
    val errorCode: String? = null, val parameters: Map<String, String> = emptyMap(),
    val pricingRevision: Long? = null, val pricingRule: SpeechPricingRule? = null, val pricingReason: String? = null)
