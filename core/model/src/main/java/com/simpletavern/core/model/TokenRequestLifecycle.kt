package com.simpletavern.core.model

import kotlinx.serialization.Serializable

/** Local execution evidence, not proof that a server received a request or charged for it. */
@Serializable
data class TokenRequestLifecycle(val version: Int = 1, val httpAttempted: Boolean = false,
    val attemptedAt: String? = null, val endedAt: String? = null, val errorCode: String? = null)

/** Process-local outcome; completion describes the vendor stream, not the business commit. */
data class TokenRequestOutcome(val tokens: UsageTokens, val completed: Boolean,
    val firstTokenLatencyMs: Long? = null, val totalDurationMs: Long)
