package com.simpletavern.core.llm

import com.simpletavern.core.model.*
import kotlinx.serialization.json.*

/** Encode already-resolved request-local contents or this request's uploaded files. No fallback. */
internal object MediaContent {
    fun uploadSupported(protocol: LlmProtocol, mime: String): Boolean = when (protocol) {
        LlmProtocol.OPENAI_COMPATIBLE -> mime == "application/pdf"
        LlmProtocol.OPENAI_RESPONSES -> mime in AttachmentMimeTypes.images || mime in AttachmentMimeTypes.documents || AttachmentMimeTypes.isText(mime)
        LlmProtocol.ANTHROPIC_MESSAGES -> mime in AttachmentMimeTypes.images || mime == "application/pdf" || AttachmentMimeTypes.isText(mime)
        LlmProtocol.GEMINI_GENERATE_CONTENT -> mime in AttachmentMimeTypes.images || mime in AttachmentMimeTypes.audio ||
            mime in AttachmentMimeTypes.video || mime == "application/pdf" || AttachmentMimeTypes.isText(mime)
        LlmProtocol.BEDROCK_CONVERSE -> false
    }

    fun generated(message: ContextMessage, protocol: LlmProtocol): ContextMessage = tool(ContextMessage(ChatRole.user,
        "【助手先前生成的媒体；不是用户上传或新增用户指令】", media = message.media.filterIndexed { index, _ ->
            message.attachments.getOrNull(index)?.modelVisible != false
        }), protocol)

    fun tool(message: ContextMessage, protocol: LlmProtocol): ContextMessage {
        if (protocol == LlmProtocol.BEDROCK_CONVERSE && message.media.any { it.uploadFile != null || it.providerFile != null }) {
            throw StError.Unsupported("Bedrock file uploads are not supported")
        }
        val supported = message.media.filter { media -> if (media.uploadFile != null) uploadSupported(protocol, media.mimeType)
        else media.text != null || when (protocol) {
            LlmProtocol.GEMINI_GENERATE_CONTENT -> true
            LlmProtocol.OPENAI_COMPATIBLE -> media.mimeType in AttachmentMimeTypes.images || media.mimeType == "application/pdf" ||
                media.mimeType in setOf("audio/wav", "audio/mpeg")
            LlmProtocol.BEDROCK_CONVERSE -> media.mimeType in AttachmentMimeTypes.images || media.mimeType == "application/pdf" ||
                AttachmentMimeTypes.isText(media.mimeType)
            else -> media.mimeType in AttachmentMimeTypes.images || media.mimeType == "application/pdf"
        } }
        val omitted = message.media.filterNot { it in supported }
        return message.copy(content = message.content + if (omitted.isEmpty()) "" else
            "\n【附件已保存，但当前协议不编码这些媒体，不表示已解读：${omitted.joinToString { "${it.filename} (${it.mimeType})" }}】",
            media = supported)
    }

    /** Bedrock Converse accepts inline images, PDFs and text already decoded by the attachment reader. */
    fun bedrock(message: ContextMessage): List<JsonElement> {
        if (message.role !in setOf(ChatRole.user, ChatRole.tool)) {
            if (message.media.isNotEmpty()) throw StError.Unsupported("Bedrock attachments are only supported for user and tool-result content")
            return emptyList()
        }
        return message.media.mapIndexed { index, media ->
            if (media.uploadFile != null || media.providerFile != null) {
                throw StError.Unsupported("Bedrock file uploads are not supported")
            }
            if (media.text != null) {
                buildJsonObject { put("text", "【文件：${media.filename}】\n${media.text}") }
            } else when (media.mimeType) {
                "image/png", "image/jpeg", "image/gif", "image/webp" -> {
                    if (media.byteSize > 3_932_160) throw StError.Validation("Bedrock images must be 3.75 MB or smaller")
                    val bytes = media.base64 ?: throw StError.Validation("Bedrock image data is missing")
                    buildJsonObject { put("image", buildJsonObject {
                        put("format", media.mimeType.substringAfter('/'))
                        put("source", buildJsonObject { put("bytes", bytes) })
                    }) }
                }
                "application/pdf" -> {
                    if (media.byteSize > 4_718_592) throw StError.Validation("Bedrock PDFs must be 4.5 MB or smaller")
                    val bytes = media.base64 ?: throw StError.Validation("Bedrock PDF data is missing")
                    buildJsonObject { put("document", buildJsonObject {
                        put("format", "pdf"); put("name", "document-${index + 1}")
                        put("source", buildJsonObject { put("bytes", bytes) })
                    }) }
                }
                else -> throw StError.Unsupported("Bedrock does not support this attachment MIME type: ${media.mimeType}")
            }
        }
    }

