package com.simpletavern.core.importer

import com.simpletavern.core.model.StError
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json

data class DecodedSillyTavernCard(
    val raw: JsonObject,
    val sourceFormat: String,
    val warnings: List<String> = emptyList(),
) {
    override fun toString(): String =
        "DecodedSillyTavernCard(sourceFormat=$sourceFormat, fields=${raw.size}, warnings=${warnings.size})"
}

object SillyTavernCardDecoder {
    const val MAX_INPUT_BYTES = 16 * 1024 * 1024
    const val MAX_JSON_BYTES = 4 * 1024 * 1024

    private val pngSignature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
    )
    private val stLikeKeys = setOf(
        "first_mes", "mes_example", "alternate_greetings", "character_book", "spec", "spec_version",
    )
    private val json = Json { isLenient = false }

    fun decode(bytes: ByteArray): DecodedSillyTavernCard {
        if (bytes.size > MAX_INPUT_BYTES) throw StError.Validation("SillyTavern input exceeds byte limit")
        return if (hasPngSignature(bytes)) decodePng(bytes) else decodeJson(bytes)
    }

    private fun decodeJson(bytes: ByteArray): DecodedSillyTavernCard {
        if (bytes.size > MAX_JSON_BYTES) throw StError.Validation("SillyTavern JSON exceeds byte limit")
        val start = if (bytes.startsWithUtf8Bom()) 3 else 0
        val jsonBytes = if (start == 0) bytes else bytes.copyOfRange(start, bytes.size)
        val raw = parseObject(jsonBytes) ?: throw StError.Validation("JSON is not a SillyTavern character card")
        if (!looksLikeCard(raw)) throw StError.Validation("JSON is not a SillyTavern character card")
        return DecodedSillyTavernCard(raw, "json")
    }

    private fun decodePng(bytes: ByteArray): DecodedSillyTavernCard {
        val ccv3 = mutableListOf<String>()
        val chara = mutableListOf<String>()
        var cursor = pngSignature.size

        while (cursor <= bytes.size - 12) {
            val length = unsignedInt(bytes, cursor)
            val dataStart = cursor.toLong() + 8L
            val crcEnd = dataStart + length + 4L
            if (crcEnd > bytes.size.toLong()) break

            val typeOffset = cursor + 4
            val isText = bytes[typeOffset] == 't'.code.toByte() &&
                bytes[typeOffset + 1] == 'E'.code.toByte() &&
                bytes[typeOffset + 2] == 'X'.code.toByte() &&
                bytes[typeOffset + 3] == 't'.code.toByte()
            if (isText) {
                val start = dataStart.toInt()
                val end = (dataStart + length).toInt()
                var separator = start
                while (separator < end && bytes[separator] != 0.toByte()) separator++
                if (separator > start && separator < end) {
                    val key = String(bytes, start, separator - start, StandardCharsets.ISO_8859_1).trim()
                    val value = String(bytes, separator + 1, end - separator - 1, StandardCharsets.ISO_8859_1)
                    when (key) {
                        "ccv3" -> ccv3 += value
                        "chara" -> chara += value
                    }
                }
            }

            val isEnd = bytes[typeOffset] == 'I'.code.toByte() &&
                bytes[typeOffset + 1] == 'E'.code.toByte() &&
                bytes[typeOffset + 2] == 'N'.code.toByte() &&
                bytes[typeOffset + 3] == 'D'.code.toByte()
            cursor = crcEnd.toInt()
            if (isEnd) break
        }

        val warnings = mutableListOf<String>()
        for (candidate in ccv3 + chara) {
            val raw = parsePngCandidate(candidate)
            if (raw != null && looksLikeCard(raw)) {
                return DecodedSillyTavernCard(raw, "png", warnings.toList())
            }
            warnings += "png_candidate_skipped"
        }
        throw StError.Validation("PNG does not contain a valid SillyTavern character card")
    }

    private fun parsePngCandidate(candidate: String): JsonObject? {
        val text = candidate.trim()
        if (text.isEmpty()) return null
        val jsonBytes = if (text.startsWith('{')) {
            if (text.length > MAX_JSON_BYTES) return null
            text.toByteArray(StandardCharsets.UTF_8)
        } else {
            val compact = StringBuilder(text.length)
            for (char in text) if (!char.isWhitespace()) compact.append(char)
            val encoded = compact.toString()
            if (encoded.isEmpty() || encoded.length > (MAX_JSON_BYTES + 2) / 3 * 4) return null
            val padded = encoded + "=".repeat((4 - encoded.length % 4) % 4)
            val decoded = try { Base64.getDecoder().decode(padded) } catch (_: IllegalArgumentException) { return null }
            decoded
        }
        if (jsonBytes.size > MAX_JSON_BYTES) return null
        return parseObject(jsonBytes)
    }

    private fun parseObject(bytes: ByteArray): JsonObject? = try {
        val text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        json.parseToJsonElement(text) as? JsonObject
    } catch (_: Exception) {
        null
    }

    private fun looksLikeCard(raw: JsonObject): Boolean {
        val data = raw["data"] as? JsonObject
        val spec = raw["spec"] as? JsonPrimitive
        if (data != null && (spec?.isString == true ||
                data.keys.any { it in stLikeKeys && it != "spec" && it != "spec_version" })) {
            return true
        }
        return raw.keys.any { it in stLikeKeys }
    }

    private fun unsignedInt(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xffL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 8) or
            (bytes[offset + 3].toLong() and 0xffL)

    private fun hasPngSignature(bytes: ByteArray): Boolean =
        bytes.size >= pngSignature.size && pngSignature.indices.all { bytes[it] == pngSignature[it] }

    private fun ByteArray.startsWithUtf8Bom(): Boolean =
        size >= 3 && this[0] == 0xef.toByte() && this[1] == 0xbb.toByte() && this[2] == 0xbf.toByte()
}
