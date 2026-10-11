package com.simpletavern.core.tools

import com.simpletavern.core.data.MessageAttachmentReader
import com.simpletavern.core.model.*
import java.util.Base64
import kotlinx.serialization.json.*

/** Extract inline bytes only. Resource URIs are descriptive data, never file/network targets. */
object McpToolContent {
    fun extract(result: JsonObject): Pair<JsonObject, List<ToolMedia>> {
        val media = mutableListOf<ToolMedia>()
        fun visible(block: JsonObject): Boolean {
            val audience = (block["annotations"] as? JsonObject)?.get("audience") as? JsonArray ?: return true
            return JsonPrimitive("assistant") in audience
        }
        fun extractBlob(block: JsonObject, key: String, modelVisible: Boolean): JsonObject {
            val encoded = (block[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw StError.Provider("MCP inline media data missing")
            val mime = AttachmentMimeTypes.normalize((block["mimeType"] as? JsonPrimitive)?.contentOrNull
                ?: "application/octet-stream")
            if (!mime.matches(Regex("[a-z0-9.+-]+/[a-z0-9.+-]+"))) throw StError.Provider("invalid MCP media MIME type")
            if (encoded.length > 8_388_608 || media.size >= 20) throw StError.Validation("MCP inline media exceeds attachment budget")
            val bytes = try { Base64.getDecoder().decode(encoded) }
                catch (_: IllegalArgumentException) { throw StError.Provider("invalid MCP media base64") }
            if (bytes.isEmpty() || bytes.size > MessageAttachmentReader.MAX_FILE_BYTES)
                throw StError.Validation("MCP inline attachment is empty or exceeds file budget")
            val index = media.size
            val extension = when (mime) {
                "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"; "image/gif" -> "gif"
                "application/pdf" -> "pdf"; "audio/wav" -> "wav"; "audio/mpeg" -> "mp3"
                "video/mp4" -> "mp4"; else -> "bin"
            }
            media += ToolMedia("mcp-${index + 1}.$extension", mime, bytes, modelVisible)
            return JsonObject((block - key) + mapOf("attachmentIndex" to JsonPrimitive(index),
                "mimeType" to JsonPrimitive(mime), "bytes" to JsonPrimitive(bytes.size)))
        }
        val content = (result["content"] as? JsonArray)?.map { entry ->
            val block = entry as? JsonObject ?: throw StError.Provider("invalid MCP content block")
            when ((block["type"] as? JsonPrimitive)?.contentOrNull) {
                "image", "audio" -> extractBlob(block, "data", visible(block))
                "resource" -> {
                    val resource = block["resource"] as? JsonObject ?: throw StError.Provider("invalid MCP embedded resource")
                    if (resource["blob"] != null) JsonObject(block + ("resource" to extractBlob(resource, "blob", visible(block) && visible(resource))))
                    else block
                }
                else -> block
            }
        } ?: emptyList()
        return JsonObject(result + ("content" to JsonArray(content))) to media
    }
}
