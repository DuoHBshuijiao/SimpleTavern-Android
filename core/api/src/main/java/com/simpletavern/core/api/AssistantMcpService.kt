package com.simpletavern.core.api

import com.simpletavern.core.data.*
import com.simpletavern.core.model.*
import com.simpletavern.core.tools.McpClient
import com.simpletavern.core.tools.McpToolContent
import com.simpletavern.core.tools.ToolResult
import com.simpletavern.runtime.sandbox.SandboxManager
import java.util.Locale
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*

/** Source-selected servers, encrypted header credentials and request-local connections. */
internal class AssistantMcpService(
    private val content: ContentRepository,
    private val credentials: CredentialStore,
    private val gate: DataOperationGate,
    private val sandbox: SandboxManager,
) {
    private val oauth = McpOAuthService(credentials, gate) { assistantId, serverId ->
        val config = configuration(assistantId)
        val server = selected(config, includeUnselected = true).find { it.id == serverId }
            ?: throw StError.NotFound("MCP server configuration")
        oauthBinding(config, server)
    }
    private fun oauthBinding(config: AssistantConfig, server: McpServerConfiguration): McpOAuthService.Binding {
        val options = server.oauth ?: McpOAuthOptions(enabled = false)
        return McpOAuthService.Binding(config.id, config.sourceNamespace ?: "assistant:${config.id}", server.id, server.url, options, server.transport)
    }
    suspend fun discoverOAuth(assistantId: String, serverId: String) = oauth.discover(assistantId, serverId)
    suspend fun startOAuth(assistantId: String, serverId: String, scopes: List<String>?) = oauth.start(assistantId, serverId, scopes)
    suspend fun completeOAuth(loginId: String, callbackUrl: String) = oauth.complete(loginId, callbackUrl)
    suspend fun cancelOAuth(loginId: String) = oauth.cancel(loginId)
    suspend fun oauthStatus(assistantId: String, serverId: String) = oauth.status(assistantId, serverId)
    suspend fun refreshOAuth(assistantId: String, serverId: String) = oauth.refresh(assistantId, serverId)
    suspend fun logoutOAuth(assistantId: String, serverId: String) = oauth.logout(assistantId, serverId)
    private fun JsonObject.text(name: String) = (get(name) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.flag(name: String, default: Boolean) = (get(name) as? JsonPrimitive)?.booleanOrNull ?: default
    private fun secretHeader(name: String): Boolean {
        val key = name.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
        return key in setOf("authorization", "cookie", "proxyauthorization", "authenticationtoken") ||
            key.contains("apikey") || key.contains("token") || key.contains("secret") || key.contains("password")
    }
    private fun credentialId(config: AssistantConfig, serverId: String, header: String): String {
        val scope = config.sourceNamespace ?: "assistant:${config.id}"
        return "mcp:${scope.length}:$scope:${serverId.length}:$serverId:${header.lowercase(Locale.ROOT)}"
    }
    private suspend fun configuration(id: String) = content.getAssistant(id) ?: throw StError.NotFound("assistant configuration")

    private suspend fun selected(config: AssistantConfig, includeUnselected: Boolean = false): List<McpServerConfiguration> {
        val assistant = Jsonx.decode<JsonObject>(config.settingsJson)
        val selectedIds = (assistant["mcpServers"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
        if (selectedIds.isEmpty() && !includeUnselected) return emptyList()
        val inline = assistant["mcpServerConfigs"] as? JsonArray
        val rows = inline ?: run {
            val namespace = config.sourceNamespace
            val root = if (namespace != null) content.getSourceSettingsJson(namespace) else content.getSettingsJson()
            (root?.let { Jsonx.decode<JsonObject>(it)["mcpServers"] } as? JsonArray) ?: JsonArray(emptyList())
        }
        val servers = rows.map { it.jsonObject }.filter { includeUnselected || it.text("id") in selectedIds }.map { raw ->
            val id = raw.text("id") ?: throw StError.Validation("MCP server identity missing")
            val options = raw["commonOptions"] as? JsonObject ?: raw
            val publicHeaders = linkedMapOf<String, String>()
            val credentialHeaders = linkedSetOf<String>()
            fun header(name: String, value: String?) {
                if (secretHeader(name) || value == null) credentialHeaders.add(name)
                else publicHeaders[name] = value
            }
            when (val container = options["headers"]) {
                is JsonObject -> container.forEach { (name, value) -> header(name, (value as? JsonPrimitive)?.contentOrNull) }
                is JsonArray -> container.forEach { entry ->
                    if (entry is JsonObject) {
                        val name = entry.text("first") ?: entry.text("name") ?: entry.text("key")
                            ?: throw StError.Validation("MCP header name missing")
                        header(name, entry.text("second") ?: entry.text("value"))
                    } else if (entry is JsonArray && entry.size in 1..2) {
                        header(entry[0].jsonPrimitive.content, (entry.getOrNull(1) as? JsonPrimitive)?.contentOrNull)
                    } else throw StError.Validation("invalid MCP header")
                }
                null -> Unit
                else -> throw StError.Validation("invalid MCP headers")
            }
            (options["credentialHeaders"] as? JsonArray).orEmpty().forEach { credentialHeaders.add(it.jsonPrimitive.content) }
            val oauthOptions = (options["oauth"] as? JsonObject)?.let { value ->
                val scope = (value["scopes"] as? JsonArray)?.map { it.jsonPrimitive.content }
                    ?: value.text("scope")?.split(' ')?.filter(String::isNotBlank).orEmpty()
                McpOAuthOptions(value.flag("enabled", false), value.text("clientId"),
                    value.text("redirectUri") ?: McpOAuthOptions().redirectUri, scope,
                    value.text("authorizationServer"), value.text("clientMetadataUrl"), value.flag("allowDynamicRegistration", true))
            }
            if (oauthOptions?.enabled == true) {
                credentialHeaders.removeAll { it.equals("Authorization", ignoreCase = true) }
                publicHeaders.keys.removeAll { it.equals("Authorization", ignoreCase = true) }
            }
            credentialHeaders.forEach { name -> publicHeaders.keys.removeAll { it.equals(name, ignoreCase = true) } }
            if ((publicHeaders.keys + credentialHeaders).any { it.lowercase(Locale.ROOT) in setOf(
                    "host", "content-length", "connection", "accept", "content-type", "mcp-session-id", "mcp-protocol-version") })
                throw StError.Validation("MCP headers cannot override transport headers")
            val transport = raw.text("type") ?: if (raw.text("command") != null) "stdio" else "streamable_http"
            if (transport == "stdio" && oauthOptions?.enabled == true)
                throw StError.Validation("stdio uses environment credentials, not HTTP OAuth")
            val environment = linkedMapOf<String, String>()
            val credentialEnv = linkedSetOf<String>()
            ((raw["env"] ?: raw["environment"]) as? JsonObject).orEmpty().forEach { (name, value) ->
                val text = (value as? JsonPrimitive)?.contentOrNull
                if (secretHeader(name) || text == null) credentialEnv.add(name) else environment[name] = text
            }
            (raw["credentialEnv"] as? JsonArray).orEmpty().forEach { credentialEnv.add(it.jsonPrimitive.content) }
            credentialEnv.forEach { environment.remove(it) }
            McpServerConfiguration(id, options.text("name").orEmpty().ifBlank { id }, transport,
                raw.text("url") ?: if (transport == "stdio") "" else throw StError.Validation("MCP URL missing"), options.flag("enable", true), publicHeaders,
                credentialHeaders.toList(), (options["tools"] as? JsonArray).orEmpty().map { item ->
                    val tool = item.jsonObject
                    McpToolSelection(tool.text("name") ?: throw StError.Validation("MCP selected tool name missing"),
                        tool.flag("enable", true), tool.flag("needsApproval", false))
                }, raw.text("command"), (raw["args"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content },
                environment, credentialEnv.toList(), raw.text("sandboxId"), oauthOptions)
        }
        if (!servers.map { it.id }.toSet().containsAll(selectedIds) || servers.map { it.id }.distinct().size != servers.size)
            throw StError.NotFound("selected MCP server configuration missing or duplicated")
        if (selectedIds.size > 16) throw StError.Validation("at most 16 MCP servers may be selected per assistant")
        return servers
    }

    suspend fun servers(assistantId: String) = gate.write { selected(configuration(assistantId)) }
    suspend fun view(assistantId: String): McpConfiguration = gate.write {
        val config = configuration(assistantId)
        val ids = (Jsonx.decode<JsonObject>(config.settingsJson)["mcpServers"] as? JsonArray).orEmpty()
            .map { it.jsonPrimitive.content }.toSet()
        McpConfiguration(config.id, config.updatedAt, selected(config, includeUnselected = true), ids)
    }
    suspend fun configure(assistantId: String, servers: List<McpServerConfiguration>, selectedIds: Set<String>, expectedUpdatedAt: String) = gate.write { content.transaction {
        val current = configuration(assistantId)
        if (current.updatedAt != expectedUpdatedAt) throw StError.Conflict("assistant configuration changed")
        if (servers.map { it.id }.distinct().size != servers.size || servers.any { it.id.isBlank() } ||
            selectedIds.any { id -> servers.none { it.id == id } }) throw StError.Validation("invalid MCP server selection")
        if (servers.any { server -> server.headers.keys.any(::secretHeader) })
            throw StError.Validation("authentication headers must use credentialHeaders and the encrypted credential API")
        if (servers.any { server -> server.environment.keys.any(::secretHeader) })
            throw StError.Validation("secret environment variables must use credentialEnv and the encrypted credential API")
        val rows = JsonArray(servers.map { server -> buildJsonObject {
            put("id", server.id); put("type", server.transport); put("url", server.url)
            server.command?.let { put("command", it) }
            put("args", JsonArray(server.args.map(::JsonPrimitive)))
            put("env", JsonObject(server.environment.mapValues { JsonPrimitive(it.value) }))
            put("credentialEnv", JsonArray(server.credentialEnv.map(::JsonPrimitive)))
            server.sandboxId?.let { put("sandboxId", it) }
            put("commonOptions", buildJsonObject {
                put("name", server.name); put("enable", server.enabled)
                put("headers", JsonObject(server.headers.mapValues { JsonPrimitive(it.value) }))
                put("credentialHeaders", JsonArray(server.credentialHeaders.map(::JsonPrimitive)))
                server.oauth?.let { put("oauth", Jsonx.json.encodeToJsonElement(it)) }
                put("tools", JsonArray(server.tools.map { tool -> buildJsonObject {
                    put("name", tool.name); put("enable", tool.enabled); put("needsApproval", tool.needsApproval)
                } }))
            })
        } })
        val settings = JsonObject(Jsonx.decode<JsonObject>(current.settingsJson) +
            ("mcpServerConfigs" to rows) + ("mcpServers" to JsonArray(selectedIds.map(::JsonPrimitive))))
        val updated = current.copy(settingsJson = settings.toString(), updatedAt = Instant.now().toString())
        selected(updated) // Same connection parser as generation, without making a network request.
        content.saveAssistant(updated)
        updated
    } }
    suspend fun setHeader(assistantId: String, serverId: String, name: String, value: String?) = gate.write {
        val config = configuration(assistantId)
        val server = selected(config, includeUnselected = true).find { it.id == serverId } ?: throw StError.NotFound("MCP server configuration")
        if (server.oauth?.enabled == true && name.equals("Authorization", ignoreCase = true))
            throw StError.Validation("OAuth manages Authorization; use the MCP login/logout API")
        val actual = server.credentialHeaders.find { it.equals(name, ignoreCase = true) }
            ?: throw StError.Validation("declare this header in credentialHeaders before setting its credential")
        val id = credentialId(config, serverId, actual)
        if (value == null) credentials.remove(id) else credentials.putSecret(id, value)
    }
    private suspend fun headers(config: AssistantConfig, server: McpServerConfiguration): Map<String, String> =
        server.headers + server.credentialHeaders.associateWith { name ->
            credentials.getSecret(credentialId(config, server.id, name))?.takeIf { it.isNotBlank() }
                ?: throw StError.Validation("MCP credential missing for header $name")
        } + if (server.oauth?.enabled == true) mapOf("Authorization" to oauth.currentHeader(oauthBinding(config, server))) else emptyMap()

    private fun environmentId(config: AssistantConfig, serverId: String, name: String): String {
        val scope = config.sourceNamespace ?: "assistant:${config.id}"
        return "mcp-env:${scope.length}:$scope:${serverId.length}:$serverId:$name"
    }
    suspend fun setEnvironment(assistantId: String, serverId: String, name: String, value: String?) = gate.write {
        val config = configuration(assistantId)
        val server = selected(config, includeUnselected = true).find { it.id == serverId } ?: throw StError.NotFound("MCP server configuration")
        if (server.transport != "stdio" || name !in server.credentialEnv)
            throw StError.Validation("declare this stdio variable in credentialEnv before setting its credential")
        val id = environmentId(config, serverId, name)
        if (value == null) credentials.remove(id) else credentials.putSecret(id, value)
    }
    private class ConnectionValues(val headers: Map<String, String>, val environment: Map<String, String>) {
        override fun toString() = "McpConnectionValues(<private>)"
        fun same(other: ConnectionValues?) = other != null && headers == other.headers && environment == other.environment
    }
    private suspend fun resolve(config: AssistantConfig, server: McpServerConfiguration): ConnectionValues =
        if (server.transport == "stdio") ConnectionValues(emptyMap(), server.environment + server.credentialEnv.associateWith { name ->
            credentials.getSecret(environmentId(config, server.id, name)) ?: throw StError.Validation("MCP credential missing for environment $name")
        }) else ConnectionValues(headers(config, server), emptyMap())
    private suspend fun connect(config: AssistantConfig, server: McpServerConfiguration): ConnectionValues {
        // Only a new explicit connection may refresh. Existing tool rounds compare their frozen token offline.
        if (server.oauth?.enabled == true) oauth.prepareConnection(oauthBinding(config, server))
        return gate.write {
            val current = configuration(config.id)
            if (current.sourceNamespace != config.sourceNamespace || selected(current).none { it == server && it.enabled })
                throw StError.Conflict("MCP server selection or configuration changed before connection")
            resolve(current, server)
        }
    }
    private fun client(server: McpServerConfiguration, values: ConnectionValues) = McpClient(server, values.headers,
        if (server.transport == "stdio") suspend {
            sandbox.openStdio(server.sandboxId ?: throw StError.Validation("select an initialized sandbox for this MCP stdio server"),
                server.command ?: throw StError.Validation("MCP stdio command missing"), server.args, values.environment)
        } else null)

    /** Explicit host operations use one selected server, not every server's tools discovery. */
    private suspend fun <T> withServer(assistantId: String, serverId: String, action: suspend (McpClient, AssistantConfig) -> T): T {
        val (server, config) = gate.write {
            val config = configuration(assistantId)
            val server = selected(config).find { it.id == serverId && it.enabled }
                ?: throw StError.NotFound("selected and enabled MCP server")
            server to config
        }
        val values = connect(config, server)
        val client = client(server, values)
        try { return action(client, config) } finally { withContext(NonCancellable) { client.close() } }
    }

    private fun JsonObject.requiredText(name: String): String =
        (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw StError.Provider("MCP response $name missing or not text")

    private fun JsonObject.rows(name: String): List<JsonObject> =
        (get(name) as? JsonArray)?.map { it as? JsonObject ?: throw StError.Provider("invalid MCP $name entry") }
            ?: throw StError.Provider("MCP response $name missing")

    suspend fun capabilities(assistantId: String, serverId: String): McpServerCapabilities =
        withServer(assistantId, serverId) { client, _ -> client.capabilities() }

    /** One explicit collector owns this connection; no network/data lease is created by constructing the Flow. */
    fun observeNotifications(assistantId: String, serverId: String,
                             resourceUris: List<String> = emptyList()): Flow<McpNotificationEvent> = flow {
        val (server, config) = gate.write {
            val config = configuration(assistantId)
            val server = selected(config).find { it.id == serverId && it.enabled }
                ?: throw StError.NotFound("selected and enabled MCP server")
            server to config
        }
        val values = connect(config, server)
        val connection = client(server, values)
        try {
            connection.observeNotifications(resourceUris).collect { event ->
                // A long-lived external connection must not deliver events under a new source/credential binding.
                gate.write {
                    val currentConfig = configuration(assistantId)
                    val current = selected(currentConfig).find { it.id == serverId && it.enabled }
                    if (currentConfig.sourceNamespace != config.sourceNamespace || current != server ||
                        !resolve(currentConfig, server).same(values))
                        throw StError.Conflict("MCP server selection, configuration or credential changed")
                }
                emit(McpNotificationEvent(assistantId, serverId, event.requiredText("method"),
                    event.text("uri"), config.sourceNamespace))
            }
        } finally { withContext(NonCancellable) { connection.close() } }
    }

    suspend fun listResources(assistantId: String, serverId: String): List<McpResourceDescriptor> =
        withServer(assistantId, serverId) { client, _ ->
            client.listResources().map { raw -> McpResourceDescriptor(
                raw.requiredText("uri"), raw.requiredText("name"), raw.text("title"), raw.text("description"),
                raw.text("mimeType"), (raw["size"] as? JsonPrimitive)?.longOrNull, raw.toString()) }
        }

    suspend fun listResourceTemplates(assistantId: String, serverId: String): List<McpResourceTemplateDescriptor> =
        withServer(assistantId, serverId) { client, _ ->
            client.listResourceTemplates().map { raw -> McpResourceTemplateDescriptor(
                raw.requiredText("uriTemplate"), raw.requiredText("name"), raw.text("title"), raw.text("description"),
                raw.text("mimeType"), raw.toString()) }
        }

    suspend fun readResource(assistantId: String, serverId: String, uri: String): McpResourceResult =
        withServer(assistantId, serverId) { client, _ ->
            // URI identifies a server resource; never resolve it as an Android path or download URL.
            val rows = client.readResource(uri).rows("contents")
            val (extracted, media) = McpToolContent.extract(buildJsonObject {
                put("content", JsonArray(rows.map { resource -> buildJsonObject {
                    put("type", "resource"); put("resource", resource)
                } }))
            })
            val contents = extracted.rows("content").map { block ->
                val resource = block["resource"]!!.jsonObject
                val text = resource.text("text")
                val index = (resource["attachmentIndex"] as? JsonPrimitive)?.intOrNull
                if (text == null && index == null) throw StError.Provider("MCP resource has no text or blob")
                McpResourceContents(resource.requiredText("uri"), resource.text("mimeType"), text, index, resource.toString())
            }
            McpResourceResult(assistantId, serverId, uri, contents, media)
        }

    suspend fun listPrompts(assistantId: String, serverId: String): List<McpPromptDescriptor> =
        withServer(assistantId, serverId) { client, _ ->
            client.listPrompts().map { raw -> McpPromptDescriptor(
                raw.requiredText("name"), raw.text("title"), raw.text("description"),
                if (raw["arguments"] == null) emptyList() else raw.rows("arguments").map { argument ->
                    McpPromptArgument(argument.requiredText("name"), argument.text("description"), argument.flag("required", false))
                }, raw.toString()) }
        }

    suspend fun getPrompt(assistantId: String, serverId: String, name: String, arguments: Map<String, String>): McpPromptResult =
        withServer(assistantId, serverId) { client, config ->
            val result = client.getPrompt(name, arguments)
            val messages = result.rows("messages")
            // One extraction keeps attachmentIndex global across all messages in this draft.
            val (extracted, media) = McpToolContent.extract(buildJsonObject {
                put("content", JsonArray(messages.map { message ->
                    message["content"] as? JsonObject ?: throw StError.Provider("MCP prompt message content missing")
                }))
            })
            val blocks = extracted.rows("content")
            val draft = messages.mapIndexed { index, message ->
                val role = when (message.requiredText("role")) {
                    "user" -> ChatRole.user; "assistant" -> ChatRole.assistant
                    else -> throw StError.Provider("invalid MCP prompt message role")
                }
                McpPromptMessage(role, blocks[index].toString())
            }
            McpPromptResult(assistantId, serverId, name, result.text("description"), draft, media, config.sourceNamespace)
        }

    suspend fun complete(assistantId: String, serverId: String, reference: McpCompletionReference,
                         argument: String, value: String, context: Map<String, String>): McpCompletion =
        withServer(assistantId, serverId) { client, _ ->
            val result = client.complete(reference, argument, value, context)["completion"] as? JsonObject
                ?: throw StError.Provider("MCP completion missing")
            val values = (result["values"] as? JsonArray)?.map {
                (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content
                    ?: throw StError.Provider("invalid MCP completion value")
            } ?: throw StError.Provider("MCP completion values missing")
            McpCompletion(values, (result["total"] as? JsonPrimitive)?.intOrNull, result.flag("hasMore", false))
        }

    suspend fun prepare(config: AssistantConfig): AssistantMcpSession {
        val servers = gate.write { selected(config).filter { it.enabled } }
        val clients = linkedMapOf<String, McpClient>()
        val descriptors = mutableListOf<McpToolDescriptor>()
        val boundConnections = linkedMapOf<String, ConnectionValues>()
        try {
            for ((serverIndex, server) in servers.withIndex()) {
                val values = connect(config, server)
                boundConnections[server.id] = values
                val client = client(server, values)
                clients[server.id] = client
                client.initialize()
                for ((toolIndex, raw) in client.listTools().withIndex()) {
                    val name = raw.text("name") ?: throw StError.Provider("MCP tool name missing")
                    val selection = server.tools.find { it.name == name }
                    if (selection?.enabled == false) continue
                    val schema = raw["inputSchema"] as? JsonObject ?: throw StError.Provider("MCP input schema missing")
                    val original = "mcp__${server.name}__$name"
                    var alias = original.takeIf { it.length <= 64 && it.matches(Regex("[A-Za-z0-9_-]+")) && descriptors.none { d -> d.modelName == it } }
                        ?: "mcp__s${serverIndex}__t${toolIndex}"
                    while (descriptors.any { it.modelName == alias }) alias += "_"
                    descriptors += McpToolDescriptor(server.id, server.name, name, alias, raw.text("description").orEmpty(),
                        schema.toString(), selection?.needsApproval == true)
                }
            }
            return AssistantMcpSession(descriptors, clients) { serverId ->
                gate.write {
                    val currentConfig = configuration(config.id)
                    if (currentConfig.sourceNamespace != config.sourceNamespace) throw StError.Conflict("MCP assistant source changed")
                    val current = selected(currentConfig).find { it.id == serverId && it.enabled }
                    if (current != servers.find { it.id == serverId } || current == null || !resolve(currentConfig, current).same(boundConnections[serverId]))
                        throw StError.Conflict("MCP server selection, configuration or credential changed")
                }
            }
        } catch (error: Exception) {
            withContext(NonCancellable) { clients.values.forEach { it.close() } }
            throw error
        }
    }
    suspend fun listTools(assistantId: String): List<McpToolDescriptor> {
        val session = prepare(gate.write { configuration(assistantId) })
        try { return session.descriptors } finally { session.close() }
    }
}

internal class AssistantMcpSession(
    val descriptors: List<McpToolDescriptor>,
    private val clients: Map<String, McpClient>,
    private val requireCurrent: suspend (String) -> Unit,
) {
    val categories = descriptors.associate { it.modelName to ApprovalCategory.WRITE }
    val forceApprovalTools = descriptors.filter { it.needsApproval }.map { it.modelName }.toSet()
    val definitions = JsonArray(descriptors.map { tool -> buildJsonObject {
        put("type", "function"); put("function", buildJsonObject {
            put("name", tool.modelName); put("description", "MCP ${tool.serverName}/${tool.name}: ${tool.description}")
            put("parameters", Jsonx.json.parseToJsonElement(tool.inputSchemaJson))
        })
    } })
    suspend fun dispatch(name: String, arguments: JsonObject): ToolResult {
        val tool = descriptors.find { it.modelName == name } ?: throw StError.Unsupported("MCP tool was not discovered in this generation")
        requireCurrent(tool.serverId)
        val (result, media) = McpToolContent.extract(clients.getValue(tool.serverId).callTool(tool.name, arguments))
        val ok = (result["isError"] as? JsonPrimitive)?.booleanOrNull != true
        val output = buildJsonObject { put("ok", ok); put("tool", name); put("data", result) }.toString()
        if (output.toByteArray(Charsets.UTF_8).size > 1_000_000) throw StError.Validation("MCP tool result exceeds generation text budget")
        return ToolResult(output, buildJsonObject {
            put("toolName", name); put("ok", ok); put("serverId", tool.serverId); put("remoteToolName", tool.name)
        }.toString(), media = media)
    }
    suspend fun close() = withContext(NonCancellable) { coroutineScope { clients.values.map { client -> launch { client.close() } }.joinAll() } }
}
