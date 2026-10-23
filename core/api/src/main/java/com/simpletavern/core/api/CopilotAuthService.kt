package com.simpletavern.core.api

import android.os.SystemClock
import com.simpletavern.core.data.ContentRepository
import com.simpletavern.core.data.CredentialStore
import com.simpletavern.core.data.DataOperationGate
import com.simpletavern.core.data.Jsonx
import com.simpletavern.core.data.NativeJsonInput
import com.simpletavern.core.llm.ProviderHttpOptions
import com.simpletavern.core.model.StError
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*

/** Only user code and public state cross the facade; device/access tokens stay private. */
@Serializable
data class ProviderDeviceLogin(
    val loginId: String, val presetId: String, val providerId: String = "github-copilot",
    val userCode: String, val verificationUri: String, val expiresAt: Long,
    val intervalSeconds: Int, val nextPollAt: Long, val state: String = "pending",
    val errorCode: String? = null,
    val method: String = "device_code",
)

@Serializable
data class ProviderOAuthStatus(
    val presetId: String, val providerId: String = "github-copilot", val loggedIn: Boolean,
    val expiresAt: Long? = null, val needsRefresh: Boolean = false, val accountId: String? = null,
    val subscriptionAuthorized: Boolean? = null,
)

/** Desktop-compatible GitHub.com device flow. Not a public, supported Copilot API contract. */
internal class CopilotAuthService(
    private val content: ContentRepository, private val credentials: CredentialStore,
    private val gate: DataOperationGate,
) {
    @Serializable
    private class TokenRecord(val provider: String = "github-copilot", val accessToken: String,
        val githubToken: String, val expiresAt: Long, val baseUrl: String) {
        override fun toString() = "CopilotToken(<redacted>)"
    }
    private class Login(val deviceCode: String, var view: ProviderDeviceLogin,
        val deadline: Long, var nextPoll: Long)

    internal class Connection(val token: String, val githubToken: String, val baseUrl: String) {
        override fun toString() = "CopilotConnection(<redacted>)"
    }

    private val lock = Mutex()
    private val sessions = linkedMapOf<String, Login>()
    private val http = ProviderAuthHttp()

    suspend fun start(presetId: String): ProviderDeviceLogin = gate.write { lock.withLock {
        checkPreset(presetId)
        sessions.entries.removeAll { it.value.deadline <= SystemClock.elapsedRealtime() || it.value.view.state != "pending" }
        if (sessions.values.any { it.view.presetId == presetId }) throw StError.Conflict("a device login is already pending for this preset")
        if (sessions.size >= 16) throw StError.Conflict("too many pending device logins")
        val obj = request("https://github.com/login/device/code", form("client_id" to CLIENT_ID, "scope" to "read:user"))
        val seconds = obj.number("expires_in")?.takeIf { it in 1..3600 } ?: invalidResponse()
        val interval = obj.number("interval")?.toInt()?.takeIf { it in 1..300 } ?: 5
        val uri = obj.text("verification_uri") ?: invalidResponse()
        if (uri != "https://github.com/login/device") invalidResponse()
        val code = obj.text("user_code")?.takeIf { it.matches(Regex("[A-Za-z0-9-]{1,32}")) } ?: invalidResponse()
        val device = obj.text("device_code") ?: invalidResponse()
        ProviderHttpOptions.validateSecret(device)
        val now = System.currentTimeMillis()
        val view = ProviderDeviceLogin("copilot:" + UUID.randomUUID(), presetId, userCode = code,
            verificationUri = uri, expiresAt = now + seconds * 1000, intervalSeconds = interval,
            nextPollAt = now + interval * 1000L)
        sessions[view.loginId] = Login(device, view, SystemClock.elapsedRealtime() + seconds * 1000,
            SystemClock.elapsedRealtime() + interval * 1000L)
        view
    } }

    /** One explicit poll, never an autonomous job. Early calls return state without an HTTP request. */
    suspend fun poll(loginId: String): ProviderDeviceLogin = gate.write { lock.withLock {
        val login = sessions[loginId] ?: throw StError.NotFound("device login")
        if (login.view.state != "pending") return@withLock login.view
        if (SystemClock.elapsedRealtime() >= login.deadline) return@withLock finish(login, "expired", "expired_token")
        if (SystemClock.elapsedRealtime() < login.nextPoll) return@withLock login.view
        checkPreset(login.view.presetId)
        schedule(login, login.view.intervalSeconds)
        val obj = request("https://github.com/login/oauth/access_token", form("client_id" to CLIENT_ID,
            "device_code" to login.deviceCode, "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"))
        val github = obj.text("access_token")
        if (github != null) {
            ProviderHttpOptions.validateSecret(github)
            // A consumed device code is never retried after a failed exchange.
            try {
                val record = exchange(github)
                checkPreset(login.view.presetId)
                credentials.putSecret(credentialId(login.view.presetId), Jsonx.json.encodeToString(record))
                finish(login, "completed")
            } catch (e: Exception) {
                finish(login, "failed", "copilot_exchange_failed")
                throw e
            }
        } else when (obj.text("error")) {
            "authorization_pending" -> login.view
            "slow_down" -> {
                val interval = maxOf(login.view.intervalSeconds + 5,
                    obj.number("interval")?.takeIf { it in 1..3600 }?.toInt() ?: 0)
                schedule(login, interval); login.view
            }
            "expired_token" -> finish(login, "expired", "expired_token")
            "access_denied" -> finish(login, "denied", "access_denied")
            else -> finish(login, "failed", "device_login_failed")
        }
    } }

    suspend fun cancel(loginId: String): ProviderDeviceLogin = lock.withLock {
        val login = sessions.remove(loginId) ?: throw StError.NotFound("device login")
        if (login.view.state == "pending") finish(login, "cancelled") else login.view
    }

    suspend fun status(presetId: String): ProviderOAuthStatus = gate.write { lock.withLock {
        checkPreset(presetId)
        val record = read(presetId)
        ProviderOAuthStatus(presetId, loggedIn = record != null, expiresAt = record?.expiresAt,
            needsRefresh = record != null && record.expiresAt <= System.currentTimeMillis() + REFRESH_MARGIN)
    } }

    suspend fun logout(presetId: String) = gate.write { lock.withLock {
        // Can also remove orphaned credentials after the preset was deleted.
        credentialId(presetId)
        sessions.entries.removeAll { it.value.view.presetId == presetId }
        credentials.remove(credentialId(presetId))
    } }

    suspend fun connection(presetId: String, forceRefresh: Boolean = false, allowRefresh: Boolean = true): Connection = gate.write { lock.withLock {
        checkPreset(presetId)
        var record = read(presetId) ?: throw StError.Validation("Copilot login is required for this preset")
        if (allowRefresh && (forceRefresh || record.expiresAt <= System.currentTimeMillis() + REFRESH_MARGIN)) {
            record = exchange(record.githubToken)
            checkPreset(presetId)
            credentials.putSecret(credentialId(presetId), Jsonx.json.encodeToString(record))
        }
        Connection(record.accessToken, record.githubToken, record.baseUrl)
    } }

    private suspend fun checkPreset(id: String) {
        credentialId(id)
        val preset = content.getPreset(id) ?: throw StError.NotFound("model preset")
        val config = NativeJsonInput.parse(preset.configJson) as? JsonObject ?: throw StError.Validation("model preset must be an object")
        if (!ProviderConnectionSettings.isCopilot(config)) throw StError.Unsupported("preset does not select the GitHub.com Copilot adapter")
        withContext(Dispatchers.IO) { ProviderConnectionSettings.select(config, "") }
    }

    private suspend fun read(id: String): TokenRecord? {
        val raw = credentials.getOAuthSecret(credentialId(id)) ?: return null
        val record = try {
            if (raw.toByteArray(Charsets.UTF_8).size > 24_576) throw IllegalArgumentException()
            Jsonx.json.decodeFromString<TokenRecord>(raw)
        } catch (_: Exception) { throw StError.Validation("invalid encrypted Copilot credential; sign in again") }
        if (record.provider != "github-copilot" || record.expiresAt <= 0) throw StError.Validation("invalid encrypted Copilot credential")
        ProviderHttpOptions.validateSecret(record.accessToken)
        ProviderHttpOptions.validateSecret(record.githubToken)
        if (record.accessToken.isBlank() || record.githubToken.isBlank()) throw StError.Validation("invalid encrypted Copilot credential")
        validateBase(record.baseUrl)
        return record
    }

    private suspend fun exchange(github: String): TokenRecord {
        val obj = request("https://api.github.com/copilot_internal/v2/token", null,
            HEADERS + ("Authorization" to "Bearer $github"))
        val token = obj.text("token") ?: invalidResponse()
        ProviderHttpOptions.validateSecret(token)
        val expires = obj.number("expires_at")?.takeIf { it in 1..Long.MAX_VALUE / 1000 }?.times(1000) ?: invalidResponse()
        if (expires <= System.currentTimeMillis()) invalidResponse()
        val proxy = token.split(';').firstOrNull { it.startsWith("proxy-ep=") }?.substringAfter('=')
        val base = if (proxy == null) DEFAULT_BASE else "https://" + proxy.removePrefix("https://").replaceFirst(Regex("^proxy\\."), "api.")
        validateBase(base)
        return TokenRecord(accessToken = token, githubToken = github, expiresAt = expires, baseUrl = base)
    }

    private fun schedule(login: Login, seconds: Int) {
        login.nextPoll = SystemClock.elapsedRealtime() + seconds * 1000L
        login.view = login.view.copy(intervalSeconds = seconds, nextPollAt = System.currentTimeMillis() + seconds * 1000L)
    }
    private fun finish(login: Login, state: String, code: String? = null): ProviderDeviceLogin {
        login.view = login.view.copy(state = state, errorCode = code)
        return login.view
    }

    private suspend fun request(url: String, body: RequestBody?, headers: Map<String, String> = emptyMap()): JsonObject {
        return http.request(url, body, mapOf("User-Agent" to HEADERS.getValue("User-Agent")) + headers).requireObject()
    }

    companion object {
        // Same compatibility client as Desktop; not a SimpleTavern-owned OAuth registration.
        private const val CLIENT_ID = "Iv1.b507a08c87ecfe98"
        private const val DEFAULT_BASE = "https://api.individual.githubcopilot.com"
        private const val REFRESH_MARGIN = 300_000L
        internal const val CREDENTIAL_PREFIX = CredentialStore.OAUTH_PREFIX + "github-copilot:"
        internal val HEADERS = mapOf("User-Agent" to "GitHubCopilotChat/0.35.0",
            "Editor-Version" to "vscode/1.107.0", "Editor-Plugin-Version" to "copilot-chat/0.35.0",
            "Copilot-Integration-Id" to "vscode-chat", "X-GitHub-Api-Version" to "2026-06-01")
        internal fun credentialId(presetId: String): String {
            if (presetId.isBlank() || presetId.length > 900 || presetId.any(Char::isISOControl)) throw StError.Validation("invalid OAuth preset identity")
            return CREDENTIAL_PREFIX + presetId
        }
        internal fun validateBase(base: String) {
            val uri = runCatching { URI(base) }.getOrNull()
            val host = uri?.host?.lowercase()
            if (uri?.scheme != "https" || host !in setOf("api.individual.githubcopilot.com", "api.business.githubcopilot.com", "api.enterprise.githubcopilot.com") ||
                uri.rawUserInfo != null || uri.port != -1 || uri.rawQuery != null || uri.rawFragment != null || uri.rawPath !in setOf("", "/"))
                throw StError.Validation("Copilot adapter requires an approved GitHub.com API root")
        }
        private fun form(vararg fields: Pair<String, String>): FormBody = FormBody.Builder().apply { fields.forEach { add(it.first, it.second) } }.build()
        private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)
        private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.longOrNull
        private fun invalidResponse(): Nothing = throw StError.Provider("invalid Copilot authentication response")
    }
}
