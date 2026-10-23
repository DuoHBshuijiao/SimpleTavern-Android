package com.simpletavern.core.api

import com.simpletavern.core.data.ContentRepository
import com.simpletavern.core.data.NativeJsonInput
import com.simpletavern.core.model.StError
import com.simpletavern.core.model.GoogleVertexOptions
import com.simpletavern.core.model.ModelPreset
import kotlinx.serialization.json.JsonObject

/** Small provider router; login IDs are opaque to callers, no durable session/replay registry. */
internal class ProviderOAuthService(private val content: ContentRepository,
    private val copilot: CopilotAuthService, private val codex: CodexAuthService,
    private val vertex: VertexAuthService, private val subscription: SubscriptionAuthService) {
    internal class Connection(val token: String, val privateMaterials: List<String>,
        val baseUrl: String, val headers: Map<String, String>, val codexBackend: Boolean = false, val chatgptSubscription: Boolean = false) {
        override fun toString() = "ProviderOAuthConnection(<redacted>)"
    }
    suspend fun start(presetId: String): ProviderDeviceLogin = when (provider(presetId)) {
        "github-copilot" -> copilot.start(presetId)
        "openai-codex" -> codex.start(presetId)
        "xai-oauth" -> subscription.startDevice(presetId)
        else -> throw StError.Unsupported("Vertex service accounts do not use device login")
    }
    suspend fun poll(loginId: String): ProviderDeviceLogin = when {
        loginId.startsWith("copilot:") -> copilot.poll(loginId)
        loginId.startsWith("codex:") -> codex.poll(loginId)
        loginId.startsWith("chatgpt:") || loginId.startsWith("xai:") -> subscription.poll(loginId)
        else -> throw StError.NotFound("provider login")
    }
    suspend fun cancel(loginId: String): ProviderDeviceLogin = when {
        loginId.startsWith("copilot:") -> copilot.cancel(loginId)
        loginId.startsWith("codex:") -> codex.cancel(loginId)
        loginId.startsWith("chatgpt:") || loginId.startsWith("xai:") -> subscription.cancel(loginId)
        else -> throw StError.NotFound("provider login")
    }
    suspend fun startBrowser(presetId: String): ProviderBrowserLogin {
        if (provider(presetId) == "openai-chatgpt") return subscription.startBrowser(presetId)
        if (provider(presetId) != "openai-codex") throw StError.Unsupported("browser callback login is only available for Codex")
        return codex.startBrowser(presetId)
    }
    suspend fun completeBrowser(loginId: String, callback: String): ProviderDeviceLogin {
        if (loginId.startsWith("chatgpt:")) return subscription.completeBrowser(loginId, callback)
        if (!loginId.startsWith("codex:")) throw StError.NotFound("browser login")
        return codex.completeBrowser(loginId, callback)
    }
    suspend fun status(presetId: String): ProviderOAuthStatus = when (provider(presetId)) {
        "github-copilot" -> copilot.status(presetId)
        "openai-codex" -> codex.status(presetId)
        "openai-chatgpt", "xai-oauth" -> subscription.status(presetId, provider(presetId))
        else -> vertex.status(presetId)
    }
    suspend fun logout(presetId: String) {
        // Removing a preset or changing its provider must not strand a previous login record.
        copilot.logout(presetId); codex.logout(presetId); vertex.logout(presetId); subscription.logout(presetId)
    }
    suspend fun configureVertex(presetId: String, options: GoogleVertexOptions, expectedUpdatedAt: String): ModelPreset =
        vertex.configure(presetId, options, expectedUpdatedAt)
    suspend fun setVertexServiceAccount(presetId: String, json: String): ProviderOAuthStatus = vertex.setServiceAccount(presetId, json)
    suspend fun connection(presetId: String, forceRefresh: Boolean = false,
        expectedProvider: String? = null, allowRefresh: Boolean = true): Connection {
        val selected = provider(presetId)
        if (expectedProvider != null && expectedProvider != selected) throw StError.Conflict("OAuth provider changed before credential resolution")
        if (selected in setOf("openai-chatgpt", "xai-oauth")) return subscription.connection(presetId, selected, forceRefresh, allowRefresh)
        return if (selected == "github-copilot") copilot.connection(presetId, forceRefresh, allowRefresh).let {
            Connection(it.token, listOf(it.githubToken), it.baseUrl, CopilotAuthService.HEADERS)
        } else if (selected == "openai-codex") codex.connection(presetId, forceRefresh, allowRefresh).let {
            Connection(it.token, listOf(it.refreshToken), CodexAuthService.BASE,
                mapOf("originator" to "simpletavern", "OpenAI-Beta" to "responses=experimental",
                    "chatgpt-account-id" to it.accountId), codexBackend = true)
        } else vertex.connection(presetId, forceRefresh, allowRefresh).let {
            Connection(it.token, listOf(it.privateKey, "google-service-account:${it.accountEmail}"), it.baseUrl, emptyMap())
        }
    }
    suspend fun usage(presetId: String): ProviderSubscriptionUsage {
        val selected = provider(presetId)
        if (selected in setOf("openai-chatgpt", "xai-oauth")) return subscription.usage(presetId, selected)
        return ProviderSubscriptionUsage(selected, if (status(presetId).loggedIn) "unsupported" else "not_logged_in",
            java.time.Instant.now().toString(), errorCode = "usage_endpoint_not_documented")
    }
    private suspend fun provider(id: String): String {
        val preset = content.getPreset(id) ?: throw StError.NotFound("model preset")
        val config = NativeJsonInput.parse(preset.configJson) as? JsonObject ?: throw StError.Validation("model preset must be an object")
        return when {
            (config["providerId"] as? kotlinx.serialization.json.JsonPrimitive)?.content in setOf("openai-chatgpt", "xai-oauth") -> (config["providerId"] as kotlinx.serialization.json.JsonPrimitive).content
            ProviderConnectionSettings.isCopilot(config) -> "github-copilot"
            ProviderConnectionSettings.isCodex(config) -> "openai-codex"
            ProviderConnectionSettings.isVertexIam(config) -> "google-vertex"
            else -> throw StError.Unsupported("preset has no supported OAuth adapter")
        }
    }
}
