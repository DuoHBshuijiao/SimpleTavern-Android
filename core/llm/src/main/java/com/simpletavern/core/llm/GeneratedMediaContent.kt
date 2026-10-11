package com.simpletavern.core.llm

import com.simpletavern.core.model.*
import java.util.Base64
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.*

/** Complete inline output only; URLs and partial preview images are not downloaded or committed. */
internal class GeneratedMediaContent(private val geminiPcmRate: Int = 24000, private val geminiStreaming: Boolean = false) {
    val media = mutableListOf<ToolMedia>()
    private var bytes = 0L
    private data class AudioStream(val index: Int, val buffer: ByteArrayOutputStream = ByteArrayOutputStream())
    private val streams = linkedMapOf<String, AudioStream>()

    private fun decode(encoded: String): ByteArray {
        if (encoded.length > 8_388_608) throw StError.Provider("generated media exceeds attachment budget")
        val data = try { Base64.getDecoder().decode(encoded) }
            catch (_: IllegalArgumentException) { throw StError.Provider("invalid generated media base64") }
        bytes += data.size
        if (data.isEmpty() || data.size > 6_291_456 || bytes > 12_582_912)
            throw StError.Provider("generated media exceeds file or context budget")
        return data
    }

    private fun reserve(mimeType: String, visible: Boolean): Int {
        val mime = AttachmentMimeTypes.normalize(mimeType)
        if (mime !in AttachmentMimeTypes.images && mime !in AttachmentMimeTypes.audio)
            throw StError.Unsupported("unsupported generated media MIME type: $mime")
        if (media.size >= 20) throw StError.Provider("generated media exceeds attachment budget")
        val index = media.size
        val extension = when (mime) {
            "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"; "image/gif" -> "gif"
            "audio/wav" -> "wav"; "audio/mpeg" -> "mp3"; "audio/aac" -> "aac"; "audio/flac" -> "flac"
            "audio/opus" -> "opus"; "audio/ogg" -> "ogg"; "audio/l16" -> "pcm"; else -> "bin"
        }
        media += ToolMedia("generated-${index + 1}.$extension", mimeType, ByteArray(0), visible)
        return index
    }

    private fun add(encoded: String, mimeType: String, visible: Boolean = true): Int {
        val data = decode(encoded)
        val index = reserve(mimeType, visible)
        val slot = media[index]
        media[index] = ToolMedia(slot.filename, slot.mimeType, data, slot.modelVisible)
        return index
    }

    /** Decode chunks separately: concatenating padded base64 strings corrupts the audio. */
    fun appendAudio(encoded: String, mimeType: String, streamKey: String, visible: Boolean = true): Triple<Int, Int, Int> {
        val data = decode(encoded)
        val stream = streams.getOrPut(streamKey) { AudioStream(reserve(mimeType, visible)) }
        val offset = stream.buffer.size()
        if (offset.toLong() + data.size > 6_291_456) throw StError.Provider("generated audio exceeds file budget")
        stream.buffer.write(data)
        return Triple(stream.index, offset, data.size)
    }

    fun finish(): List<ToolMedia> {
        streams.values.forEach { stream ->
            val slot = media[stream.index]
            val data = stream.buffer.toByteArray()
            if (AttachmentMimeTypes.normalize(slot.mimeType) == "audio/l16" && data.size % 2 != 0)
                throw StError.Provider("generated PCM ended with an incomplete sample")
            media[stream.index] = ToolMedia(slot.filename, slot.mimeType, data, slot.modelVisible)
        }
        return media
    }

    fun gemini(part: JsonObject): JsonObject {
        val key = if (part["inlineData"] != null) "inlineData" else "inline_data"
        val blob = part[key] as? JsonObject ?: return part
        val mime = (blob["mimeType"] ?: blob["mime_type"])?.jsonPrimitive?.content
            ?: throw StError.Provider("generated Gemini media MIME missing")
        val data = (blob["data"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw StError.Provider("generated Gemini media data missing")
        val visible = part["thought"] != JsonPrimitive(true)
        val normalized = AttachmentMimeTypes.normalize(mime)
        val mapping = if (normalized in setOf("audio/l16", "audio/alaw", "audio/mulaw") || geminiStreaming && normalized in AttachmentMimeTypes.audio) {
            val storedMime = if (normalized == "audio/l16") {
                val rate = Regex("(?:^|;)\\s*rate=(\\d+)", RegexOption.IGNORE_CASE).find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: geminiPcmRate
                "audio/l16;codec=pcm;rate=$rate;channels=1"
            } else mime
            val (index, offset, size) = appendAudio(data, storedMime, "gemini:$storedMime:$visible", visible)
            mapOf("__st_media_index" to JsonPrimitive(index), "__st_media_offset" to JsonPrimitive(offset), "__st_media_size" to JsonPrimitive(size))
        } else mapOf("__st_media_index" to JsonPrimitive(add(data, normalized, visible)))
        // Keep the part and its exact thoughtSignature, replacing bytes with a local index only.
        return JsonObject(part + (key to JsonObject((blob - "data") + mapping)))
    }

    fun responses(output: JsonArray, format: String): JsonArray = JsonArray(output.map { element ->
        val item = element.jsonObject
        if (item["type"] != JsonPrimitive("image_generation_call")) item else {
            if (item["status"] != JsonPrimitive("completed")) throw StError.Provider("image generation did not complete")
            val data = (item["result"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw StError.Provider("generated Responses image missing")
            val selected = (item["output_format"] as? JsonPrimitive)?.contentOrNull ?: format
            val mime = when (selected) { "png" -> "image/png"; "jpeg" -> "image/jpeg"; "webp" -> "image/webp"
                else -> throw StError.Unsupported("unsupported generated image format") }
            JsonObject((item - "result") + ("__st_media_index" to JsonPrimitive(add(data, mime))))
        }
    })
}
