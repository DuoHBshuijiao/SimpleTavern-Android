package com.simpletavern.core.api

import android.os.SystemClock
import android.util.Base64
import com.simpletavern.core.data.*
import com.simpletavern.core.llm.ProviderHttpOptions
import com.simpletavern.core.model.StError
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class ProviderBrowserLogin(val loginId: String, val presetId: String,
    val providerId: String = "openai-codex", val authorizeUrl: String,
    val redirectUri: String, val expiresAt: Long)

/** Desktop Codex compatibility flow. Separate from the newer dynamic-client ChatGPT plan API. */
internal class CodexAuthService(private val content: ContentRepository,
    private val credentials: CredentialStore, private val gate: DataOperationGate) {
    @Serializable
    private class TokenRecord(val provider: String = "openai-codex", val accessToken: String,
        val refreshToken: String, val expiresAt: Long, val accountId: String) {
        override fun toString() = "CodexToken(<redacted>)"
    }
    internal class Connection(val token: String, val refreshToken: String, val accountId: String) {
        override fun toString() = "CodexConnection(<redacted>)"
    }
    private class Login(var view: ProviderDeviceLogin, val deadline: Long, var nextPoll: Long,
        val deviceAuthId: String? = null, val verifier: String? = null, val state: String? = null)
    private val lock = Mutex()
    private val http = ProviderAuthHttp()
    private val sessions = linkedMapOf<String, Login>()

    suspend fun start(presetId: String): ProviderDeviceLogin = gate.write { lock.withLock {
        checkPreset(presetId); prepareStart(presetId)
        val obj = http.request("$AUTH/api/accounts/deviceauth/usercode", jsonBody(buildJsonObject {
            put("client_id", CLIENT_ID)
        })).requireObject()
        val device = obj.text("device_auth_id") ?: invalid()
        ProviderHttpOptions.validateSecret(device)
        val code = obj.text("user_code")?.takeIf { it.matches(Regex("[A-Za-z0-9-]{1,32}")) } ?: invalid()
        val interval = (obj["interval"] as? JsonPrimitive)?.content?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it in 1.0..300.0 }?.let { kotlin.math.ceil(it).toInt() } ?: invalid()
        val view = view(presetId, code, interval)
        sessions[view.loginId] = Login(view, SystemClock.elapsedRealtime() + TIMEOUT,
            SystemClock.elapsedRealtime() + interval * 1000L, deviceAuthId = device)
        view
    } }

    suspend fun startBrowser(presetId: String): ProviderBrowserLogin = gate.write { lock.withLock {
        checkPreset(presetId); prepareStart(presetId)
        val verifier = randomValue()
        // S256 is part of PKCE, not an additional archive/file integrity mechanism.
        val challenge = encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val state = randomValue()
        val view = view(presetId, "", 0).copy(method = "pkce", verificationUri = REDIRECT, nextPollAt = 0)
        val query = mapOf("response_type" to "code", "client_id" to CLIENT_ID,
            "redirect_uri" to REDIRECT, "scope" to "openid profile email offline_access",
            "code_challenge" to challenge, "code_challenge_method" to "S256", "state" to state,
            "id_token_add_organizations" to "true", "codex_cli_simplified_flow" to "true", "originator" to "simpletavern")
            .entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        sessions[view.loginId] = Login(view, SystemClock.elapsedRealtime() + TIMEOUT, 0,
            verifier = verifier, state = state)
        ProviderBrowserLogin(view.loginId, presetId, authorizeUrl = "$AUTH/oauth/authorize?$query",
            redirectUri = REDIRECT, expiresAt = view.expiresAt)
    } }

    suspend fun poll(loginId: String): ProviderDeviceLogin = gate.write { lock.withLock {
        val login = session(loginId)
        if (login.verifier != null) throw StError.Validation("browser login requires the complete callback URL")
        if (login.view.state != "pending") return@withLock login.view
        if (expired(login)) return@withLock finish(login, "expired", "expired_token")
        if (SystemClock.elapsedRealtime() < login.nextPoll) return@withLock login.view
        checkPreset(login.view.presetId)
        schedule(login, login.view.intervalSeconds)
        val reply = http.request("$AUTH/api/accounts/deviceauth/token", jsonBody(buildJsonObject {
            put("device_auth_id", login.deviceAuthId!!); put("user_code", login.view.userCode)
        }))
        val obj = reply.body
        val error = obj?.text("error") ?: (obj?.get("error") as? JsonObject)?.text("code")
        if (error == "slow_down") { schedule(login, login.view.intervalSeconds + 5); return@withLock login.view }
        if (error in setOf("expired_token", "deviceauth_expired")) return@withLock finish(login, "expired", "expired_token")
        if (reply.status in setOf(403, 404) || error == "deviceauth_authorization_pending") return@withLock login.view
        if (reply.status !in 200..299 || obj == null) return@withLock finish(login, "failed", "device_login_failed")
        val code = obj.text("authorization_code") ?: return@withLock finish(login, "failed", "device_login_failed")
        val verifier = obj.text("code_verifier") ?: return@withLock finish(login, "failed", "device_login_failed")
        complete(login) { exchangeCode(code, verifier, "$AUTH/deviceauth/callback") }
    } }

    suspend fun completeBrowser(loginId: String, callback: String): ProviderDeviceLogin = gate.write { lock.withLock {
        val login = session(loginId)
        val verifier = login.verifier ?: throw StError.Validation("not a browser login")
        if (login.view.state != "pending") return@withLock login.view
        if (expired(login)) return@withLock finish(login, "expired", "expired_token")
        checkPreset(login.view.presetId)
        if (callback.length > 16_384) throw StError.Validation("OAuth callback exceeds budget")
        val uri = runCatching { URI(callback.trim()) }.getOrNull()
        if (uri?.scheme != "http" || uri.host != "localhost" || uri.port != 1455 || uri.rawPath != "/auth/callback" ||
            uri.rawUserInfo != null || uri.rawFragment != null) throw StError.Validation("paste the full expected OAuth callback URL")
        val params = runCatching { uri.rawQuery.orEmpty().split('&').map { part ->
            URLDecoder.decode(part.substringBefore('='), "UTF-8") to URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
        } }.getOrElse { throw StError.Validation("invalid OAuth callback query") }
        if (params.map { it.first }.distinct().size != params.size) throw StError.Validation("duplicate OAuth callback parameters")
        val values = params.toMap()
        if (values["state"] != login.state) throw StError.Validation("OAuth callback state does not match this login")
        if (values["error"] != null) return@withLock finish(login, "denied", "access_denied")
        val code = values["code"]?.takeIf(String::isNotBlank) ?: throw StError.Validation("OAuth callback has no authorization code")
        complete(login) { exchangeCode(code, verifier, REDIRECT) }
    } }

    private suspend fun complete(login: Login, exchange: suspend () -> TokenRecord): ProviderDeviceLogin {
        try {
            val record = exchange()
            checkPreset(login.view.presetId)
            credentials.putSecret(credentialId(login.view.presetId), Jsonx.json.encodeToString(record))
            return finish(login, "completed")
        } catch (e: Exception) {
            finish(login, "failed", "token_exchange_failed")
            throw e
        }
    }

    suspend fun cancel(loginId: String): ProviderDeviceLogin = lock.withLock {
        val login = sessions.remove(loginId) ?: throw StError.NotFound("device or browser login")
        if (login.view.state == "pending") finish(login, "cancelled") else login.view
    }
    suspend fun status(presetId: String): ProviderOAuthStatus = gate.write { lock.withLock {
        checkPreset(presetId)
        val record = read(presetId)
        ProviderOAuthStatus(presetId, "openai-codex", record != null, record?.expiresAt,
            record != null && record.expiresAt <= System.currentTimeMillis() + MARGIN, record?.accountId)
    } }
    suspend fun logout(presetId: String) = gate.write { lock.withLock {
        credentialId(presetId)
        sessions.entries.removeAll { it.value.view.presetId == presetId }
        credentials.remove(credentialId(presetId))
    } }
    suspend fun connection(presetId: String, forceRefresh: Boolean = false, allowRefresh: Boolean = true): Connection = gate.write { lock.withLock {
        checkPreset(presetId)
        var record = read(presetId) ?: throw StError.Validation("Codex login is required for this preset")
        if (allowRefresh && (forceRefresh || record.expiresAt <= System.currentTimeMillis() + MARGIN)) {
            record = tokenRecord(http.request("$AUTH/oauth/token", form("grant_type" to "refresh_token",
                "refresh_token" to record.refreshToken, "client_id" to CLIENT_ID)).requireObject())
            checkPreset(presetId)
            credentials.putSecret(credentialId(presetId), Jsonx.json.encodeToString(record))
        }
        Connection(record.accessToken, record.refreshToken, record.accountId)
    } }

    private suspend fun checkPreset(id: String) {
        credentialId(id)
        val preset = content.getPreset(id) ?: throw StError.NotFound("model preset")
        val config = NativeJsonInput.parse(preset.configJson) as? JsonObject ?: throw StError.Validation("model preset must be an object")
        if (!ProviderConnectionSettings.isCodex(config)) throw StError.Unsupported("preset does not select the Codex compatibility adapter")
        withContext(Dispatchers.IO) { ProviderConnectionSettings.select(config, "") }
    }
    private suspend fun read(id: String): TokenRecord? {
        val raw = credentials.getOAuthSecret(credentialId(id)) ?: return null
        val record = try {
            if (raw.toByteArray(Charsets.UTF_8).size > 24_576) throw IllegalArgumentException()
            Jsonx.json.decodeFromString<TokenRecord>(raw)
        } catch (_: Exception) { throw StError.Validation("invalid encrypted Codex credential; sign in again") }
        if (record.provider != "openai-codex" || record.expiresAt <= 0 || record.accountId != accountId(record.accessToken))
            throw StError.Validation("invalid encrypted Codex credential")
        ProviderHttpOptions.validateSecret(record.accessToken); ProviderHttpOptions.validateSecret(record.refreshToken)
        if (record.accessToken.isBlank() || record.refreshToken.isBlank()) throw StError.Validation("invalid encrypted Codex credential")
        return record
    }
    private suspend fun exchangeCode(code: String, verifier: String, redirect: String): TokenRecord {
        ProviderHttpOptions.validateSecret(code); ProviderHttpOptions.validateSecret(verifier)
        return tokenRecord(http.request("$AUTH/oauth/token", form("grant_type" to "authorization_code",
            "client_id" to CLIENT_ID, "code" to code, "code_verifier" to verifier, "redirect_uri" to redirect)).requireObject())
    }
    private fun tokenRecord(obj: JsonObject): TokenRecord {
        val token = obj.text("access_token") ?: invalid()
        val refresh = obj.text("refresh_token") ?: invalid()
        ProviderHttpOptions.validateSecret(token); ProviderHttpOptions.validateSecret(refresh)
        val seconds = (obj["expires_in"] as? JsonPrimitive)?.longOrNull?.takeIf { it in 1..31_536_000 } ?: invalid()
        return TokenRecord(accessToken = token, refreshToken = refresh,
            expiresAt = System.currentTimeMillis() + seconds * 1000, accountId = accountId(token))
    }
    /** Routing claim only, not JWT signature validation or proof of user identity/entitlement. */
    private fun accountId(token: String): String {
        if (token.length > 8192) invalid()
        val account = runCatching {
            val parts = token.split('.')
            if (parts.size != 3) invalid()
            val bytes = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP)
            val obj = NativeJsonInput.parse(NativeJsonInput.utf8(bytes)) as? JsonObject
            (obj?.get("https://api.openai.com/auth") as? JsonObject)?.text("chatgpt_account_id")
        }.getOrNull()
        return account?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,256}")) } ?: invalid()
    }
    private fun prepareStart(id: String) {
        sessions.entries.removeAll { expired(it.value) || it.value.view.state != "pending" }
        if (sessions.values.any { it.view.presetId == id }) throw StError.Conflict("a Codex login is already pending for this preset")
        if (sessions.size >= 16) throw StError.Conflict("too many pending Codex logins")
    }
    private fun view(id: String, code: String, interval: Int) = ProviderDeviceLogin("codex:" + UUID.randomUUID(), id,
        "openai-codex", code, "$AUTH/codex/device", System.currentTimeMillis() + TIMEOUT,
        interval, System.currentTimeMillis() + interval * 1000L)
    private fun session(id: String) = sessions[id] ?: throw StError.NotFound("device or browser login")
    private fun expired(login: Login) = SystemClock.elapsedRealtime() >= login.deadline
    private fun finish(login: Login, state: String, code: String? = null): ProviderDeviceLogin {
        login.view = login.view.copy(state = state, errorCode = code); return login.view
    }
    private fun schedule(login: Login, seconds: Int) {
        login.nextPoll = SystemClock.elapsedRealtime() + seconds * 1000L
        login.view = login.view.copy(intervalSeconds = seconds, nextPollAt = System.currentTimeMillis() + seconds * 1000L)
    }
    companion object {
        internal const val BASE = "https://chatgpt.com/backend-api/codex"
        private const val AUTH = "https://auth.openai.com"
        private const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        private const val REDIRECT = "http://localhost:1455/auth/callback"
        private const val TIMEOUT = 900_000L
        private const val MARGIN = 300_000L
        internal fun credentialId(id: String): String {
            if (id.isBlank() || id.length > 900 || id.any(Char::isISOControl)) throw StError.Validation("invalid OAuth preset identity")
            return CredentialStore.OAUTH_PREFIX + "openai-codex:" + id
        }
        private fun randomValue(): String = encode(ByteArray(32).also { SecureRandom().nextBytes(it) })
        private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        private fun jsonBody(obj: JsonObject) = obj.toString().toRequestBody("application/json".toMediaType())
        private fun form(vararg fields: Pair<String, String>) = FormBody.Builder().apply { fields.forEach { add(it.first, it.second) } }.build()
        private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)
        private fun invalid(): Nothing = throw StError.Provider("invalid Codex authentication response")
    }
}
