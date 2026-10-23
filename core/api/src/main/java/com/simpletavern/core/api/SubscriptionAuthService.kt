package com.simpletavern.core.api

import android.os.SystemClock
import android.util.Base64
import com.simpletavern.core.data.*
import com.simpletavern.core.llm.ProviderHttpOptions
import com.simpletavern.core.model.StError
import java.net.*
import java.security.*
import java.security.spec.RSAPublicKeySpec
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.math.BigInteger
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.FormBody

@Serializable
data class SubscriptionUsageWindow(val id: String, val label: String, val windowMinutes: Int? = null,
    val usedPercent: Double? = null, val remainingPercent: Double? = null, val resetsAt: String? = null)
@Serializable
data class ProviderSubscriptionUsage(val provider: String, val state: String, val checkedAt: String,
    val plan: String? = null, val windows: List<SubscriptionUsageWindow> = emptyList(),
    val manageUrl: String? = null, val errorCode: String? = null)

/** Public SIWC flow and isolated xAI device compatibility flow. No Codex endpoints or API-key fallback. */
internal class SubscriptionAuthService(private val content: ContentRepository,
    private val credentials: CredentialStore, private val gate: DataOperationGate) {
    @Serializable
    private class Record(val provider: String, val clientId: String, val access: String, val refresh: String,
        val expires: Long, val scope: String, val subject: String? = null, val email: String? = null,
        val idToken: String? = null) {
        override fun toString() = "SubscriptionCredential(<redacted>)"
    }
    private class Attempt(var view: ProviderDeviceLogin, val client: String, val redirect: String = "",
        val state: String = "", val nonce: String = "", val verifier: String = "", val device: String = "",
        val socket: ServerSocket? = null, val subject: String? = null) {
        var job: Job? = null
        val deadline = SystemClock.elapsedRealtime() + (view.expiresAt - System.currentTimeMillis()).coerceAtLeast(0)
        var nextPoll = SystemClock.elapsedRealtime() + view.intervalSeconds * 1000L
    }
    private val lock = Mutex()
    private val http = ProviderAuthHttp()
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val attempts = linkedMapOf<String, Attempt>()

    suspend fun startBrowser(id: String): ProviderBrowserLogin = gate.write { lock.withLock {
        check(id, CHATGPT); prepare(id)
        val old = read(id, CHATGPT)
        val client = old?.clientId ?: credentials.getOAuthSecret(registration(id)) ?: "dynamic_agent_client"
        val hostKey = CredentialStore.OAUTH_PREFIX + "openai-chatgpt:host"
        val host = credentials.getOAuthSecret(hostKey) ?: ("urn:uuid:" + UUID.randomUUID()).also { credentials.putSecret(hostKey, it) }
        val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 900_000 }
        val redirect = "http://127.0.0.1:${socket.localPort}/auth/callback"
        val state = random(); val nonce = random(); val verifier = random()
        val view = ProviderDeviceLogin("chatgpt:" + UUID.randomUUID(), id, CHATGPT, "", redirect,
            System.currentTimeMillis() + 900_000, 1, 0, method = "pkce")
        val attempt = Attempt(view, client, redirect, state, nonce, verifier, socket = socket, subject = old?.subject)
        attempts[view.loginId] = attempt
        val params = linkedMapOf("client_id" to client, "ext_agent_host_id" to host,
            "response_type" to "code", "redirect_uri" to redirect, "scope" to SCOPES,
            "resource" to RESOURCE, "state" to state, "nonce" to nonce, "code_challenge_method" to "S256",
            "code_challenge" to b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))))
        if (client == "dynamic_agent_client") params["agent_name_hint"] = "SimpleTavern"
        attempt.job = jobs.launch {
            try {
                withTimeout(900_000) {
                    while (isActive && !socket.isClosed) {
                        val callback = withContext(Dispatchers.IO) {
                            socket.accept().use { peer ->
                                peer.soTimeout = 5_000
                                val line = peer.getInputStream().bufferedReader().readLine().orEmpty()
                                val target = line.split(' ').getOrNull(1).orEmpty()
                                val body = "Login received. Return to SimpleTavern."
                                peer.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: ${body.length}\r\n\r\n" + body).toByteArray())
                                if (line.startsWith("GET ") && target.length <= 16_384 && target.startsWith("/auth/callback?")) redirect + target.substringAfter("/auth/callback") else null
                            }
                        }
                        if (callback != null) {
                            try { completeBrowser(view.loginId, callback); break }
                            catch (_: StError.Validation) { /* Ignore unsolicited callbacks; retain the pending state. */ }
                        }
                    }
                }
            } catch (_: Exception) {
                lock.withLock { if (attempt.view.state == "pending") finish(attempt, "expired", "callback_timeout") }
            } finally { socket.close() }
        }
        ProviderBrowserLogin(view.loginId, id, CHATGPT,
            "$AUTH/api/accounts/authorize?" + params.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }, redirect, view.expiresAt)
    } }

    suspend fun completeBrowser(loginId: String, callback: String): ProviderDeviceLogin = gate.write { lock.withLock {
        val a = attempt(loginId)
        if (a.view.state != "pending") return@withLock a.view
        if (expired(a)) return@withLock finish(a, "expired", "expired_token")
        check(a.view.presetId, CHATGPT)
        val uri = runCatching { URI(callback) }.getOrNull()
        if (callback.length > 16_384 || uri == null || uri.scheme != "http" || uri.host != "127.0.0.1" ||
            uri.port != URI(a.redirect).port || uri.rawPath != "/auth/callback" || uri.rawFragment != null || uri.rawUserInfo != null)
            throw StError.Validation("invalid ChatGPT callback URI")
        val pairs = runCatching { uri.rawQuery.orEmpty().split('&').map { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") } }
            .getOrElse { throw StError.Validation("invalid ChatGPT callback query") }
        if (pairs.map { it.first }.distinct().size != pairs.size) throw StError.Validation("duplicate ChatGPT callback parameters")
        val values = pairs.toMap()
        if (values["state"] != a.state) throw StError.Validation("ChatGPT callback state mismatch")
        if (values["error"] != null) return@withLock finish(a, "denied", "access_denied")
        val client = if (a.client == "dynamic_agent_client") values["client_id"]?.takeIf { it.isNotBlank() && it != "dynamic_agent_client" }
            ?: throw StError.Validation("ChatGPT registration did not issue a client ID") else a.client.also {
                if (values["client_id"] != null && values["client_id"] != it) throw StError.Validation("ChatGPT client ID mismatch") }
        ProviderHttpOptions.validateSecret(client)
        // Persist the issued registration before consuming the one-use authorization code.
        credentials.putSecret(registration(a.view.presetId), client)
        val code = values["code"]?.takeIf(String::isNotBlank) ?: throw StError.Validation("ChatGPT callback has no code")
        try {
            val obj = http.request("$AUTH/api/accounts/oauth/token", form("grant_type" to "authorization_code", "client_id" to client,
                "code" to code, "code_verifier" to a.verifier, "redirect_uri" to a.redirect, "resource" to RESOURCE)).requireObject()
            val token = obj.text("id_token") ?: invalid()
            val claims = validateIdToken(token, client, a.nonce)
            val subject = claims.text("sub") ?: invalid()
            if (a.subject != null && a.subject != subject) throw StError.Validation("ChatGPT account changed; use a separate preset")
            val record = record(CHATGPT, client, obj, subject = subject, email = claims.text("email"), idToken = token)
            check(a.view.presetId, CHATGPT)
            credentials.putSecret(key(CHATGPT, a.view.presetId), Jsonx.json.encodeToString(record))
            finish(a, "completed")
        } catch (e: Exception) { finish(a, "failed", "token_exchange_failed"); throw e }
    } }

    suspend fun startDevice(id: String): ProviderDeviceLogin = gate.write { lock.withLock {
        val config = check(id, XAI); prepare(id)
        val meta = xaiMetadata()
        val client = (config["providerParams"] as? JsonObject)?.text("client_id") ?: XAI_CLIENT
        ProviderHttpOptions.validateSecret(client)
        val obj = http.request(meta.text("device_authorization_endpoint")!!, form("client_id" to client,
            "scope" to "openid profile email offline_access grok-cli:access api:access")).requireObject()
        val device = obj.text("device_code") ?: invalid()
        val verification = obj.text("verification_uri") ?: invalid()
        val target = URI(verification)
        if (target.scheme != "https" || target.host !in setOf("auth.x.ai", "accounts.x.ai", "grok.com", "x.ai")) invalid()
        val seconds = obj["expires_in"]?.jsonPrimitive?.longOrNull?.takeIf { it in 1..3600 } ?: invalid()
        val interval = obj["interval"]?.jsonPrimitive?.intOrNull?.takeIf { it in 1..300 } ?: 5
        val view = ProviderDeviceLogin("xai:" + UUID.randomUUID(), id, XAI, obj.text("user_code") ?: invalid(),
            verification, System.currentTimeMillis() + seconds * 1000, interval, System.currentTimeMillis() + interval * 1000)
        attempts[view.loginId] = Attempt(view, client, device = device)
        view
    } }
    suspend fun poll(id: String): ProviderDeviceLogin = gate.write { lock.withLock {
        val a = attempt(id)
        if (a.view.state != "pending") return@withLock a.view
        if (expired(a)) return@withLock finish(a, "expired", "expired_token")
        if (a.view.providerId == CHATGPT || SystemClock.elapsedRealtime() < a.nextPoll) return@withLock a.view
        check(a.view.presetId, XAI)
        a.nextPoll = SystemClock.elapsedRealtime() + a.view.intervalSeconds * 1000L
        a.view = a.view.copy(nextPollAt = System.currentTimeMillis() + a.view.intervalSeconds * 1000L)
        val reply = http.request(xaiMetadata().text("token_endpoint")!!, form("grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            "client_id" to a.client, "device_code" to a.device))
        when (reply.body?.text("error")) {
            "authorization_pending" -> return@withLock a.view
            "slow_down" -> { val interval = (a.view.intervalSeconds + 5).coerceAtMost(300)
                a.nextPoll = SystemClock.elapsedRealtime() + interval * 1000L
                a.view = a.view.copy(intervalSeconds = interval, nextPollAt = System.currentTimeMillis() + interval * 1000L); return@withLock a.view }
            "access_denied" -> return@withLock finish(a, "denied", "access_denied")
            "expired_token" -> return@withLock finish(a, "expired", "expired_token")
        }
        try {
            val record = record(XAI, a.client, reply.requireObject())
            check(a.view.presetId, XAI)
            credentials.putSecret(key(XAI, a.view.presetId), Jsonx.json.encodeToString(record))
            finish(a, "completed")
        } catch (e: Exception) { finish(a, "failed", "device_login_failed"); throw e }
    } }
    suspend fun cancel(id: String): ProviderDeviceLogin = lock.withLock {
        val a = attempt(id); a.socket?.close(); a.job?.cancel()
        if (a.view.state == "pending") finish(a, "cancelled") else a.view
    }
    suspend fun logout(id: String) = gate.write { lock.withLock {
        attempts.values.filter { it.view.presetId == id }.forEach { it.socket?.close(); it.job?.cancel() }
        attempts.entries.removeAll { it.value.view.presetId == id }
        credentials.remove(key(CHATGPT, id)); credentials.remove(key(XAI, id))
    } }
    suspend fun status(id: String, provider: String): ProviderOAuthStatus = gate.write { lock.withLock {
        check(id, provider); val r = read(id, provider)
        ProviderOAuthStatus(id, provider, r != null, r?.expires, r != null && r.expires <= System.currentTimeMillis() + 300_000, r?.email ?: r?.subject,
            subscriptionAuthorized = if (provider == CHATGPT) r?.scope?.split(' ')?.contains("chatgpt.tokens.use.direct") == true else null)
    } }
    suspend fun connection(id: String, provider: String, refresh: Boolean, allow: Boolean): ProviderOAuthService.Connection = gate.write { lock.withLock {
        check(id, provider)
        var r = read(id, provider) ?: throw StError.Validation("subscription login is required")
        if (allow && (refresh || r.expires <= System.currentTimeMillis() + 300_000)) {
            val endpoint = if (provider == CHATGPT) "$AUTH/api/accounts/oauth/token" else xaiMetadata().text("token_endpoint")!!
            val args = mutableListOf("grant_type" to "refresh_token", "refresh_token" to r.refresh, "client_id" to r.clientId)
            if (provider == CHATGPT) args += "resource" to RESOURCE
            val obj = http.request(endpoint, form(*args.toTypedArray())).requireObject()
            val updatedId = obj.text("id_token")
            if (updatedId != null && provider == CHATGPT) {
                val claims = validateIdToken(updatedId, r.clientId, null)
                if (claims.text("sub") != r.subject) throw StError.Validation("ChatGPT refresh changed account identity")
            }
            r = record(provider, r.clientId, obj, r, r.subject, r.email, updatedId ?: r.idToken)
            check(id, provider)
            credentials.putSecret(key(provider, id), Jsonx.json.encodeToString(r))
        }
        if (provider == CHATGPT && "chatgpt.tokens.use.direct" !in r.scope.split(' '))
            throw StError.Validation("ChatGPT plan permission was not granted; sign in again")
        ProviderOAuthService.Connection(r.access, listOf(r.refresh) + listOfNotNull(r.idToken),
            if (provider == CHATGPT) "https://api.openai.com/v1" else "https://api.x.ai/v1", emptyMap(), chatgptSubscription = provider == CHATGPT)
    } }
    suspend fun usage(id: String, provider: String): ProviderSubscriptionUsage {
        val signed = status(id, provider).loggedIn
        return ProviderSubscriptionUsage(provider, if (signed) "unsupported" else "not_logged_in", java.time.Instant.now().toString(),
            manageUrl = if (provider == CHATGPT) "https://chatgpt.com/settings/usage" else "https://grok.com/settings",
            errorCode = if (signed) "usage_endpoint_not_documented" else null)
    }
    private suspend fun validateIdToken(token: String, client: String, nonce: String?): JsonObject {
        val parts = token.split('.')
        if (parts.size != 3 || token.length > 32_768) invalid()
        val header = Jsonx.json.parseToJsonElement(String(unb64(parts[0]), Charsets.UTF_8)).jsonObject
        val algorithm = header.text("alg")
        if (algorithm !in setOf("RS256", "ES256")) throw StError.Unsupported("unsupported OpenAI ID token signature algorithm")
        val discovery = http.request("$AUTH/.well-known/openid-configuration").requireObject()
        if (discovery.text("issuer") != AUTH) invalid()
        val jwksUrl = discovery.text("jwks_uri") ?: invalid()
        val uri = URI(jwksUrl)
        if (uri.scheme != "https" || uri.host != "auth.openai.com" || uri.port !in setOf(-1,443) || uri.rawUserInfo != null) invalid()
        val keys = http.request(jwksUrl).requireObject()["keys"] as? JsonArray ?: invalid()
        val jwk = keys.mapNotNull { it as? JsonObject }.singleOrNull { it.text("kid") == header.text("kid") && it.text("kty") == (if (algorithm == "RS256") "RSA" else "EC") } ?: invalid()
        if (jwk.text("alg")?.let { it != algorithm } == true || jwk.text("use")?.let { it != "sig" } == true) invalid()
        val publicKey = if (algorithm == "RS256") KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(BigInteger(1, unb64(jwk.text("n") ?: invalid())), BigInteger(1, unb64(jwk.text("e") ?: invalid()))))
        else {
            if (jwk.text("crv") != "P-256") invalid()
            val parameters = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
            KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(BigInteger(1, unb64(jwk.text("x") ?: invalid())), BigInteger(1, unb64(jwk.text("y") ?: invalid()))), parameters))
        }
        val signature = unb64(parts[2])
        val encoded = if (algorithm == "RS256") signature else {
            if (signature.size != 64) invalid()
            val r = BigInteger(1, signature.copyOfRange(0, 32)).toByteArray()
            val s = BigInteger(1, signature.copyOfRange(32, 64)).toByteArray()
            byteArrayOf(0x30, (r.size + s.size + 4).toByte(), 0x02, r.size.toByte()) + r + byteArrayOf(0x02, s.size.toByte()) + s
        }
        val valid = Signature.getInstance(if (algorithm == "RS256") "SHA256withRSA" else "SHA256withECDSA").apply { initVerify(publicKey); update((parts[0] + "." + parts[1]).toByteArray(Charsets.US_ASCII)) }.verify(encoded)
        if (!valid) throw StError.Validation("invalid OpenAI ID token signature")
        val claims = Jsonx.json.parseToJsonElement(String(unb64(parts[1]), Charsets.UTF_8)).jsonObject
        val aud = claims["aud"]
        val audience = aud == JsonPrimitive(client) || (aud as? JsonArray)?.contains(JsonPrimitive(client)) == true
        val now = System.currentTimeMillis() / 1000
        if (claims.text("iss") != AUTH || !audience || (claims["exp"]?.jsonPrimitive?.longOrNull ?: 0) <= now ||
            (claims["iat"]?.jsonPrimitive?.longOrNull ?: Long.MAX_VALUE) > now + 60 || nonce != null && claims.text("nonce") != nonce ||
            (aud as? JsonArray)?.size?.let { it > 1 && claims.text("azp") != client } == true) throw StError.Validation("OpenAI ID token claims mismatch")
        return claims
    }
    private suspend fun xaiMetadata(): JsonObject {
        val obj = http.request("https://auth.x.ai/.well-known/openid-configuration").requireObject()
        if (obj.text("issuer") != "https://auth.x.ai") invalid()
        for (field in listOf("device_authorization_endpoint", "token_endpoint")) {
            val uri = URI(obj.text(field) ?: invalid())
            if (uri.scheme != "https" || uri.host != "auth.x.ai" || uri.port !in setOf(-1,443) || uri.rawUserInfo != null || uri.rawFragment != null) invalid()
        }
        return obj
    }
    private fun record(provider: String, client: String, obj: JsonObject, old: Record? = null,
        subject: String? = null, email: String? = null, idToken: String? = null): Record {
        val access = obj.text("access_token") ?: invalid()
        val refresh = obj.text("refresh_token") ?: old?.refresh ?: invalid()
        ProviderHttpOptions.validateSecret(access); ProviderHttpOptions.validateSecret(refresh)
        if (obj.text("token_type")?.equals("Bearer", true) != true) invalid()
        val seconds = obj["expires_in"]?.jsonPrimitive?.longOrNull?.takeIf { it in 1..31_536_000 } ?: invalid()
        val scope = obj.text("scope") ?: old?.scope ?: ""
        return Record(provider, client, access, refresh, System.currentTimeMillis() + seconds * 1000, scope, subject, email, idToken)
    }
    private suspend fun check(id: String, provider: String): JsonObject {
        key(provider, id)
        val preset = content.getPreset(id) ?: throw StError.NotFound("model preset")
        val config = NativeJsonInput.parse(preset.configJson) as? JsonObject ?: throw StError.Validation("invalid model preset")
        if (config.text("providerId") != provider) throw StError.Conflict("subscription provider changed")
        ProviderConnectionSettings.select(config, "")
        return config
    }
    private suspend fun read(id: String, provider: String): Record? {
        val raw = credentials.getOAuthSecret(key(provider, id)) ?: return null
        val r = runCatching { Jsonx.json.decodeFromString<Record>(raw) }.getOrElse { throw StError.Validation("invalid subscription credentials; sign in again") }
        if (r.provider != provider || r.clientId == "dynamic_agent_client" || r.access.isBlank() || r.refresh.isBlank()) invalid()
        ProviderHttpOptions.validateSecret(r.access); ProviderHttpOptions.validateSecret(r.refresh)
        return r
    }
    private fun prepare(id: String) {
        attempts.entries.removeAll { if (expired(it.value) || it.value.view.state != "pending") { it.value.socket?.close(); it.value.job?.cancel(); true } else false }
        if (attempts.values.any { it.view.presetId == id }) throw StError.Conflict("subscription login already pending")
        if (attempts.size >= 16) throw StError.Conflict("too many pending subscription logins")
    }
    private fun attempt(id: String) = attempts[id] ?: throw StError.NotFound("subscription login")
    private fun expired(a: Attempt) = SystemClock.elapsedRealtime() >= a.deadline
    private fun finish(a: Attempt, state: String, error: String? = null): ProviderDeviceLogin {
        a.view = a.view.copy(state = state, errorCode = error); a.socket?.close(); return a.view
    }
    companion object {
        const val CHATGPT = "openai-chatgpt"; const val XAI = "xai-oauth"
        const val NOTICE = "通过 OpenAI 官方提供的 Sign in with ChatGPT，使用你的 ChatGPT 订阅额度。本功能目前处于预览阶段，OpenAI 可能随时调整可用范围、使用限制或接入方式。"
        private const val AUTH = "https://auth.openai.com"
        private const val RESOURCE = "https://api.openai.com/v1"
        private const val SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
        // Grok CLI public-client compatibility, not a SimpleTavern-owned xAI registration.
        private const val XAI_CLIENT = "b1a00492-073a-47ea-816f-4c329264a828"
        fun key(provider: String, id: String): String {
            if (id.isBlank() || id.length > 900 || id.any(Char::isISOControl)) throw StError.Validation("invalid OAuth preset identity")
            return CredentialStore.OAUTH_PREFIX + provider + ":" + id
        }
        private fun registration(id: String) = key(CHATGPT, id) + ":registration"
        private fun random() = b64(ByteArray(32).also { SecureRandom().nextBytes(it) })
        private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        private fun unb64(value: String) = Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP)
        private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
        private fun form(vararg values: Pair<String,String>) = FormBody.Builder().apply { values.forEach { add(it.first, it.second) } }.build()
        private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)
        private fun invalid(): Nothing = throw StError.Provider("invalid subscription authentication response")
    }
}
