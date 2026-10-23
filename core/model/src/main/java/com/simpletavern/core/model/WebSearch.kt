package com.simpletavern.core.model

import kotlinx.serialization.Serializable

/** Secret values are deliberately absent. Store them through the encrypted credential API. */
@Serializable
data class WebSearchConnection(val credentialId: String, val baseUrl: String? = null, val parametersJson: String = "{}")
@Serializable
data class WebSearchConfiguration(val provider: String = "tavily", val geminiSearch: String = "native",
    val tavily: WebSearchConnection? = null, val bocha: WebSearchConnection? = null, val brave: WebSearchConnection? = null)
@Serializable
data class WebSearchConfigurationRecord(val version: Int = 1, val revision: Long = 0,
    val configuration: WebSearchConfiguration = WebSearchConfiguration(), val updatedAt: String? = null)
@Serializable
data class WebSearchVersion(val scope: String, val epoch: String, val revision: Long, val basis: String? = null)
@Serializable
data class WebSearchConfigurationSnapshot(val value: WebSearchConfigurationRecord, val version: WebSearchVersion,
    val configured: Map<String, Boolean>)
@Serializable
enum class WebSearchAccountState { NOT_CONFIGURED, NOT_CHECKED, UNSUPPORTED, AVAILABLE, FAILED }
/** Provider-reported values, not an invoice or locally inferred spend. Missing keys remain absent. */
@Serializable
data class WebSearchAccountUsage(val unit: String, val values: Map<String, String?>,
    val plan: String? = null, val currency: String? = null)
@Serializable
data class WebSearchAccountStatus(val provider: String, val configured: Boolean, val state: WebSearchAccountState,
    val checkedAt: String? = null, val usage: WebSearchAccountUsage? = null,
    val errorCode: String? = null, val httpStatus: Int? = null, val warnings: List<String> = emptyList())
@Serializable
data class WebSearchStatus(val version: Int = 1, val configurationVersion: WebSearchVersion, val provider: String,
    val capturedAt: String, val accounts: Map<String, WebSearchAccountStatus>,
    val independentModes: List<String> = listOf("tavily", "bocha", "brave"),
    val nativeModes: List<String> = listOf("openai_responses", "anthropic_messages", "gemini_generate_content"))
@Serializable
data class WebSearchOptions(val enabled: Boolean = false, val geminiSearch: String? = null, val assistantId: String? = null,
    val source: String? = null, val searchEnabled: Boolean = true, val fetchEnabled: Boolean = true)
@Serializable
data class WebSearchHit(val title: String, val url: String, val snippet: String,
    val publishedAt: String? = null, val truncated: Boolean = false)
@Serializable
data class WebSearchResult(val version: Int = 1, val provider: String, val hits: List<WebSearchHit>,
    val answer: String? = null, val truncated: Boolean = false, val credits: String? = null, val requestId: String? = null)
@Serializable
data class NativeSearchTrace(val provider: String, val metadataJson: String, val truncated: Boolean = false)
