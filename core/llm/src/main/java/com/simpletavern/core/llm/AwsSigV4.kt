package com.simpletavern.core.llm

import com.simpletavern.core.model.AwsBedrockCredentials
import com.simpletavern.core.model.StError
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import okhttp3.Request

/** Signs the already-built fixed regional Bedrock request, never files or cache identities. */
internal object AwsSigV4 {
    private val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)
    private val hex = "0123456789abcdef"
    private val uriHex = "0123456789ABCDEF"

    fun bedrock(request: Request, payload: ByteArray, credentials: AwsBedrockCredentials): Request {
        val managed = listOf("authorization", "x-amz-date", "x-amz-security-token", "x-amz-content-sha256")
        if (managed.any { request.header(it) != null })
            throw StError.Validation("AWS signing headers cannot be configured manually")
        val dateTime = timestamp.format(Instant.now())
        val day = dateTime.substring(0, 8)
        val region = request.url.host.substringAfter('.').substringBefore(".amazonaws.")
        val scope = "$day/$region/bedrock/aws4_request"
        val payloadHash = digest(payload)
        val builder = request.newBuilder()
            .header("Host", request.url.host)
            .header("Content-Type", request.body?.contentType()?.toString() ?: "application/json")
            .header("X-Amz-Date", dateTime)
            .header("X-Amz-Content-Sha256", payloadHash)
        credentials.sessionToken?.let { builder.header("X-Amz-Security-Token", it) }
        val prepared = builder.build()
        val headers = sortedMapOf<String, String>()
        prepared.headers.names().forEach { name ->
            val lower = name.lowercase(Locale.ROOT)
            if (lower in setOf("host", "content-type") || lower.startsWith("x-amz-") || lower.startsWith("x-amzn-bedrock-"))
                headers[lower] = prepared.headers.values(name).joinToString(",") { it.trim().replace(Regex(" +"), " ") }
        }
        val signedHeaders = headers.keys.joinToString(";")
        val canonicalHeaders = headers.entries.joinToString("") { "${it.key}:${it.value}\n" }
        val query = (0 until prepared.url.querySize).map { index ->
            encodeUri(prepared.url.queryParameterName(index), false) to
                encodeUri(prepared.url.queryParameterValue(index).orEmpty(), false)
        }.sortedWith(compareBy<Pair<String, String>>({ it.first }, { it.second }))
            .joinToString("&") { (name, value) -> "$name=$value" }
        // AWS canonicalization re-encodes the raw encoded path; query names/values are encoded separately.
        val canonical = "${prepared.method}\n${encodeUri(prepared.url.encodedPath, true)}\n$query\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
        val toSign = "AWS4-HMAC-SHA256\n$dateTime\n$scope\n${digest(canonical.toByteArray(Charsets.UTF_8))}"
        val signingKey = hmac(hmac(hmac(hmac(("AWS4" + credentials.secretAccessKey).toByteArray(Charsets.UTF_8), day), region), "bedrock"), "aws4_request")
        val signature = lowerHex(hmac(signingKey, toSign))
        return prepared.newBuilder().header("Authorization",
            "AWS4-HMAC-SHA256 Credential=${credentials.accessKeyId}/$scope, SignedHeaders=$signedHeaders, Signature=$signature").build()
    }

    private fun encodeUri(text: String, preserveSlash: Boolean) = buildString {
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val value = byte.toInt().and(255)
            if (value in 65..90 || value in 97..122 || value in 48..57 || value in setOf(45, 46, 95, 126) || preserveSlash && value == 47) append(value.toChar())
            else { append('%'); append(uriHex[value ushr 4]); append(uriHex[value and 15]) }
        }
    }
    private fun digest(bytes: ByteArray) = lowerHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun lowerHex(bytes: ByteArray) = buildString(bytes.size * 2) {
        for (byte in bytes) { val value = byte.toInt().and(255); append(hex[value ushr 4]); append(hex[value and 15]) }
    }
    private fun hmac(key: ByteArray, value: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(value.toByteArray(Charsets.UTF_8))
    }
}
