package com.simpletavern.core.model

import kotlinx.serialization.Serializable

@Serializable
data class McpToolSelection(val name: String, val enabled: Boolean = true, val needsApproval: Boolean = false)

/** Public configuration contains header names, never authentication values. */
@Serializable
data class McpServerConfiguration(
    val id: String,
    val name: String,
    val transport: String,
    val url: String = "",
    val enabled: Boolean = true,
    val headers: Map<String, String> = emptyMap(),
    val credentialHeaders: List<String> = emptyList(),
    val tools: List<McpToolSelection> = emptyList(),
    val command: String? = null,
    val args: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    val credentialEnv: List<String> = emptyList(),
    val sandboxId: String? = null,
    val oauth: McpOAuthOptions? = null,
)

/** Public-client options only. Secrets and dynamically issued client records stay encrypted. */
@Serializable
data class McpOAuthOptions(
    val enabled: Boolean = true,
    val clientId: String? = null,
    val redirectUri: String = "http://127.0.0.1:1456/oauth/mcp/callback",
    val scopes: List<String> = emptyList(),
    val authorizationServer: String? = null,
    val clientMetadataUrl: String? = null,
    val allowDynamicRegistration: Boolean = true,
)

@Serializable
data class McpOAuthDiscovery(val assistantId: String, val serverId: String, val resource: String,
    val resourceMetadataUrl: String, val authorizationServers: List<String>, val scopesSupported: List<String>,
    val challengedScopes: List<String>?)

@Serializable
class McpBrowserLogin(val loginId: String, val assistantId: String, val serverId: String,
    val authorizeUrl: String, val redirectUri: String, val issuer: String, val resource: String,
    val scopes: List<String>, val expiresAt: Long) {
    override fun toString() = "McpBrowserLogin(loginId=$loginId, authorizeUrl=<private>)"
}

@Serializable
data class McpOAuthStatus(val assistantId: String, val serverId: String, val authorized: Boolean,
    val expiresAt: Long? = null, val needsRefresh: Boolean = false, val canRefresh: Boolean = false,
    val issuer: String? = null, val resource: String? = null, val scopes: List<String> = emptyList())

@Serializable
data class McpToolDescriptor(
    val serverId: String,
    val serverName: String,
    val name: String,
    val modelName: String,
    val description: String,
    val inputSchemaJson: String,
    val needsApproval: Boolean = false,
)

@Serializable
data class McpConfiguration(
    val assistantId: String,
    val assistantUpdatedAt: String,
    val servers: List<McpServerConfiguration>,
    val selectedIds: Set<String>,
)

@Serializable
data class McpServerCapabilities(
    val protocolVersion: String,
    val tools: Boolean,
    val resources: Boolean,
    val prompts: Boolean,
    val completions: Boolean,
    val resourceSubscriptions: Boolean = false,
    val resourceListChanged: Boolean = false,
    val toolListChanged: Boolean = false,
    val promptListChanged: Boolean = false,
)

/** Public change hint only; never contains raw protocol events, credentials, or resource bodies. */
@Serializable
data class McpNotificationEvent(
    val assistantId: String,
    val serverId: String,
    val method: String,
    val uri: String? = null,
    val sourceNamespace: String? = null,
)

@Serializable
data class McpResourceDescriptor(
    val uri: String, val name: String, val title: String? = null,
    val description: String? = null, val mimeType: String? = null,
    val size: Long? = null, val metadataJson: String = "{}",
)

@Serializable
data class McpResourceTemplateDescriptor(
    val uriTemplate: String, val name: String, val title: String? = null,
    val description: String? = null, val mimeType: String? = null,
    val metadataJson: String = "{}",
)

@Serializable
data class McpPromptArgument(val name: String, val description: String? = null, val required: Boolean = false)

@Serializable
data class McpPromptDescriptor(
    val name: String, val title: String? = null, val description: String? = null,
    val arguments: List<McpPromptArgument> = emptyList(), val metadataJson: String = "{}",
)

@Serializable
data class McpResourceContents(
    val uri: String, val mimeType: String? = null, val text: String? = null,
    val mediaIndex: Int? = null, val metadataJson: String = "{}",
)

/** Request-owned content. Decoded bytes are not persisted or injected into chat automatically. */
class McpResourceResult(
    val assistantId: String, val serverId: String, val uri: String,
    val contents: List<McpResourceContents>, val media: List<ToolMedia>,
) {
    override fun toString() = "McpResourceResult(serverId=$serverId, contents=${contents.size}, media=${media.size}, content=<private>)"
}

@Serializable
data class McpPromptMessage(val role: ChatRole, val contentJson: String)

/** A user-selected draft, not system instructions and not an executed tool result. */
class McpPromptResult(
    val assistantId: String, val serverId: String, val name: String,
    val description: String?, val messages: List<McpPromptMessage>, val media: List<ToolMedia>,
    val sourceNamespace: String? = null,
) {
    override fun toString() = "McpPromptResult(serverId=$serverId, messages=${messages.size}, media=${media.size}, content=<private>)"
}

/** Receipt for an explicitly appended prompt, not a generation or tool-execution result. */
@Serializable
data class McpPromptApplication(val chatId: String, val assistantId: String, val serverId: String,
    val promptName: String, val nodeIds: List<String>, val candidateIds: List<String>,
    val attachmentIds: List<String>, val chatUpdatedAt: String)

@Serializable
enum class McpCompletionTarget { PROMPT, RESOURCE }

@Serializable
data class McpCompletionReference(val target: McpCompletionTarget, val value: String)

@Serializable
data class McpCompletion(val values: List<String>, val total: Int? = null, val hasMore: Boolean = false)
