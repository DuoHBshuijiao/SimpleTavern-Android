package com.simpletavern.core.data

import com.simpletavern.core.model.StError
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Hostile archive input: bound allocation before decoding, reject duplicate keys and excessive depth. */
object NativeJsonInput {
    private val strict = Json { isLenient = false }

    suspend fun read(input: InputStream, limit: Int): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(65_536)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (count > limit - output.size()) throw StError.Validation("native JSON exceeds byte budget")
            output.write(buffer, 0, count)
        }
        return utf8(output.toByteArray())
    }

    suspend fun lines(input: InputStream, expectedBytes: Long, maxLineBytes: Int = 32_000_000,
                      skipBlankLines: Boolean = false, consume: suspend (String) -> Unit): Long {
        val buffer = ByteArray(65_536)
        val line = ByteArrayOutputStream()
        var rows = 0L
        var bytes = 0L
        suspend fun emit() {
            val text = utf8(line.toByteArray())
            line.reset()
            if (skipBlankLines && text.isBlank()) return
            if (text.isEmpty()) throw StError.Validation("empty native JSONL row")
            consume(text); rows++
        }
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (count > expectedBytes - bytes) throw StError.Validation("native JSONL size differs from archive")
            bytes += count
            var start = 0
            for (index in 0 until count) {
                if (buffer[index] == 10.toByte()) {
                    val length = index - start
                    if (length > maxLineBytes - line.size()) throw StError.Validation("native JSONL row exceeds byte budget")
                    line.write(buffer, start, length); emit(); start = index + 1
                }
            }
            if (count - start > maxLineBytes - line.size()) throw StError.Validation("native JSONL row exceeds byte budget")
            line.write(buffer, start, count - start)
        }
        if (line.size() != 0) emit()
        if (bytes != expectedBytes) throw StError.Validation("native JSONL size differs from archive")
        return rows
    }

    fun utf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) { throw StError.Validation("invalid native UTF-8") }

    fun parse(text: String): JsonElement {
        // Lexical pass before the recursive library parser. A document cannot smuggle duplicate keys.
        data class Frame(val objectFrame: Boolean, val keys: MutableSet<String>, var wantsKey: Boolean)
        val stack = mutableListOf<Frame>()
        var index = 0
        try {
            while (index < text.length) {
                when (text[index]) {
                    '{', '[' -> {
                        if (stack.size >= 128) throw StError.Validation("native JSON nesting exceeds 128")
                        stack += Frame(text[index] == '{', mutableSetOf(), text[index] == '{')
                    }
                    '}', ']' -> {
                        if (stack.isEmpty() || stack.last().objectFrame != (text[index] == '}'))
                            throw StError.Validation("invalid native JSON nesting")
                        stack.removeAt(stack.lastIndex)
                    }
                    ',' -> if (stack.lastOrNull()?.objectFrame == true) stack.last().wantsKey = true
                    ':' -> if (stack.lastOrNull()?.objectFrame == true) stack.last().wantsKey = false
                    '"' -> {
                        val start = index++
                        var escaped = false
                        while (index < text.length) {
                            val char = text[index]
                            if (!escaped && char == '"') break
                            escaped = !escaped && char == '\\'
                            index++
                        }
                        if (index == text.length) throw StError.Validation("unterminated native JSON string")
                        val frame = stack.lastOrNull()
                        if (frame?.objectFrame == true && frame.wantsKey) {
                            val key = strict.parseToJsonElement(text.substring(start, index + 1)).jsonPrimitive.content
                            if (!frame.keys.add(key)) throw StError.Validation("duplicate native JSON key")
                        }
                    }
                }
                index++
            }
            if (stack.isNotEmpty()) throw StError.Validation("unclosed native JSON structure")
            val result = strict.parseToJsonElement(text)
            fun validateStrings(value: JsonElement) {
                when (value) {
                    is JsonObject -> value.forEach { (key, child) -> validateUnicode(key); validateStrings(child) }
                    is JsonArray -> value.forEach(::validateStrings)
                    is JsonPrimitive -> if (value.isString) validateUnicode(value.content)
                }
            }
            validateStrings(result)
            return result
        } catch (error: StError) { throw error }
        catch (_: Exception) { throw StError.Validation("invalid native JSON") }
    }

    private fun validateUnicode(text: String) {
        var index = 0
        while (index < text.length) {
            val char = text[index++]
            if (Character.isHighSurrogate(char)) {
                if (index >= text.length || !Character.isLowSurrogate(text[index++])) throw StError.Validation("unpaired native Unicode surrogate")
            } else if (Character.isLowSurrogate(char)) throw StError.Validation("unpaired native Unicode surrogate")
        }
    }
}
