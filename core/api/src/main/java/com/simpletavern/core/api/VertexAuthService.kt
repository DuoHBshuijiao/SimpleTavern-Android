package com.simpletavern.core.api

import com.simpletavern.core.data.ChatRepository
import com.simpletavern.core.data.ContentRepository
import com.simpletavern.core.data.CredentialStore
import com.simpletavern.core.data.DataOperationGate
import com.simpletavern.core.data.Jsonx
import com.simpletavern.core.data.NativeJsonInput
import com.simpletavern.core.llm.ProviderHttpOptions
import com.simpletavern.core.model.GoogleVertexOptions
import com.simpletavern.core.model.ModelPreset
import com.simpletavern.core.model.StError
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.FormBody

/** Explicit service-account credentials, exchanged only at Google's fixed OAuth token endpoint. */
internal class VertexAuthService(
    private val content: ContentRepository, private val credentials: CredentialStore,
    private val gate: DataOperationGate, private val chats: ChatRepository,
) {
    @Serializable
    private data class Account(
        val clientEmail: String, val privateKey: String, val privateKeyId: String? = null,
        val sourceNamespace: String? = null, val accessToken: String? = null, val expiresAt: Long? = null,
    ) {
        override fun toString() = "VertexServiceAccount(<redacted>)"
    }
    internal class Connection(val token: String, val privateKey: String, val baseUrl: String, val accountEmail: String) {
        override fun toString() = "VertexConnection(<redacted>)"
    }
    private val lock = Mutex()
    private val http = ProviderAuthHttp()

    suspend fun configure(presetId: String, options: GoogleVertexOptions, expectedUpdatedAt: String): ModelPreset =
        lock.withLock { gate.write { chats.transaction {
            val current = preset(presetId)
            if (current.updatedAt != expectedUpdatedAt) throw StError.Conflict("model preset changed before Vertex configuration")
            val config = JsonObject(config(current) + mapOf(
                "providerId" to JsonPrimitive("google-vertex"),
                "authStyle" to JsonPrimitive("google_service_account"),
                "googleVertex" to Jsonx.json.encodeToJsonElement(options),
            ))
            val connection = withContext(Dispatchers.IO) { ProviderConnectionSettings.select(config, "") }
            val result = current.copy(configJson = JsonObject(config +
                ("baseUrl" to JsonPrimitive(connection.baseUrl))).toString(), updatedAt = Instant.now().toString())
            content.savePreset(result)
            result
        } } }

    suspend fun setServiceAccount(presetId: String, serviceAccountJson: String): ProviderOAuthStatus {
        val account = withContext(Dispatchers.IO) { parseServiceAccount(serviceAccountJson) }
        return lock.withLock { gate.write {
            val current = iamPreset(presetId)
            val stored = account.copy(sourceNamespace = current.sourceNamespace)
            credentials.putSecret(credentialId(presetId), Jsonx.encode(stored))
            status(presetId, stored)
        } }
    }

    suspend fun status(presetId: String): ProviderOAuthStatus = gate.write {
        val current = iamPreset(presetId)
        status(presetId, read(presetId, current.sourceNamespace)?.second)
    }

    suspend fun logout(presetId: String) = lock.withLock { gate.write { credentials.remove(credentialId(presetId)) } }

    suspend fun connection(presetId: String, forceRefresh: Boolean = false, allowRefresh: Boolean = true): Connection {
        // Offline preparation may already own a Room transaction: never wait on the refresh mutex here.
        if (!allowRefresh) return gate.write {
            val current = iamPreset(presetId)
            val account = read(presetId, current.sourceNamespace)?.second
                ?: throw StError.Validation("configure a Google Vertex service account for this preset")
            if (forceRefresh || account.accessToken == null || (account.expiresAt ?: 0) <= System.currentTimeMillis() + REFRESH_MARGIN)
                throw StError.Validation("refresh Google Vertex authentication before preparing this connection")
            Connection(account.accessToken, account.privateKey,
                withContext(Dispatchers.IO) { ProviderConnectionSettings.select(config(current), "").baseUrl }, account.clientEmail)
        }
        return lock.withLock {
            val snapshot = gate.write {
                val current = iamPreset(presetId)
                val saved = read(presetId, current.sourceNamespace)
                    ?: throw StError.Validation("configure a Google Vertex service account for this preset")
                Triple(current, saved.first, saved.second)
            }
            var account = snapshot.third
            val expired = account.accessToken == null || (account.expiresAt ?: 0) <= System.currentTimeMillis() + REFRESH_MARGIN
            if (forceRefresh || expired) {
                // No snapshot lease is held across the network request. A restore/logout cannot be overwritten afterward.
                account = exchange(account)
                gate.write {
                    val current = iamPreset(presetId)
                    if (current.sourceNamespace != snapshot.first.sourceNamespace ||
                        current.configJson != snapshot.first.configJson ||
                        credentials.getOAuthSecret(credentialId(presetId)) != snapshot.second)
                        throw StError.Conflict("Vertex connection or credentials changed during authentication")
                    credentials.putSecret(credentialId(presetId), Jsonx.encode(account))
                }
            }
            val base = withContext(Dispatchers.IO) { ProviderConnectionSettings.select(config(snapshot.first), "").baseUrl }
            Connection(account.accessToken!!, account.privateKey, base, account.clientEmail)
        }
    }

    private fun status(id: String, account: Account?) = ProviderOAuthStatus(id, "google-vertex",
        loggedIn = account != null, expiresAt = account?.expiresAt,
        needsRefresh = account != null && (account.expiresAt ?: 0) <= System.currentTimeMillis() + REFRESH_MARGIN,
        accountId = account?.clientEmail)

    private suspend fun preset(id: String): ModelPreset {
        credentialId(id)
        return content.getPreset(id) ?: throw StError.NotFound("model preset")
    }
    private suspend fun iamPreset(id: String): ModelPreset {
        val current = preset(id)
        val config = config(current)
        if (!ProviderConnectionSettings.isVertexIam(config)) throw StError.Unsupported("preset does not select Google Vertex service-account authentication")
        withContext(Dispatchers.IO) { ProviderConnectionSettings.select(config, "") }
        return current
    }
    private fun config(preset: ModelPreset) = NativeJsonInput.parse(preset.configJson) as? JsonObject
        ?: throw StError.Validation("model preset must be an object")

    private suspend fun read(id: String, namespace: String?): Pair<String, Account>? {
        val raw = credentials.getOAuthSecret(credentialId(id)) ?: return null
        val account = Jsonx.decodeOrNull<Account>(raw)
            ?: throw StError.Validation("invalid encrypted Vertex credential; import the service account again")
        if (account.sourceNamespace != namespace) throw StError.Conflict("Vertex service account belongs to another source")
        return raw to account
    }

    private fun parseServiceAccount(raw: String): Account {
        if (raw.toByteArray(Charsets.UTF_8).size > 65_536) throw StError.Validation("service-account JSON exceeds 64 KiB")
        val obj = NativeJsonInput.parse(raw) as? JsonObject ?: throw StError.Validation("service-account JSON must be an object")
        if (obj.text("type") != "service_account") throw StError.Validation("a Google service-account key is required")
        if (obj.text("token_uri")?.let { it != TOKEN_URL } == true)
            throw StError.Validation("service account must use Google's OAuth token endpoint")
        val email = obj.text("client_email") ?: throw StError.Validation("service-account client_email is required")
        if (email.length > 320 || !email.matches(Regex("[^\\s@]+@[^\\s@]+")))
            throw StError.Validation("invalid service-account client_email")
        val key = obj.text("private_key") ?: throw StError.Validation("service-account private_key is required")
        val keyId = obj.text("private_key_id")
        if (keyId != null && (keyId.length > 256 || keyId.any(Char::isISOControl)))
            throw StError.Validation("invalid service-account key identifier")
        privateKey(key)
        return Account(email, key, keyId)
    }

    private fun privateKey(pem: String): PrivateKey = try {
        if (!pem.startsWith("-----BEGIN PRIVATE KEY-----") || !pem.trimEnd().endsWith("-----END PRIVATE KEY-----"))
            throw IllegalArgumentException()
        val encoded = pem.removePrefix("-----BEGIN PRIVATE KEY-----").trimEnd()
            .removeSuffix("-----END PRIVATE KEY-----").filterNot(Char::isWhitespace)
        KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)))
    } catch (_: Exception) { throw StError.Validation("service account requires a PKCS8 RSA private key") }

    private suspend fun exchange(account: Account): Account {
        val assertion = withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis() / 1000
            val header = buildJsonObject {
                put("alg", "RS256"); put("typ", "JWT"); account.privateKeyId?.let { put("kid", it) }
            }
            val claims = buildJsonObject {
                put("iss", account.clientEmail); put("scope", "https://www.googleapis.com/auth/cloud-platform")
                put("aud", TOKEN_URL); put("iat", now); put("exp", now + 3600)
            }
            val encoder = Base64.getUrlEncoder().withoutPadding()
            val signed = encoder.encodeToString(header.toString().toByteArray(Charsets.UTF_8)) + "." +
                encoder.encodeToString(claims.toString().toByteArray(Charsets.UTF_8))
            val signature = Signature.getInstance("SHA256withRSA").apply {
                initSign(privateKey(account.privateKey)); update(signed.toByteArray(Charsets.US_ASCII))
            }.sign()
            signed + "." + encoder.encodeToString(signature)
        }
        val reply = http.request(TOKEN_URL, FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer").add("assertion", assertion).build()).requireObject()
        val token = reply.text("access_token")?.takeIf(String::isNotBlank) ?: invalidResponse()
        ProviderHttpOptions.validateSecret(token)
        val seconds = (reply["expires_in"] as? JsonPrimitive)?.longOrNull?.takeIf { it in 1..3600 } ?: invalidResponse()
        if (!reply.text("token_type").equals("Bearer", ignoreCase = true)) invalidResponse()
        return account.copy(accessToken = token, expiresAt = System.currentTimeMillis() + seconds * 1000)
    }

    private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun invalidResponse(): Nothing = throw StError.Provider("invalid Google service-account token response")

    companion object {
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        private const val REFRESH_MARGIN = 60_000L
        internal fun credentialId(presetId: String): String {
            if (presetId.isBlank() || presetId.length > 900 || presetId.any(Char::isISOControl))
                throw StError.Validation("invalid Vertex preset identity")
            return CredentialStore.OAUTH_PREFIX + "google-vertex:" + presetId
        }
    }
}
