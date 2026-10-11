package com.simpletavern.core.tools

import com.simpletavern.core.model.StError
import kotlinx.serialization.json.*

/** RFC 6570 operators and composite values. Expansion never resolves or opens a URI. */
object McpUriTemplate {
    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private const val RESERVED = ":/?#[]@!\$&'()*+,;="
    private const val HEX = "0123456789ABCDEF"
    private const val MAX_URI = 65_536
    private val variable = Regex("((?:[A-Za-z0-9_]|%[0-9A-Fa-f]{2})(?:\\.?(?:[A-Za-z0-9_]|%[0-9A-Fa-f]{2}))*)(?::([1-9][0-9]{0,3})|(\\*))?")

    fun expand(template: String, values: JsonObject): String {
        if (template.length > MAX_URI || values.toString().length > 1_000_000) throw StError.Validation("MCP URI template input exceeds budget")
        val result = StringBuilder()
        var index = 0
        while (index < template.length) {
            if (template[index] == '{') {
                val end = template.indexOf('}', index + 1)
                if (end < 0) throw StError.Validation("MCP URI template expression is not closed")
                result.add(expression(template.substring(index + 1, end), values))
                index = end + 1
            } else {
                val end = template.indexOf('{', index).let { if (it < 0) template.length else it }
                val literal = template.substring(index, end)
                if ('}' in literal || literal.any { it.code < 0x21 || it in "\"<>\\^`|" } ||
                    literal.indices.any { literal[it] == '%' && !triplet(literal, it) })
                    throw StError.Validation("invalid MCP URI template literal")
                result.add(encode(literal, reserved = true))
                index = end
            }
        }
        return result.toString()
    }

    private fun expression(expression: String, values: JsonObject): String {
        if (expression.isEmpty()) throw StError.Validation("empty MCP URI template expression")
        val operator = expression.first().takeIf { it in "+#./;?&" }
        val specs = expression.removePrefix(operator?.toString().orEmpty()).split(',')
        val reserved = operator == '+' || operator == '#'
        val named = operator in listOf(';', '?', '&')
        val separator = when (operator) { '.', '/', ';' -> operator.toString(); '?', '&' -> "&"; else -> "," }
        val first = when (operator) { '#', '.', '/', ';', '?', '&' -> operator.toString(); else -> "" }
        val result = StringBuilder()
        var emitted = false
        fun field(name: String, value: String): String = if (value.isEmpty() && operator == ';') name else "$name=$value"
        fun emit(part: String) {
            result.add(if (emitted) separator else first); result.add(part); emitted = true
        }
        for (spec in specs) {
            val match = variable.matchEntire(spec) ?: throw StError.Validation("invalid MCP URI template variable or modifier")
            val name = match.groupValues[1] // Percent-encoded varnames are exact keys, not decoded names.
            val prefixLength = match.groupValues[2].toIntOrNull()
            val explode = match.groupValues[3].isNotEmpty()
            val value = values[name]?.takeUnless { it == JsonNull } ?: continue
            val encodedName = encode(name, reserved = true)
            fun encodeValue(text: String) = encode(text, reserved)
            when (value) {
                is JsonPrimitive -> {
                    val text = prefixLength?.let { prefix(value.content, it) } ?: value.content
                    val encoded = encodeValue(text)
                    emit(if (named) field(encodedName, encoded) else encoded)
                }
                is JsonArray -> {
                    if (prefixLength != null) throw StError.Validation("URI template prefix cannot apply to a list")
                    val parts = value.filterNot { it == JsonNull }.map { encodeValue(scalar(it)) }
                    if (parts.isEmpty()) continue
                    if (explode) parts.forEach { emit(if (named) field(encodedName, it) else it) }
                    else emit(if (named) field(encodedName, parts.joinToString(",")) else parts.joinToString(","))
                }
                is JsonObject -> {
                    if (prefixLength != null) throw StError.Validation("URI template prefix cannot apply to an object")
                    val pairs = value.filterValues { it != JsonNull }.map { (key, item) -> encodeValue(key) to encodeValue(scalar(item)) }
                    if (pairs.isEmpty()) continue
                    if (explode) pairs.forEach { (key, item) ->
                        emit(if (item.isEmpty() && operator !in listOf('?', '&')) key else "$key=$item")
                    } else {
                        val encoded = pairs.flatMap { listOf(it.first, it.second) }.joinToString(",")
                        emit(if (named) field(encodedName, encoded) else encoded)
                    }
                }
            }
        }
        return result.toString()
    }

    private fun scalar(value: JsonElement): String = (value as? JsonPrimitive)?.content
        ?: throw StError.Validation("URI template list/object members must be scalar values")

    private fun encode(value: String, reserved: Boolean): String {
        val result = StringBuilder()
        var index = 0
        while (index < value.length) {
            if (reserved && triplet(value, index)) {
                result.add(value.substring(index, index + 3)); index += 3; continue
            }
            val point = value.codePointAt(index)
            if (point in 0xD800..0xDFFF) throw StError.Validation("URI template contains an invalid Unicode character")
            if (point < 128 && (point.toChar() in UNRESERVED || reserved && point.toChar() in RESERVED))
                result.add(point.toChar().toString())
            else String(Character.toChars(point)).toByteArray(Charsets.UTF_8).forEach { byte ->
                val unsigned = byte.toInt() and 255
                result.add("%${HEX[unsigned ushr 4]}${HEX[unsigned and 15]}")
            }
            index += Character.charCount(point)
        }
        return result.toString()
    }

    /** Count Unicode characters, keeping percent-encoded UTF-8 characters and triplets intact. */
    private fun prefix(value: String, count: Int): String {
        var end = 0
        var taken = 0
        while (end < value.length && taken < count) {
            if (triplet(value, end)) {
                val byte = value.substring(end + 1, end + 3).toInt(16)
                val width = when (byte) { in 0xC2..0xDF -> 2; in 0xE0..0xEF -> 3; in 0xF0..0xF4 -> 4; else -> 1 }
                val complete = (1 until width).all { offset ->
                    val at = end + offset * 3
                    triplet(value, at) && value.substring(at + 1, at + 3).toInt(16) in 0x80..0xBF
                }
                end += if (complete) width * 3 else 3
            } else end += Character.charCount(value.codePointAt(end))
            taken++
        }
        return value.substring(0, end)
    }

    private fun triplet(value: String, index: Int) = index + 2 < value.length && value[index] == '%' &&
        value[index + 1].digitToIntOrNull(16) != null && value[index + 2].digitToIntOrNull(16) != null
    private fun StringBuilder.add(value: String) {
        if (length.toLong() + value.length > MAX_URI) throw StError.Validation("expanded MCP resource URI exceeds 64 KiB")
        append(value)
    }
}
