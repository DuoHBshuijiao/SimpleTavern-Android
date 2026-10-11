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
    private val vertex: VertexAuthService) {
    internal class Connection(val token: String, val privateMaterials: List<String>,
        val baseUrl: String, val headers: Map<String, String>, val codexBackend: Boolean = false) {
        override fun toString() = "ProviderOAuthConnection(<redacted>)"
    }
    suspend fun start(presetId: String): ProviderDeviceLogin = when (provider(presetId)) {
        "github-copilot" -> copilot.start(presetId)
        "openai-codex" -> codex.start(presetId)
        else -> throw StError.Unsupported("Vertex service accounts do not use device login")
    }
    suspend fun poll(loginId: String): ProviderDeviceLogin = when {
        loginId.startsWith("copilot:") -> copilot.poll(loginId)
        loginId.startsWith("codex:") -> codex.poll(loginId)
        else -> throw StError.NotFound("provider login")
    }
    suspend fun cancel(loginId: String): ProviderDeviceLogin = when {
        loginId.startsWith("copilot:") -> copilot.cancel(loginId)
        loginId.startsWith("codex:") -> codex.cancel(loginId)
        else -> throw StError.NotFound("provider login")
    }
    suspend fun startBrowser(presetId: String): ProviderBrowserLogin {
        if (provider(presetId) != "openai-codex") throw StError.Unsupported("browser callback login is only available for Codex")
        return codex.startBrowser(presetId)
    }
    suspend fun completeBrowser(loginId: String, callback: String): ProviderDeviceLogin {
        if (!loginId.startsWith("codex:")) throw StError.NotFound("browser login")
        return codex.completeBrowser(loginId, callback)
    }
    suspend fun status(presetId: String): ProviderOAuthStatus = when (provider(presetId)) {
        "github-copilot" -> copilot.status(presetId)
        "openai-codex" -> codex.status(presetId)
        else -> vertex.status(presetId)
    }
    suspend fun logout(presetId: String) {
        // Removing a preset or changing its provider must not strand a previous login record.
        copilot.logout(presetId); codex.logout(presetId); vertex.logout(presetId)
    }
    suspend fun configureVertex(presetId: String, options: GoogleVertexOptions, expectedUpdatedAt: String): ModelPreset =
        vertex.configure(presetId, options, expectedUpdatedAt)
    suspend fun setVertexServiceAccount(presetId: String, json: String): ProviderOAuthStatus = vertex.setServiceAccount(presetId, json)
    suspend fun connection(presetId: String, forceRefresh: Boolean = false,
        expectedProvider: String? = null, allowRefresh: Boolean = true): Connection {
        val selected = provider(presetId)
        if (expectedProvider != null && expectedProvider != selected) throw StError.Conflict("OAuth provider changed before credential resolution")
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
    private suspend fun provider(id: String): String {
        val preset = content.getPreset(id) ?: throw StError.NotFound("model preset")
        val config = NativeJsonInput.parse(preset.configJson) as? JsonObject ?: throw StError.Validation("model preset must be an object")
        return when {
            ProviderConnectionSettings.isCopilot(config) -> "github-copilot"
            ProviderConnectionSettings.isCodex(config) -> "openai-codex"
            ProviderConnectionSettings.isVertexIam(config) -> "google-vertex"
            else -> throw StError.Unsupported("preset has no supported OAuth adapter")
        }
    }
}
