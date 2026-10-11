package com.simpletavern.core.api

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.simpletavern.core.data.ContentRepository
import com.simpletavern.core.data.DataOperationGate
import com.simpletavern.core.data.Jsonx
import com.simpletavern.core.model.*
import com.simpletavern.core.tools.JavascriptEvaluator
import com.simpletavern.core.tools.ToolResult
import com.simpletavern.core.tools.UserQuestions
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Implements the portable subset of imported localTools, without a second tool runtime. */
internal class AssistantLocalTools(
    private val content: ContentRepository,
    private val gate: DataOperationGate,
    private val config: AssistantConfig,
    private val appContext: Context,
    private val deviceTools: AssistantDeviceTools,
    private val speech: AssistantSpeechService,
    private val chatId: String,
) {
    private fun selected(config: AssistantConfig): Set<String> {
        val options = Jsonx.decode<JsonObject>(config.settingsJson)["localTools"] as? JsonArray ?: return emptySet()
        return options.mapNotNull { (it as? JsonObject)?.get("type")?.let { value -> (value as? JsonPrimitive)?.contentOrNull } }.toSet()
    }
    private val names = mapOf("eval_javascript" to "javascript_engine", "get_time_info" to "time_info",
        "ask_user" to "ask_user", "clipboard_tool" to "clipboard", "calendar_query" to "calendar",
        "calendar_create" to "calendar", "get_screen_time" to "screen_time", "text_to_speech" to "tts")
    val categories = names.filterValues { it in selected(config) }.mapValues { (name, _) ->
        if (name in setOf("calendar_create", "text_to_speech")) ApprovalCategory.WRITE else ApprovalCategory.READ
    }
    val forceApprovalTools = setOf("calendar_create").intersect(categories.keys)
    val beforeUserQuestion: (suspend () -> Unit)? = if ("ask_user" in categories) ({ requireSelection("ask_user") }) else null
    val definitions = JsonArray(categories.keys.filter { it != "ask_user" && it !in AssistantDeviceTools.names }.map { name -> buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", name)
            put("description", when (name) {
                "eval_javascript" -> "执行一次独立 Rhino JavaScript（ES6 及该引擎支持的扩展，非 QuickJS）。返回最后表达式的字符串/JSON和 console 日志。无 DOM、Node、Java、文件或网络；不支持模块和异步宿主接口。代码最多256000字符，结果最多128000字符。"
                "clipboard_tool" -> "读取或写入设备剪贴板纯文本。action 为 read/write，写入必须提供 text，且只在用户明确要求时使用；文本最多256000 UTF-8字节。系统可能限制后台读取，不转换或读取剪贴板 URI/文件。"
                "text_to_speech" -> "按本来源已选语音配置启动设备朗读/合成后播放；提供自然文本，不含 Markdown。立即返回独立播放 task_id，不代表已播放完成，可查看进度或停止。仅在用户要求朗读或适合音频输出时使用；未支持提供方明确失败，不静默改用系统引擎。"
                else -> "返回设备当前本地日期、时间、星期、时区、UTC偏移及毫秒时间戳。"
            })
            put("parameters", buildJsonObject {
                put("type", "object"); put("additionalProperties", false)
                put("properties", buildJsonObject {
                    if (name == "eval_javascript") put("code", buildJsonObject { put("type", "string") })
                    if (name == "text_to_speech") put("text", buildJsonObject { put("type", "string") })
                    if (name == "clipboard_tool") {
                        put("action", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("read", "write").map(::JsonPrimitive))) })
                        put("text", buildJsonObject { put("type", "string") })
                    }
                })
                put("required", JsonArray(when (name) {
                    "eval_javascript" -> listOf(JsonPrimitive("code"))
                    "clipboard_tool" -> listOf(JsonPrimitive("action"))
                    "text_to_speech" -> listOf(JsonPrimitive("text"))
                    else -> emptyList()
                }))
            })
        })
    } } + (if ("ask_user" in categories) listOf(UserQuestions.definition) else emptyList()) +
        AssistantDeviceTools.definitions.filter { it.jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content in categories })

    fun category(name: String, args: JsonObject): ApprovalCategory? = if (name != "clipboard_tool") categories[name]
        else when (args["action"]?.jsonPrimitive?.content) {
            "read" -> ApprovalCategory.READ
            "write" -> {
                if (args["text"] == null) throw StError.Validation("clipboard write requires text")
                ApprovalCategory.WRITE
            }
            else -> throw StError.Validation("clipboard action must be read or write")
        }

    private suspend fun requireSelection(name: String) {
        gate.write {
            val current = content.getAssistant(config.id) ?: throw StError.NotFound("assistant configuration")
            if (current.sourceNamespace != config.sourceNamespace || names[name] !in selected(current))
                throw StError.Conflict("assistant local tool selection changed")
        }
    }

    suspend fun dispatch(name: String, args: JsonObject): ToolResult {
        requireSelection(name)
        val data = when (name) {
            "eval_javascript" -> JavascriptEvaluator.evaluate(args.getValue("code").jsonPrimitive.content)
            "clipboard_tool" -> clipboard(args)
            "text_to_speech" -> speech.start(config, chatId, args.getValue("text").jsonPrimitive.content)
            "calendar_query", "calendar_create", "get_screen_time" -> deviceTools.dispatch(name, args)
            "get_time_info" -> {
                val at = ZonedDateTime.now()
                buildJsonObject {
                    put("year", at.year); put("month", at.monthValue); put("day", at.dayOfMonth)
                    put("weekday", at.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()))
                    put("weekday_en", at.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
                    put("weekday_index", at.dayOfWeek.value)
                    put("date", at.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE))
                    put("time", at.toLocalTime().withNano(0).format(DateTimeFormatter.ISO_LOCAL_TIME))
                    put("datetime", at.withNano(0).format(DateTimeFormatter.ISO_ZONED_DATE_TIME))
                    put("timezone", at.zone.id); put("utc_offset", at.offset.id)
                    put("timestamp_ms", at.toInstant().toEpochMilli())
                }
            }
            else -> throw StError.Unsupported("unknown assistant local tool")
        }
        val ok = data["error"] == null
        val output = buildJsonObject { put("ok", ok); put("tool", name); put("data", data) }.toString()
        if (output.toByteArray(Charsets.UTF_8).size > 1_000_000) throw StError.Validation("local tool result exceeds text budget")
        return ToolResult(output, buildJsonObject { put("toolName", name); put("ok", ok) }.toString())
    }

    private suspend fun clipboard(args: JsonObject): JsonObject = withContext(Dispatchers.Main) {
        val manager = appContext.getSystemService(ClipboardManager::class.java)
            ?: throw StError.Unsupported("device clipboard service unavailable")
        try {
            if (args.getValue("action").jsonPrimitive.content == "read") {
                val clip = manager.primaryClip
                val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                if (text != null && text.toByteArray(Charsets.UTF_8).size > 256_000)
                    throw StError.Validation("clipboard text exceeds 256000 UTF-8 bytes")
                buildJsonObject {
                    put("text", text?.let(::JsonPrimitive) ?: JsonNull)
                    put("available", text != null)
                    if (text == null) put("note", "系统未提供纯文本；可能为空、非文本剪贴板或后台/焦点访问受限。未读取 URI 或文件。")
                }
            } else {
                val text = args.getValue("text").jsonPrimitive.content
                if (text.toByteArray(Charsets.UTF_8).size > 256_000)
                    throw StError.Validation("clipboard text exceeds 256000 UTF-8 bytes")
                manager.setPrimaryClip(ClipData.newPlainText("SimpleTavern", text))
                buildJsonObject { put("success", true); put("text", text) }
            }
        } catch (error: SecurityException) {
            throw StError.Validation("device clipboard access denied by Android")
        }
    }
}
