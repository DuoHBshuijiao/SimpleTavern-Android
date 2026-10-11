package com.simpletavern.core.api

import android.os.SystemClock
import android.util.Base64
import com.simpletavern.core.data.CredentialStore
import com.simpletavern.core.data.DataOperationGate
import com.simpletavern.core.data.Jsonx
import com.simpletavern.core.llm.ProviderHttpOptions
import com.simpletavern.core.model.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** HTTP public-client OAuth. No browser launch, automatic tool replay, or network under the data gate. */
internal class McpOAuthService(
    private val credentials: CredentialStore,
    private val gate: DataOperationGate,
    private val lookup: suspend (String, String) -> Binding,
) {
    @Serializable
    internal data class Binding(val assistantId: String, val scope: String, val serverId: String,
        val url: String, val options: McpOAuthOptions, val transport: String) {
        // Assistants of one imported source deliberately share the same server account.
        fun same(other: Binding) = scope == other.scope && serverId == other.serverId &&
            url == other.url && options == other.options && transport == other.transport
        fun key(kind: String = "mcp") = "oauth:$kind:${scope.length}:$scope:${serverId.length}:$serverId"
    }
    @Serializable
    private class TokenRecord(val binding: Binding, val issuer: String, val resource: String,
        val tokenEndpoint: String, val clientId: String, val accessToken: String,
        val refreshToken: String?, val expiresAt: Long?, val scopes: List<String>) {
        override fun toString() = "McpOAuthToken(<redacted>)"
    }
    @Serializable
    private class Registration(val binding: Binding, val issuer: String, val clientId: String) {
        override fun toString() = "McpOAuthRegistration(<private>)"
    }
    private class Login(val view: McpBrowserLogin, val binding: Binding, val verifier: String,
        val state: String, val tokenEndpoint: String, val clientId: String, val before: String?, val deadline: Long)
    private val lock = Mutex()
    private val http = ProviderAuthHttp()
    private val random = SecureRandom()
    private val sessions = linkedMapOf<String, Login>()

    suspend fun discover(assistantId: String, serverId: String): McpOAuthDiscovery {
        val binding = gate.write { lookup(assistantId, serverId) }
        val result = discover(binding)
        gate.write { requireCurrent(binding) }
        return result
    }

    private suspend fun discover(binding: Binding): McpOAuthDiscovery {
        requireEnabled(binding)
        val endpoint = resourceUrl(binding.url)
        val probe = http.request(endpoint.toString(), headers = mapOf("Accept" to "application/json, text/event-stream"), readBody = false)
        val candidates = probe.resourceMetadata?.let { listOf(httpsUrl(it).toString()) }
            ?: listOfNotNull(
                endpoint.encodedPath.takeIf { it != "/" }?.let {
                    wellKnown(endpoint, "/.well-known/oauth-protected-resource$it")
                }, wellKnown(endpoint, "/.well-known/oauth-protected-resource"))
        val (metadataUrl, metadata) = metadata(candidates)
        val resource = metadata.requiredText("resource")
        val target = resourceUrl(resource)
        val path = target.encodedPath.trimEnd('/')
        if (target.scheme != endpoint.scheme || target.host != endpoint.host || target.port != endpoint.port ||
            (endpoint.encodedPath != path && !endpoint.encodedPath.startsWith("$path/")) ||
            (target.encodedQuery != null && target.encodedQuery != endpoint.encodedQuery))
            throw StError.Provider("MCP resource metadata identifies a different resource")
        val issuers = metadata.strings("authorization_servers")
        if (issuers.isEmpty()) throw StError.Provider("MCP metadata has no authorization server")
        issuers.forEach { issuerUrl(it) }
        return McpOAuthDiscovery(binding.assistantId, binding.serverId, resource, metadataUrl, issuers,
            scopes(metadata.strings("scopes_supported")), probe.scope?.let { scopes(it.split(' ').filter(String::isNotBlank)) })
    }

    suspend fun start(assistantId: String, serverId: String, requestedScopes: List<String>?): McpBrowserLogin = lock.withLock {
        sessions.entries.removeAll { it.value.deadline <= SystemClock.elapsedRealtime() }
        val binding = gate.write { lookup(assistantId, serverId) }
        requireEnabled(binding)
        sessions.entries.removeAll { it.value.binding.scope == binding.scope && it.value.binding.serverId == serverId }
        if (sessions.size >= 32) throw StError.Conflict("too many pending MCP logins")
        val redirect = redirectUrl(binding.options.redirectUri).toString()
        val discovery = discover(binding)
        val issuer = binding.options.authorizationServer ?: discovery.authorizationServers.singleOrNull()
            ?: throw StError.Validation("choose one advertised MCP authorizationServer before login")
        if (issuer !in discovery.authorizationServers) throw StError.Validation("authorizationServer is not advertised by this MCP resource")
        val issuerUrl = issuerUrl(issuer)
        val path = issuerUrl.encodedPath.trimEnd('/')
        val candidates = listOf(
            wellKnown(issuerUrl, "/.well-known/oauth-authorization-server$path"),
            wellKnown(issuerUrl, "/.well-known/openid-configuration$path"),
            wellKnown(issuerUrl, "$path/.well-known/openid-configuration"),
        ).distinct()
        val metadata = metadata(candidates).second
        if (metadata.requiredText("issuer") != issuer) throw StError.Provider("MCP authorization metadata issuer mismatch")
        if ("S256" !in metadata.strings("code_challenge_methods_supported"))
            throw StError.Unsupported("MCP authorization server must advertise PKCE S256")
        if (metadata["response_types_supported"] != null && "code" !in metadata.strings("response_types_supported"))
            throw StError.Unsupported("MCP authorization server does not support authorization code")
        if (metadata["token_endpoint_auth_methods_supported"] != null && "none" !in metadata.strings("token_endpoint_auth_methods_supported"))
            throw StError.Unsupported("MCP OAuth currently supports public clients with token_endpoint_auth_method none")
        val authorize = httpsUrl(metadata.requiredText("authorization_endpoint"))
        val tokenEndpoint = httpsUrl(metadata.requiredText("token_endpoint")).toString()
        gate.write { requireCurrent(binding) }
        val scope = scopes(if (requestedScopes != null) discovery.challengedScopes.orEmpty() + requestedScopes
            else discovery.challengedScopes ?: binding.options.scopes.takeIf { it.isNotEmpty() } ?: discovery.scopesSupported)
        val clientId = client(binding, issuer, metadata, redirect)
        val verifier = randomValue()
        // S256 is required by PKCE; it is not a file/archive hash check.
        val challenge = encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val state = randomValue()
        val parameters = linkedMapOf("response_type" to "code", "client_id" to clientId, "redirect_uri" to redirect,
            "code_challenge" to challenge, "code_challenge_method" to "S256", "state" to state, "resource" to discovery.resource)
        if (scope.isNotEmpty()) parameters["scope"] = scope.joinToString(" ")
        if (authorize.queryParameterNames.any { it in parameters }) throw StError.Provider("MCP authorization endpoint overrides OAuth parameters")
        val authorizeUrl = authorize.newBuilder().apply { parameters.forEach { (key, value) -> addQueryParameter(key, value) } }.build().toString()
        val view = McpBrowserLogin(UUID.randomUUID().toString(), assistantId, serverId, authorizeUrl, redirect,
            issuer, discovery.resource, scope, System.currentTimeMillis() + TIMEOUT)
        val before = gate.write { requireCurrent(binding); credentials.getOAuthSecret(binding.key()) }
        sessions[view.loginId] = Login(view, binding, verifier, state, tokenEndpoint, clientId, before,
            SystemClock.elapsedRealtime() + TIMEOUT)
        view
    }

    private suspend fun client(binding: Binding, issuer: String, metadata: JsonObject, redirect: String): String {
        binding.options.clientId?.takeIf { it.isNotBlank() }?.let { return publicId(it) }
        binding.options.clientMetadataUrl?.let { url ->
            if ((metadata["client_id_metadata_document_supported"] as? JsonPrimitive)?.booleanOrNull != true)
                throw StError.Unsupported("MCP authorization server does not support Client ID Metadata Documents")
            val endpoint = httpsUrl(url)
            if (endpoint.encodedPath == "/" || endpoint.encodedQuery != null) throw StError.Validation("clientMetadataUrl must be a hosted HTTPS document path")
            val document = http.request(url).requireObject()
            if (document.requiredText("client_id") != url || document.requiredText("client_name").isBlank() ||
                redirect !in document.strings("redirect_uris") || (document.text("token_endpoint_auth_method") ?: "none") != "none")
                throw StError.Validation("hosted MCP client metadata must describe this public client and exact redirect URI")
            return publicId(url)
        }
        val key = binding.key("mcp-client")
        val before = gate.write { requireCurrent(binding); credentials.getOAuthSecret(key) }
        before?.let {
            val saved = Jsonx.decode<Registration>(it)
            if (saved.binding.same(binding) && saved.issuer == issuer) return publicId(saved.clientId)
        }
        val endpoint = metadata.text("registration_endpoint")?.takeIf { binding.options.allowDynamicRegistration }
            ?: throw StError.Validation("configure a preregistered MCP clientId or a hosted clientMetadataUrl")
        val registered = http.request(httpsUrl(endpoint).toString(), buildJsonObject {
            put("client_name", "SimpleTavern Android")
            put("redirect_uris", JsonArray(listOf(JsonPrimitive(redirect))))
            put("grant_types", JsonArray(listOf(JsonPrimitive("authorization_code"), JsonPrimitive("refresh_token"))))
            put("response_types", JsonArray(listOf(JsonPrimitive("code"))))
            put("token_endpoint_auth_method", "none")
        }.toString().toRequestBody("application/json".toMediaType())).requireObject()
        val clientId = publicId(registered.requiredText("client_id"))
        if (registered.text("token_endpoint_auth_method") != "none" || !registered.text("client_secret").isNullOrBlank())
            throw StError.Unsupported("dynamic MCP registration did not return a public client")
        if (redirect !in registered.strings("redirect_uris")) throw StError.Provider("MCP registration redirect mismatch")
        withContext(NonCancellable) { gate.write {
            requireCurrent(binding)
            if (credentials.getOAuthSecret(key) != before) throw StError.Conflict("MCP client registration changed")
            credentials.putSecret(key, Jsonx.json.encodeToString(Registration(binding, issuer, clientId)))
        } }
        return clientId
    }

    suspend fun complete(loginId: String, callbackUrl: String): McpOAuthStatus = lock.withLock {
        val login = sessions[loginId] ?: throw StError.NotFound("pending MCP browser login")
        if (login.deadline <= SystemClock.elapsedRealtime()) {
            sessions.remove(loginId); throw StError.Validation("MCP browser login expired; start again")
        }
        if (callbackUrl.length > 16_384) throw StError.Validation("MCP callback exceeds budget")
        val callback = callbackUrl.trim().toHttpUrlOrNull() ?: throw StError.Validation("paste the complete MCP callback URL")
        val redirect = redirectUrl(login.view.redirectUri)
        if (callback.fragment != null || callback.username.isNotEmpty() || callback.password.isNotEmpty() ||
            callback.newBuilder().query(null).build() != redirect) throw StError.Validation("MCP callback redirect mismatch")
        if (callback.queryParameterNames.any { callback.queryParameterValues(it).size != 1 })
            throw StError.Validation("duplicate MCP OAuth callback parameters")
        if (callback.queryParameter("state") != login.state) throw StError.Validation("MCP callback state mismatch")
        callback.queryParameter("iss")?.let { if (it != login.view.issuer) throw StError.Validation("MCP callback issuer mismatch") }
        if (callback.queryParameter("error") != null) {
            sessions.remove(loginId); throw StError.Provider("MCP authorization was denied")
        }
        val code = callback.queryParameter("code")?.takeIf(String::isNotBlank) ?: throw StError.Validation("MCP callback has no code")
        ProviderHttpOptions.validateSecret(code)
        gate.write { requireCurrent(login.binding) }
        sessions.remove(loginId) // A code is consumed once, including cancelled/failed exchanges.
        val body = FormBody.Builder().add("grant_type", "authorization_code").add("client_id", login.clientId)
            .add("redirect_uri", login.view.redirectUri).add("code", code).add("code_verifier", login.verifier)
            .add("resource", login.view.resource).build()
        val reply = http.request(login.tokenEndpoint, body).requireObject()
        val record = token(reply, login.binding, login.view.issuer, login.view.resource, login.tokenEndpoint,
            login.clientId, login.view.scopes, null)
        save(record, login.before)
        status(login.binding, record)
    }

    suspend fun cancel(loginId: String) = lock.withLock {
        if (sessions.remove(loginId) == null) throw StError.NotFound("pending MCP browser login")
        Unit
    }

    suspend fun status(assistantId: String, serverId: String): McpOAuthStatus = gate.write {
        val binding = lookup(assistantId, serverId)
        status(binding, read(binding))
    }

    suspend fun logout(assistantId: String, serverId: String) = lock.withLock { gate.write {
        val binding = lookup(assistantId, serverId)
        sessions.entries.removeAll { it.value.binding.scope == binding.scope && it.value.binding.serverId == serverId }
        credentials.remove(binding.key()); credentials.remove(binding.key("mcp-client"))
    } }

    suspend fun refresh(assistantId: String, serverId: String): McpOAuthStatus {
        val binding = gate.write { lookup(assistantId, serverId) }
        return status(binding, connection(binding, forceRefresh = true))
    }

    suspend fun prepareConnection(binding: Binding) { connection(binding) }

    /** Offline comparison of the captured generation credential; never refreshes or takes the auth mutex. */
    suspend fun currentHeader(binding: Binding): String {
        val record = read(binding) ?: throw StError.Validation("MCP OAuth login is required")
        return "Bearer ${record.accessToken}"
    }

    private suspend fun connection(binding: Binding, forceRefresh: Boolean = false): TokenRecord = lock.withLock {
        requireEnabled(binding)
        val before = gate.write { requireCurrent(binding); credentials.getOAuthSecret(binding.key()) }
        var record = decode(binding, before) ?: throw StError.Validation("MCP OAuth login is required")
        val due = record.expiresAt?.let { it <= System.currentTimeMillis() + MARGIN } == true
        if (forceRefresh || due) {
            val refresh = record.refreshToken
            if (refresh == null) {
                if (forceRefresh || record.expiresAt?.let { it <= System.currentTimeMillis() } == true)
                    throw StError.Validation("MCP token cannot be refreshed; log in again")
            } else {
                val body = FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refresh)
                    .add("client_id", record.clientId).add("resource", record.resource).build()
                val reply = http.request(record.tokenEndpoint, body)
                if (reply.status !in 200..299 && reply.body?.text("error") == "invalid_grant") {
                    // Do not keep sending a revoked/consumed refresh token on future connections.
                    gate.write {
                        requireCurrent(binding)
                        if (credentials.getOAuthSecret(binding.key()) == before) credentials.remove(binding.key())
                    }
                    throw StError.Validation("MCP OAuth grant expired; log in again")
                }
                record = token(reply.requireObject(), binding, record.issuer, record.resource, record.tokenEndpoint,
                    record.clientId, record.scopes, refresh)
                save(record, before)
            }
        }
        gate.write { requireCurrent(binding) }
        record
    }

    // After receiving tokens, finish the compare-and-set commit across cancellation; redeemed codes or rotated refresh tokens cannot safely be retried.
    private suspend fun save(record: TokenRecord, before: String?) = withContext(NonCancellable) { gate.write {
        requireCurrent(record.binding)
        if (credentials.getOAuthSecret(record.binding.key()) != before) throw StError.Conflict("MCP OAuth credential changed during authentication")
        credentials.putSecret(record.binding.key(), Jsonx.json.encodeToString(record))
    } }

    private fun token(obj: JsonObject, binding: Binding, issuer: String, resource: String, endpoint: String,
                      clientId: String, requestedScopes: List<String>, previousRefresh: String?): TokenRecord {
        if (!obj.requiredText("token_type").equals("Bearer", ignoreCase = true)) throw StError.Unsupported("MCP OAuth token is not Bearer")
        val access = secret(obj.requiredText("access_token"))
        val refresh = obj.text("refresh_token")?.let(::secret) ?: previousRefresh
        val expires = obj["expires_in"]?.let {
            val seconds = (it as? JsonPrimitive)?.longOrNull?.takeIf { value -> value > 0 }
                ?: throw StError.Provider("invalid MCP token expiry")
            try { Math.addExact(System.currentTimeMillis(), Math.multiplyExact(seconds, 1000L)) }
            catch (_: ArithmeticException) { throw StError.Provider("invalid MCP token expiry") }
        }
        val scope = obj.text("scope")?.let { scopes(it.split(' ').filter(String::isNotBlank)) } ?: requestedScopes
        return TokenRecord(binding, issuer, resource, endpoint, clientId, access, refresh, expires, scope)
    }

    private suspend fun read(binding: Binding) = decode(binding, credentials.getOAuthSecret(binding.key()))
    private fun decode(binding: Binding, raw: String?): TokenRecord? {
        if (raw == null) return null
        val record = try { Jsonx.decode<TokenRecord>(raw) }
            catch (_: Exception) { throw StError.Validation("invalid encrypted MCP OAuth credential; log in again") }
        if (!record.binding.same(binding)) return null
        issuerUrl(record.issuer); resourceUrl(record.resource); httpsUrl(record.tokenEndpoint)
        publicId(record.clientId); secret(record.accessToken); record.refreshToken?.let(::secret)
        return record
    }
    private fun status(binding: Binding, record: TokenRecord?) = McpOAuthStatus(binding.assistantId, binding.serverId,
        record != null, record?.expiresAt, record?.expiresAt?.let { it <= System.currentTimeMillis() + MARGIN } == true,
        record?.refreshToken != null, record?.issuer, record?.resource, record?.scopes.orEmpty())

    private suspend fun requireCurrent(binding: Binding) {
        if (!binding.same(lookup(binding.assistantId, binding.serverId))) throw StError.Conflict("MCP OAuth source or server configuration changed")
    }
    private fun requireEnabled(binding: Binding) {
        if (!binding.options.enabled || binding.transport == "stdio")
            throw StError.Validation("enable MCP OAuth for an HTTP server; stdio uses environment credentials")
    }
    private suspend fun metadata(candidates: List<String>): Pair<String, JsonObject> {
        for (url in candidates) {
            val reply = http.request(url)
            if (reply.status == 404 || reply.status == 405) continue
            return url to reply.requireObject()
        }
        throw StError.Provider("MCP OAuth discovery metadata not found")
    }
    private fun wellKnown(url: HttpUrl, path: String) = url.newBuilder().encodedPath(path).query(null).fragment(null).build().toString()
    private fun httpsUrl(value: String): HttpUrl {
        val url = resourceUrl(value)
        if (!url.isHttps) throw StError.Validation("MCP authorization endpoints must use HTTPS")
        return url
    }
    private fun issuerUrl(value: String): HttpUrl = httpsUrl(value).also {
        if (it.encodedQuery != null) throw StError.Validation("MCP issuer cannot contain a query")
    }
    private fun resourceUrl(value: String): HttpUrl {
        val url = value.takeIf { it.length <= 8192 }?.toHttpUrlOrNull() ?: throw StError.Validation("invalid MCP OAuth URL")
        if (url.fragment != null || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            (!url.isHttps && url.host !in LOOPBACK)) throw StError.Validation("MCP OAuth URLs require HTTPS or a local resource")
        return url
    }
    private fun redirectUrl(value: String): HttpUrl = resourceUrl(value).also {
        if (it.encodedQuery != null) throw StError.Validation("MCP OAuth redirect cannot contain a query")
    }
    private fun scopes(values: List<String>): List<String> {
        if (values.size > 128 || values.any { it.isBlank() || it.length > 256 || it.any { c -> c.code !in 33..126 || c == '"' || c == '\\' } })
            throw StError.Validation("invalid MCP OAuth scopes")
        return values.distinct()
    }
    private fun publicId(value: String): String {
        if (value.isBlank() || value.length > 8192 || value.any { it.code !in 32..126 }) throw StError.Validation("invalid MCP OAuth client ID")
        return value
    }
    private fun secret(value: String): String {
        ProviderHttpOptions.validateSecret(value)
        if (value.isBlank()) throw StError.Provider("empty MCP OAuth token")
        return value
    }
    private fun JsonObject.text(name: String) = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.requiredText(name: String) = text(name) ?: throw StError.Provider("MCP OAuth response field missing: $name")
    private fun JsonObject.strings(name: String): List<String> = when (val value = get(name)) {
        null -> emptyList()
        is JsonArray -> value.map { (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.content
            ?: throw StError.Provider("invalid MCP OAuth metadata list: $name") }
        else -> throw StError.Provider("invalid MCP OAuth metadata list: $name")
    }
    private fun randomValue() = encode(ByteArray(32).also(random::nextBytes))
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    companion object {
        private const val TIMEOUT = 10 * 60 * 1000L
        private const val MARGIN = 30_000L
        private val LOOPBACK = setOf("localhost", "127.0.0.1", "::1")
    }
}
