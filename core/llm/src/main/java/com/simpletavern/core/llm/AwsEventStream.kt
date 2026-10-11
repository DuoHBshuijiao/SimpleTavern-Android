package com.simpletavern.core.llm

import com.simpletavern.core.model.StError
import java.nio.ByteBuffer
import java.util.zip.CRC32
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.BufferedSource

/** AWS's binary transport envelope, shared by InvokeModel and Converse streams. */
internal object AwsEventStream {
    class Event(val headers: Map<String, String>, val payload: ByteArray) {
        override fun toString() = "AwsEvent(<private>)"
    }
    suspend fun read(source: BufferedSource, consume: suspend (Event) -> Boolean) {
        while (!source.exhausted()) {
            currentCoroutineContext().ensureActive()
            val prelude = source.readByteArray(12)
            val size = ByteBuffer.wrap(prelude).int
            val headersSize = ByteBuffer.wrap(prelude, 4, 4).int
            if (size !in 16..33_554_432 || headersSize < 0 || headersSize > size - 16) invalid()
            // These CRCs are mandatory AWS wire framing, not a file/content hash or cache key.
            if (crc(prelude, 0, 8) != ByteBuffer.wrap(prelude, 8, 4).int.toLong().and(0xffffffffL)) invalid()
            val rest = source.readByteArray((size - 12).toLong())
            val checksum = CRC32().apply { update(prelude); update(rest, 0, rest.size - 4) }.value
            if (checksum != ByteBuffer.wrap(rest, rest.size - 4, 4).int.toLong().and(0xffffffffL)) invalid()
            val headers = ByteBuffer.wrap(rest, 0, headersSize).slice()
            val values = linkedMapOf<String, String>()
            val names = mutableSetOf<String>()
            while (headers.hasRemaining()) {
                val nameSize = headers.get().toInt().and(255)
                if (nameSize == 0 || headers.remaining() < nameSize + 1) invalid()
                val nameBytes = ByteArray(nameSize); headers.get(nameBytes)
                val name = nameBytes.toString(Charsets.UTF_8)
                if (!names.add(name)) invalid()
                val type = headers.get().toInt().and(255)
                when (type) {
                    0, 1 -> Unit
                    2 -> skip(headers, 1)
                    3 -> skip(headers, 2)
                    4 -> skip(headers, 4)
                    5, 8 -> skip(headers, 8)
                    9 -> skip(headers, 16)
                    6, 7 -> {
                        if (headers.remaining() < 2) invalid()
                        val length = headers.short.toInt().and(65535)
                        if (length > headers.remaining()) invalid()
                        if (type == 7) {
                            val bytes = ByteArray(length); headers.get(bytes)
                            if (values.put(name, bytes.toString(Charsets.UTF_8)) != null) invalid()
                        } else skip(headers, length)
                    }
                    else -> invalid()
                }
            }
            if (values[":message-type"] in setOf("error", "exception"))
                throw StError.Provider("Bedrock returned a stream exception")
            if (values[":message-type"] != "event" || values[":event-type"].isNullOrBlank()) invalid()
            if (!consume(Event(values, rest.copyOfRange(headersSize, rest.size - 4)))) return
        }
    }
    private fun skip(buffer: ByteBuffer, size: Int) {
        if (buffer.remaining() < size) invalid()
        buffer.position(buffer.position() + size)
    }
    private fun crc(bytes: ByteArray, start: Int, length: Int) = CRC32().apply { update(bytes, start, length) }.value
    private fun invalid(): Nothing = throw StError.Provider("invalid AWS event-stream envelope")
}
