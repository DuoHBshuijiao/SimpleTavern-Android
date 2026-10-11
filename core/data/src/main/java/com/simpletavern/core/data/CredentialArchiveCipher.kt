package com.simpletavern.core.data

import android.util.Base64
import com.simpletavern.core.model.StError
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
private data class CredentialEnvelope(val version: Int = 1, val kdf: String = "PBKDF2WithHmacSHA256",
    val iterations: Int, val salt: String, val nonce: String, val ciphertext: String)

/** Password portability, not an Android Keystore file copy. No content digest/checksum feature. */
object CredentialArchiveCipher {
    private const val ITERATIONS = 600_000
    private val aad = "simpletavern-native/credentials/v1".toByteArray(Charsets.UTF_8)
    fun validatePassword(password: CharArray?) {
        if (password == null || password.size !in 12..1024) throw StError.Validation("credential archive requires a 12..1024 character password")
    }
    fun encrypt(secrets: Map<String, String>, password: CharArray): String {
        validatePassword(password)
        if (secrets.size > 20_000 || secrets.keys.any { it.isBlank() || it.length > 4096 || it.contains('\u0000') })
            throw StError.Validation("invalid credential archive identifiers")
        val plaintext = Jsonx.encode(secrets).toByteArray(Charsets.UTF_8)
        if (plaintext.size > 2_000_000) { plaintext.fill(0); throw StError.Validation("credential archive exceeds budget") }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val key = derive(password, salt, ITERATIONS)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            return Jsonx.encode(CredentialEnvelope(iterations = ITERATIONS, salt = encode(salt), nonce = encode(nonce),
                ciphertext = encode(cipher.doFinal(plaintext))))
        } finally { plaintext.fill(0); key.fill(0) }
    }
    fun decrypt(archive: String, password: CharArray): Map<String, String> {
        validatePassword(password)
        if (archive.toByteArray(Charsets.UTF_8).size > 3_000_000) throw StError.Validation("credential envelope exceeds budget")
        NativeJsonInput.parse(archive)
        val envelope = Jsonx.decode<CredentialEnvelope>(archive)
        if (envelope.version != 1 || envelope.kdf != "PBKDF2WithHmacSHA256" || envelope.iterations !in 100_000..2_000_000)
            throw StError.Unsupported("unsupported credential encryption format")
        val salt = Base64.decode(envelope.salt, Base64.NO_WRAP)
        val nonce = Base64.decode(envelope.nonce, Base64.NO_WRAP)
        if (salt.size != 16 || nonce.size != 12) throw StError.Validation("invalid credential encryption parameters")
        val key = derive(password, salt, envelope.iterations)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            val plaintext = try { cipher.doFinal(Base64.decode(envelope.ciphertext, Base64.NO_WRAP)) }
            catch (error: Exception) { throw StError.Validation("credential password or encrypted archive is invalid") }
            try {
                if (plaintext.size > 2_000_000) throw StError.Validation("credential plaintext exceeds budget")
                val values = NativeJsonInput.parse(NativeJsonInput.utf8(plaintext)) as? JsonObject
                    ?: throw StError.Validation("invalid credential map")
                if (values.size > 20_000) throw StError.Validation("too many archived credentials")
                return values.mapValues { (id, value) ->
                    if (id.isBlank() || id.length > 4096 || id.contains('\u0000') || value !is JsonPrimitive || !value.isString)
                        throw StError.Validation("invalid archived credential")
                    value.content
                }
            }
            finally { plaintext.fill(0) }
        } finally { key.fill(0) }
    }
    private fun derive(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
}