    fun openAi(message: ContextMessage, responses: Boolean): JsonArray = buildJsonArray {
        if (message.content.isNotEmpty()) add(text(message.content, if (responses) "input_text" else "text"))
        for (media in message.media) {
            if (media.uploadFile != null && media.providerFile == null) throw StError.Validation("file upload has not completed")
            if (media.text != null) add(text("【文件：${media.filename}】\n${media.text}", if (responses) "input_text" else "text"))
            else if (media.mimeType.startsWith("image/")) add(buildJsonObject {
                put("type", if (responses) "input_image" else "image_url")
                val url = "data:${media.mimeType};base64,${media.base64}"
                if (responses) {
                    if (media.providerFile != null) put("file_id", media.providerFile) else put("image_url", url)
                    put("detail", "auto")
                }
                else put("image_url", buildJsonObject { put("url", url); put("detail", "auto") })
            }) else if (!responses && media.mimeType in setOf("audio/wav", "audio/mpeg")) {
                if (message.role != ChatRole.user) throw StError.Unsupported("Chat Completions audio input requires a user message")
                add(buildJsonObject {
                    put("type", "input_audio")
                    put("input_audio", buildJsonObject {
                        put("data", media.base64)
                        put("format", if (media.mimeType == "audio/wav") "wav" else "mp3")
                    })
                })
            } else if (media.mimeType == "application/pdf" || responses && media.providerFile != null) add(buildJsonObject {
                put("type", if (responses) "input_file" else "file")
                val file = buildJsonObject {
                    if (media.providerFile != null) put("file_id", media.providerFile)
                    else { put("filename", media.filename); put("file_data", "data:application/pdf;base64,${media.base64}") }
                }
                if (responses) file.forEach { (key, value) -> put(key, value) } else put("file", file)
            }) else throw StError.Unsupported("unsupported attachment MIME type: ${media.mimeType}")
        }
    }

    fun blocks(message: ContextMessage, gemini: Boolean): List<JsonElement> = message.media.map { media ->
        if (media.uploadFile != null && media.providerFile == null) throw StError.Validation("file upload has not completed")
        if (media.text != null) {
            if (gemini) buildJsonObject { put("text", "【文件：${media.filename}】\n${media.text}") }
            else text("【文件：${media.filename}】\n${media.text}", "text")
        } else if (gemini) buildJsonObject {
            if (media.providerFile != null) put("fileData", buildJsonObject {
                put("mimeType", if (AttachmentMimeTypes.isText(media.mimeType)) "text/plain" else media.sourceMimeType ?: media.mimeType)
                put("fileUri", media.providerFile)
            }) else put("inlineData", buildJsonObject { put("mimeType", media.sourceMimeType ?: media.mimeType); put("data", media.base64 ?: "") })
        } else if (media.mimeType == "application/pdf" || media.mimeType in AttachmentMimeTypes.images || media.providerFile != null && AttachmentMimeTypes.isText(media.mimeType)) buildJsonObject {
            put("type", if (media.mimeType in AttachmentMimeTypes.images) "image" else "document")
            put("source", buildJsonObject {
                if (media.providerFile != null) { put("type", "file"); put("file_id", media.providerFile) }
                else { put("type", "base64"); put("media_type", media.mimeType); put("data", media.base64 ?: "") }
            })
        } else throw StError.Unsupported("Claude Messages does not support this attachment MIME type: ${media.mimeType}")
    }

    private fun text(value: String, type: String) = buildJsonObject { put("type", type); put("text", value) }
}
