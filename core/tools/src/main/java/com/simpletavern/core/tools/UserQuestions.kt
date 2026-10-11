package com.simpletavern.core.tools

import com.simpletavern.core.data.Jsonx
import com.simpletavern.core.model.*
import kotlinx.serialization.json.*

/** Model-facing questions and UI-facing answers share the same persisted request. */
object UserQuestions {
    fun parse(argumentsJson: String): List<UserQuestion> {
        val args = Jsonx.json.parseToJsonElement(argumentsJson) as? JsonObject
            ?: throw StError.Validation("ask_user arguments must be an object")
        val raw = args["questions"] as? JsonArray
            ?: throw StError.Validation("ask_user questions must be an array")
        if (args.keys != setOf("questions") || raw.isEmpty())
            throw StError.Validation("ask_user requires nonempty questions")
        val questions = raw.map { item ->
            val obj = item as? JsonObject ?: throw StError.Validation("question must be an object")
            if (obj.keys.any { it !in setOf("id", "question", "options", "selection_type") })
                throw StError.Validation("unknown question field")
            fun string(key: String, default: String? = null): String =
                (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: if (key !in obj && default != null) default else throw StError.Validation("question $key must be text")
            val options = if ("options" !in obj) emptyList() else
                (obj["options"] as? JsonArray)?.map { option ->
                    (option as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: throw StError.Validation("question options must contain text")
                } ?: throw StError.Validation("question options must be an array")
            UserQuestion(string("id"), string("question"), options, string("selection_type", "text"))
        }
        if (questions.map { it.id }.distinct().size != questions.size || questions.any {
                it.id.isBlank() || it.question.isBlank() || it.selectionType !in setOf("text", "single", "multi") ||
                    it.options.any(String::isBlank) || it.options.distinct().size != it.options.size
            }) throw StError.Validation("invalid question identity, text, options or selection type")
        return questions
    }

    fun output(questions: List<UserQuestion>, answers: Map<String, UserQuestionAnswer>): JsonObject {
        if (answers.keys != questions.map { it.id }.toSet()) throw StError.Validation("answer every question by its original id")
        return buildJsonObject {
            for (question in questions) {
                val answer = answers.getValue(question.id)
                val options = answer.selectedOptions
                val custom = answer.text?.takeIf { it.isNotBlank() }
                if (options.distinct().size != options.size || options.any { it !in question.options } ||
                    (options.isEmpty() && custom == null) ||
                    (question.selectionType == "text" && options.isNotEmpty()) ||
                    (question.selectionType == "single" && (options.size > 1 || options.isNotEmpty() && custom != null)))
                    throw StError.Validation("answer does not match question ${question.id}")
                put(question.id, if (question.selectionType == "multi")
                    JsonArray((options + listOfNotNull(custom)).map(::JsonPrimitive))
                    else JsonPrimitive(custom ?: options.single()))
            }
        }.also { if (it.toString().toByteArray(Charsets.UTF_8).size > 1_000_000)
            throw StError.Validation("user answers exceed tool text budget") }
    }

    val definition = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", "ask_user")
            put("description", "向用户提出澄清问题并等待实际答案。支持文字、单选和多选，所有问题均可输入自定义文字；多选可组合选项和文字。返回按问题 id 映射的答案，不自动选择默认项。")
            put("parameters", buildJsonObject {
                put("type", "object"); put("additionalProperties", false)
                put("required", JsonArray(listOf(JsonPrimitive("questions"))))
                put("properties", buildJsonObject {
                    put("questions", buildJsonObject {
                        put("type", "array"); put("minItems", 1)
                        put("items", buildJsonObject {
                            put("type", "object"); put("additionalProperties", false)
                            put("required", JsonArray(listOf(JsonPrimitive("id"), JsonPrimitive("question"))))
                            put("properties", buildJsonObject {
                                for (key in listOf("id", "question")) put(key, buildJsonObject { put("type", "string") })
                                put("options", buildJsonObject {
                                    put("type", "array"); put("items", buildJsonObject { put("type", "string") })
                                })
                                put("selection_type", buildJsonObject {
                                    put("type", "string"); put("enum", JsonArray(listOf("text", "single", "multi").map(::JsonPrimitive)))
                                })
                            })
                        })
                    })
                })
            })
        })
    }
}
