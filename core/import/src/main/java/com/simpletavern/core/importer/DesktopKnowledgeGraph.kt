package com.simpletavern.core.importer

import com.simpletavern.core.data.Jsonx
import com.simpletavern.core.model.KnowledgeGraph
import com.simpletavern.core.model.StError
import kotlinx.serialization.json.*

/** Desktop sidecars are owned by their containing chat and do not carry chatId. */
internal fun decodeDesktopKnowledgeGraph(text: String, chatId: String): KnowledgeGraph {
    val source = Jsonx.json.parseToJsonElement(text) as? JsonObject
        ?: throw StError.Validation("knowledge graph must be an object")
    return Jsonx.json.decodeFromJsonElement<KnowledgeGraph>(JsonObject(source + ("chatId" to JsonPrimitive(chatId))))
}
