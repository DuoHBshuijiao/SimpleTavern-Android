package com.simpletavern.core.memory

import com.simpletavern.core.model.ChatRole
import com.simpletavern.core.model.ContextMessage
import com.simpletavern.core.model.StError
import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.loader.MemoryLoader
import java.io.StringWriter
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Request-local templates and primitive variables only; no file loader or application objects. */
internal class MessageTemplateRenderer(pattern: String, definitions: Map<String, String>) {
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    private val template = run {
        val loader = MemoryLoader()
        definitions.forEach { (name, text) -> loader.addTemplate(name, text) }
        loader.addTemplate(CURRENT, pattern)
        try {
            PebbleEngine.Builder().loader(loader).autoEscaping(false).maxRenderedSize(1_000_000).build().getTemplate(CURRENT)
        } catch (_: Exception) {
            throw StError.Validation("message template cannot be compiled")
        }
    }

    fun render(message: ContextMessage): ContextMessage {
        val createdAt = message.sourceCreatedAt ?: return message // synthetic instructions have no source message
        if (message.role == ChatRole.tool) return message
        val zone = ZoneId.systemDefault()
        val time = try {
            Instant.parse(createdAt).atZone(zone)
        } catch (_: java.time.format.DateTimeParseException) {
            try { LocalDateTime.parse(createdAt).atZone(zone) }
            catch (_: java.time.format.DateTimeParseException) { throw StError.Validation("message template timestamp is invalid") }
        }
        val writer = StringWriter()
        try {
            template.evaluate(writer, mapOf("message" to message.content, "role" to message.role.name,
                "time" to time.toLocalTime().format(timeFormatter),
                "date" to time.toLocalDate().toString()))
        } catch (_: Exception) {
            throw StError.Validation("message template cannot be evaluated")
        }
        return message.copy(content = writer.toString())
    }

    private companion object { const val CURRENT = "__current_message_template__" }
}
