package com.simpletavern.core.model

import kotlinx.serialization.json.*

/** Only generation controls, never credentials, headers, messages, tools or arbitrary request bodies. */
object GenerationParameters {
    private val json = Json { isLenient = false }
    private val aliases = mapOf("topP" to "top_p", "topK" to "top_k", "maxTokens" to "max_tokens",
        "maxOutputTokens" to "max_tokens", "max_output_tokens" to "max_tokens", "max_completion_tokens" to "max_tokens",
        "reasoningEffort" to "reasoning_effort", "reasoningSummary" to "reasoning_summary",
        "presencePenalty" to "presence_penalty", "frequencyPenalty" to "frequency_penalty",
        "stopSequences" to "stop", "stop_sequences" to "stop", "thinkingBudget" to "thinking_budget",
        "thinkingLevel" to "thinking_level", "includeThoughts" to "include_thoughts", "maxTokenField" to "max_token_field",
        "fastMode" to "fast_mode", "serviceTier" to "service_tier", "streamEnabled" to "stream", "streamOutput" to "stream",
        "responseModalities" to "response_modalities", "imageConfig" to "image_config", "speechConfig" to "speech_config",
        "responseFormat" to "response_format")
    private val keys = setOf("max_tokens", "max_token_field", "top_k", "reasoning_effort", "reasoning_summary", "verbosity",
        "presence_penalty", "frequency_penalty", "seed", "stop", "thinking", "thinking_budget", "thinking_level",
        "include_thoughts", "output_config", "service_tier", "speed", "prompt_cache_key", "prompt_cache_retention",
        "prompt_cache_options", "cache_control", "cache_breakpoints", "image_generation", "response_modalities", "image_config",
        "modalities", "audio", "speech_config", "response_format")
    val sourceKeys: Set<String> = keys + aliases.keys + setOf("temperature", "top_p", "generationConfig", "thinkingConfig", "reasoning", "fast_mode", "promptCache", "anthropicPromptCache", "stream")

    fun streaming(params: JsonObject): Boolean {
        val value = normalize(params)["stream"] ?: return true
        return (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            ?: throw StError.Validation("stream must be boolean")
    }

    fun normalize(obj: JsonObject): JsonObject {
        val result = obj.filterValues { it != JsonNull }.toMutableMap()
        for ((alias, canonical) in aliases) result.remove(alias)?.let { if (canonical !in result) result[canonical] = it }
        if (obj["max_completion_tokens"] != null && obj["max_completion_tokens"] != JsonNull && "max_token_field" !in result)
            result["max_token_field"] = JsonPrimitive("max_completion_tokens")
        result.remove("generationConfig")?.let { value ->
            val nested = value as? JsonObject ?: invalid("generationConfig")
            if (nested.keys.any { it !in sourceKeys || it == "generationConfig" }) unsupported("generationConfig")
            result.putAll(normalize(nested))
        }
        result.remove("reasoning")?.let { value ->
            val nested = value as? JsonObject ?: invalid("reasoning")
            if (nested.keys.any { it !in setOf("effort", "summary") }) unsupported("reasoning")
            nested["effort"]?.takeIf { it != JsonNull }?.let { result["reasoning_effort"] = it }
            nested["summary"]?.takeIf { it != JsonNull }?.let { result["reasoning_summary"] = it }
        }
        result.remove("thinkingConfig")?.let { value ->
            val nested = value as? JsonObject ?: invalid("thinkingConfig")
            if (nested.keys.any { it !in setOf("thinkingBudget", "thinkingLevel", "includeThoughts") }) unsupported("thinkingConfig")
            result.putAll(normalize(nested))
        }
        return JsonObject(result)
    }

    fun extras(params: JsonObject): Map<String, String> = params.filterKeys { it in keys }.mapValues { (name, value) ->
        if (name in setOf("stop", "thinking", "output_config", "prompt_cache_options", "cache_control", "cache_breakpoints",
                "image_generation", "response_modalities", "image_config", "modalities", "audio", "speech_config", "response_format")) value.toString()
        else (value as? JsonPrimitive)?.contentOrNull ?: invalid(name)
    }

    /** Returns native wire fields. Explicit unsupported controls fail before the HTTP callback. */
    fun wire(protocol: LlmProtocol, temperature: Double?, topP: Double?, extra: Map<String, String>): JsonObject {
        if (extra.keys.any { it !in keys }) unsupported("extra")
        if (extra.entries.sumOf { it.key.toByteArray().size.toLong() + it.value.toByteArray().size } > 65_536) invalid("extra")
        fun integer(name: String, min: Long = 1, max: Long = Int.MAX_VALUE.toLong()): Long? = extra[name]?.let {
            it.toLongOrNull()?.takeIf { n -> n in min..max } ?: invalid(name)
        }
        fun number(name: String): Double? = extra[name]?.let { it.toDoubleOrNull()?.takeIf { n -> n.isFinite() && n in -2.0..2.0 } ?: invalid(name) }
        fun boolean(name: String): Boolean? = extra[name]?.let { when (it) { "true" -> true; "false" -> false; else -> invalid(name) } }
        fun choice(name: String, values: Set<String>): String? = extra[name]?.takeIf { it in values } ?: if (name in extra) invalid(name) else null
        fun objectValue(name: String): JsonObject? = extra[name]?.let { raw ->
            try { json.parseToJsonElement(raw) as? JsonObject ?: invalid(name) } catch (_: Exception) { invalid(name) }
        }
        val output = linkedMapOf<String, JsonElement>()
        fun put(name: String, value: JsonElement?) { if (value != null) output[name] = value }
        if (protocol == LlmProtocol.BEDROCK_CONVERSE) {
            val supported = setOf("max_tokens", "max_token_field", "top_p", "stop", "top_k", "thinking",
                "thinking_budget", "output_config", "service_tier", "cache_control", "cache_breakpoints", "response_format")
            extra.keys.firstOrNull { it !in supported }?.let { unsupported(it) }
            val inference = linkedMapOf<String, JsonElement>()
            val additional = linkedMapOf<String, JsonElement>()
            val maxTokens = integer("max_tokens")
            choice("max_token_field", setOf("max_tokens", "max_completion_tokens"))
            maxTokens?.let { inference["maxTokens"] = JsonPrimitive(it) }
            temperature?.let {
                if (!it.isFinite() || it !in 0.0..1.0) invalid("temperature")
                inference["temperature"] = JsonPrimitive(it)
            }
            extra["top_p"]?.let { raw ->
                val value = raw.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: invalid("top_p")
                inference["topP"] = JsonPrimitive(value)
            }
            topP?.let {
                if (!it.isFinite() || it !in 0.0..1.0) invalid("top_p")
                inference["topP"] = JsonPrimitive(it)
            }
            extra["stop"]?.let { raw ->
                val value = try { json.parseToJsonElement(raw) } catch (_: Exception) { invalid("stop") }
                val values = if (value is JsonArray) value else JsonArray(listOf(value))
                if (values.size > 4 || values.any { it !is JsonPrimitive || !it.isString || it.content.isEmpty() }) invalid("stop")
                values.forEach { SpeechText.validate(it.jsonPrimitive.content, 4096) }
                inference["stopSequences"] = values
            }
            integer("top_k", 0, 500)?.let { value ->
                additional["top_k"] = JsonPrimitive(value)
            }
            val thinking = objectValue("thinking")
            val thinkingBudget = integer("thinking_budget", 1024)
            if (thinking != null && thinkingBudget != null) invalid("thinking/thinking_budget conflict")
            val normalizedThinking = thinking ?: thinkingBudget?.let {
                buildJsonObject { put("type", "enabled"); put("budget_tokens", it) }
            }
            normalizedThinking?.let { config ->
                if (config.keys.any { it !in setOf("type", "budget_tokens") }) unsupported("thinking field")
                val type = (config["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (type !in setOf("enabled", "disabled", "adaptive")) invalid("thinking")
                val budget = config["budget_tokens"]?.let {
                    (it as? JsonPrimitive)?.takeUnless { primitive -> primitive.isString }?.longOrNull
                        ?.takeIf { value -> value in 1024..Int.MAX_VALUE.toLong() } ?: invalid("thinking budget")
                }
                if ((type == "enabled") != (budget != null)) invalid("thinking budget")
                if (type == "enabled" && maxTokens != null && budget!! >= maxTokens) invalid("thinking budget/max_tokens")
                if (type != "disabled" && (temperature != null && temperature != 1.0 || topP != null ||
                            "top_p" in extra || "top_k" in extra)) invalid("thinking sampling conflict")
                additional["thinking"] = config
            }
            objectValue("output_config")?.let { config ->
                if (config.keys.any { it != "effort" } ||
                    (config["effort"] as? JsonPrimitive)?.takeIf { it.isString }?.content !in setOf("low", "medium", "high", "xhigh", "max"))
                    invalid("output_config")
                additional["output_config"] = config
            }
            objectValue("response_format")?.let { config ->
                if (config.keys.any { it !in setOf("type", "json_schema") }) unsupported("response_format field")
                val type = (config["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: invalid("response_format.type")
                if (type != "json_schema") unsupported("response_format type: $type")
                val definition = config["json_schema"] as? JsonObject ?: invalid("response_format.json_schema")
                if (definition.keys.any { it !in setOf("schema", "name", "description", "strict") })
                    unsupported("response_format.json_schema field")
                definition["strict"]?.let { value ->
                    val enabled = (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                        ?: invalid("response_format.json_schema.strict")
                    if (!enabled) unsupported("response_format.json_schema.strict=false")
                }
                val schema = definition["schema"] as? JsonObject ?: invalid("response_format.json_schema.schema")
                fun optionalText(name: String): String? = definition[name]?.let { value ->
                    (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid("response_format.json_schema.$name")
                }
                val name = optionalText("name")
                val description = optionalText("description")
                output["outputConfig"] = buildJsonObject {
                    put("textFormat", buildJsonObject {
                        put("type", "json_schema")
                        put("structure", buildJsonObject {
                            put("jsonSchema", buildJsonObject {
                                put("schema", schema.toString())
                                name?.let { put("name", it) }
                                description?.let { put("description", it) }
                            })
                        })
                    })
                }
            }
            choice("service_tier", setOf("priority", "default", "flex", "reserved"))?.let {
                output["serviceTier"] = buildJsonObject { put("type", it) }
            }
            val cacheControl = objectValue("cache_control")
            cacheControl?.let {
                if (it.keys.any { key -> key !in setOf("type", "ttl") } || it["type"] != JsonPrimitive("ephemeral") ||
                    it["ttl"]?.let { ttl -> ttl !in setOf(JsonPrimitive("5m"), JsonPrimitive("1h")) } == true)
                    invalid("cache_control")
            }
            val cacheBreakpoints = extra["cache_breakpoints"]?.let { raw ->
                val values = try { json.parseToJsonElement(raw) as? JsonArray ?: invalid("cache_breakpoints") }
                    catch (_: Exception) { invalid("cache_breakpoints") }
                if (values.size !in 1..3 || values.any {
                        it !is JsonPrimitive || !it.isString || it.content !in setOf("system", "tools", "history_tail")
                    } || values.distinct().size != values.size) invalid("cache_breakpoints")
                values
            }
            if (cacheBreakpoints != null && cacheControl == null) invalid("cache_breakpoints without cache_control")
            return buildJsonObject {
                put("inferenceConfig", JsonObject(inference))
                if (additional.isNotEmpty()) put("additionalModelRequestFields", JsonObject(additional))
                output.forEach { (key, value) -> put(key, value) }
            }
        }
        extra["modalities"]?.let { raw ->
            if (protocol != LlmProtocol.OPENAI_COMPATIBLE) unsupported("modalities")
            val values = try { json.parseToJsonElement(raw) as? JsonArray ?: invalid("modalities") }
                catch (_: Exception) { invalid("modalities") }
            if (values.isEmpty() || values.any { it !in listOf(JsonPrimitive("text"), JsonPrimitive("audio")) }) invalid("modalities")
            put("modalities", values)
        }
        val audio = objectValue("audio")
        audio?.let { options ->
            if (protocol != LlmProtocol.OPENAI_COMPATIBLE) unsupported("audio")
            if (options.keys.any { it !in setOf("format", "voice") } ||
                options["format"] !in listOf("wav", "aac", "mp3", "flac", "opus", "pcm16").map(::JsonPrimitive)) invalid("audio")
            when (val voice = options["voice"]) {
                is JsonPrimitive -> if (!voice.isString || voice.content.isBlank()) invalid("audio.voice")
                is JsonObject -> if (voice.keys != setOf("id") || (voice["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content.isNullOrBlank()) invalid("audio.voice")
                else -> invalid("audio.voice")
            }
            put("audio", options)
        }
        if (protocol == LlmProtocol.OPENAI_COMPATIBLE &&
            ((output["modalities"] as? JsonArray)?.contains(JsonPrimitive("audio")) == true) != (audio != null))
            invalid("modalities/audio: configure both output modality and audio format/voice")
        objectValue("image_generation")?.let { options ->
            if (protocol != LlmProtocol.OPENAI_RESPONSES) unsupported("image_generation")
            if (options.keys.any { it !in setOf("model", "action", "quality", "size", "background", "output_format",
                    "output_compression", "partial_images", "moderation") }) unsupported("image_generation field")
            options.forEach { (key, value) ->
                val primitive = value as? JsonPrimitive ?: invalid("image_generation.$key")
                if (key in setOf("output_compression", "partial_images")) {
                    val number = primitive.takeUnless { it.isString }?.intOrNull ?: invalid("image_generation.$key")
                    if (number !in 0..if (key == "partial_images") 3 else 100) invalid("image_generation.$key")
                } else if (!primitive.isString || primitive.content.isBlank()) invalid("image_generation.$key")
            }
            if (options["output_format"]?.let { it !in listOf(JsonPrimitive("png"), JsonPrimitive("jpeg"), JsonPrimitive("webp")) } == true)
                invalid("image_generation.output_format")
            put("image_generation", options)
        }
        extra["response_modalities"]?.let { raw ->
            if (protocol != LlmProtocol.GEMINI_GENERATE_CONTENT) unsupported("response_modalities")
            val modalities = try { json.parseToJsonElement(raw) as? JsonArray ?: invalid("response_modalities") }
                catch (_: Exception) { invalid("response_modalities") }
            if (modalities.isEmpty() || modalities.any { it !in listOf(JsonPrimitive("TEXT"), JsonPrimitive("IMAGE"), JsonPrimitive("AUDIO")) })
                unsupported("response_modalities: supported output types are TEXT/IMAGE/AUDIO")
            put("responseModalities", modalities)
        }
        objectValue("speech_config")?.let { config ->
            if (protocol != LlmProtocol.GEMINI_GENERATE_CONTENT) unsupported("speech_config")
            if (config.keys.any { it !in setOf("voiceConfig", "multiSpeakerVoiceConfig", "languageCode") }) unsupported("speech_config field")
            put("speechConfig", config)
        }
        objectValue("response_format")?.let { config ->
            if (protocol == LlmProtocol.OPENAI_COMPATIBLE) {
                val type = (config["type"] as? JsonPrimitive)?.contentOrNull
                if (type !in setOf("text", "json_object", "json_schema") || config.keys.any { it !in setOf("type", "json_schema") } ||
                    type == "json_schema" && config["json_schema"] !is JsonObject) invalid("response_format")
                put("response_format", config)
            } else {
                if (protocol != LlmProtocol.GEMINI_GENERATE_CONTENT) unsupported("response_format")
                if (config.keys != setOf("audio")) invalid("response_format")
                val options = config["audio"] as? JsonObject ?: invalid("response_format.audio")
                if (options.keys.any { it !in setOf("mimeType", "sampleRate") } ||
                    options["mimeType"] !in listOf("AUDIO_WAV", "AUDIO_L16", "AUDIO_MULAW", "AUDIO_ALAW").map(::JsonPrimitive)) invalid("response_format.audio")
                options["sampleRate"]?.let { rate ->
                    if ((rate as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull !in setOf(8000, 16000, 24000)) invalid("response_format.audio.sampleRate")
                }
                put("responseFormat", config)
            }
        }
        objectValue("image_config")?.let { config ->
            if (protocol != LlmProtocol.GEMINI_GENERATE_CONTENT) unsupported("image_config")
            if (config.keys.any { it !in setOf("aspectRatio", "imageSize") } || config.values.any {
                    it !is JsonPrimitive || !it.isString || it.content.isBlank() }) invalid("image_config")
            put("imageConfig", config)
        }
        choice("service_tier", setOf("auto", "default", "flex", "priority", "fast"))?.let {
            if (protocol !in setOf(LlmProtocol.OPENAI_COMPATIBLE, LlmProtocol.OPENAI_RESPONSES)) unsupported("service_tier")
            put("service_tier", JsonPrimitive(it))
        }
        choice("speed", setOf("fast", "standard"))?.let {
            if (protocol != LlmProtocol.ANTHROPIC_MESSAGES) unsupported("speed")
            put("speed", JsonPrimitive(it))
        }
        val openAi = protocol in setOf(LlmProtocol.OPENAI_COMPATIBLE, LlmProtocol.OPENAI_RESPONSES)
        extra["prompt_cache_key"]?.let {
            if (!openAi) unsupported("prompt_cache_key")
            SpeechText.validate(it, 1024)
            if (it.isBlank() || it.any(Char::isISOControl) || it.length > 256) invalid("prompt_cache_key")
            put("prompt_cache_key", JsonPrimitive(it))
        }
        choice("prompt_cache_retention", setOf("in_memory", "24h"))?.let {
            if (!openAi) unsupported("prompt_cache_retention")
            put("prompt_cache_retention", JsonPrimitive(it))
        }
        val options = objectValue("prompt_cache_options")
        options?.let {
            if (!openAi) unsupported("prompt_cache_options")
            if (it.keys.any { k -> k !in setOf("mode", "ttl") } ||
                (it["mode"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content !in setOf("implicit", "explicit") ||
                it["ttl"]?.let { ttl -> ttl != JsonPrimitive("30m") } == true) invalid("prompt_cache_options")
            put("prompt_cache_options", it)
        }
        val control = objectValue("cache_control")
        control?.let {
            if (protocol != LlmProtocol.ANTHROPIC_MESSAGES) unsupported("cache_control")
            if (it.keys.any { k -> k !in setOf("type", "ttl") } || it["type"] != JsonPrimitive("ephemeral") ||
                it["ttl"]?.let { ttl -> ttl !in setOf(JsonPrimitive("5m"), JsonPrimitive("1h")) } == true) invalid("cache_control")
        }
        val breakpoints = extra["cache_breakpoints"]?.let { raw ->
            val array = try { json.parseToJsonElement(raw) as? JsonArray ?: invalid("cache_breakpoints") } catch (_: Exception) { invalid("cache_breakpoints") }
            if (array.size !in 1..3 || array.any { it !is JsonPrimitive || !it.isString || it.content !in setOf("system", "tools", "history_tail") } ||
                array.distinct().size != array.size) invalid("cache_breakpoints")
            array.map { it.jsonPrimitive.content }.toSet()
        }
        if (protocol == LlmProtocol.ANTHROPIC_MESSAGES) {
            if (breakpoints != null && control == null) invalid("cache_breakpoints without cache_control")
            if (breakpoints == null || "history_tail" in breakpoints) put("cache_control", control)
        } else if (breakpoints != null || options?.get("mode") == JsonPrimitive("explicit")) {
            if (protocol != LlmProtocol.OPENAI_RESPONSES || options?.get("mode") != JsonPrimitive("explicit") ||
                breakpoints != null && breakpoints != setOf("system")) unsupported("explicit OpenAI cache breakpoints")
        }
        temperature?.let {
            if (!it.isFinite() || it !in 0.0..(if (protocol == LlmProtocol.ANTHROPIC_MESSAGES) 1.0 else 2.0)) invalid("temperature")
            put("temperature", JsonPrimitive(it))
        }
        topP?.let { if (!it.isFinite() || it !in 0.0..1.0) invalid("top_p"); put(if (protocol == LlmProtocol.GEMINI_GENERATE_CONTENT) "topP" else "top_p", JsonPrimitive(it)) }
        val maxTokens = integer("max_tokens")
        val field = choice("max_token_field", setOf("max_tokens", "max_completion_tokens")) ?: "max_tokens"
        put(when (protocol) { LlmProtocol.OPENAI_COMPATIBLE -> field; LlmProtocol.OPENAI_RESPONSES -> "max_output_tokens"
            LlmProtocol.GEMINI_GENERATE_CONTENT -> "maxOutputTokens"; else -> "max_tokens" }, maxTokens?.let(::JsonPrimitive))
        integer("top_k")?.let {
            if (protocol == LlmProtocol.OPENAI_RESPONSES) unsupported("top_k")
            put(if (protocol == LlmProtocol.GEMINI_GENERATE_CONTENT) "topK" else "top_k", JsonPrimitive(it))
        }
        for (name in listOf("presence_penalty", "frequency_penalty")) number(name)?.let {
            if (protocol !in setOf(LlmProtocol.OPENAI_COMPATIBLE, LlmProtocol.GEMINI_GENERATE_CONTENT)) unsupported(name)
            val native = if (protocol == LlmProtocol.GEMINI_GENERATE_CONTENT) aliases.entries.first { entry -> entry.value == name }.key else name
            put(native, JsonPrimitive(it))
        }
        integer("seed", Long.MIN_VALUE, Long.MAX_VALUE)?.let {
            if (protocol == LlmProtocol.GEMINI_GENERATE_CONTENT && it !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) invalid("seed")
            if (protocol !in setOf(LlmProtocol.OPENAI_COMPATIBLE, LlmProtocol.GEMINI_GENERATE_CONTENT)) unsupported("seed")
            put("seed", JsonPrimitive(it))
        }
        extra["stop"]?.let { raw ->
            if (protocol == LlmProtocol.OPENAI_RESPONSES) unsupported("stop")
            val value = try { json.parseToJsonElement(raw) } catch (_: Exception) { invalid("stop") }
            val values = if (value is JsonArray) value else JsonArray(listOf(value))
            val limit = when (protocol) { LlmProtocol.OPENAI_COMPATIBLE -> 4; LlmProtocol.GEMINI_GENERATE_CONTENT -> 5; else -> 64 }
            if (values.size > limit || values.any { it !is JsonPrimitive || !it.isString || it.content.isEmpty() }) invalid("stop")
            values.forEach { SpeechText.validate(it.jsonPrimitive.content, 4096) }
            put(when (protocol) { LlmProtocol.ANTHROPIC_MESSAGES -> "stop_sequences"; LlmProtocol.GEMINI_GENERATE_CONTENT -> "stopSequences"; else -> "stop" }, values)
        }
        val effort = choice("reasoning_effort", setOf("none", "minimal", "low", "medium", "high", "xhigh", "max"))
        val summary = choice("reasoning_summary", setOf("auto", "concise", "detailed"))
        val verbosity = choice("verbosity", setOf("low", "medium", "high"))
        if (protocol == LlmProtocol.OPENAI_RESPONSES) {
            if (effort != null || summary != null) put("reasoning", buildJsonObject { effort?.let { put("effort", it) }; summary?.let { put("summary", it) } })
            verbosity?.let { put("text", buildJsonObject { put("verbosity", it) }) }
        } else {
            if (summary != null || verbosity != null) unsupported("reasoning_summary/verbosity")
            if (effort != null) {
                if (protocol != LlmProtocol.OPENAI_COMPATIBLE) unsupported("reasoning_effort: select native thinking controls")
                put("reasoning_effort", JsonPrimitive(effort))
            }
        }
        objectValue("thinking")?.let { thinking ->
            if (protocol !in setOf(LlmProtocol.ANTHROPIC_MESSAGES, LlmProtocol.OPENAI_COMPATIBLE)) unsupported("thinking")
            if (thinking.keys.any { it !in setOf("type", "budget_tokens") }) unsupported("thinking field")
            val type = (thinking["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (type !in setOf("enabled", "disabled", "adaptive") || protocol == LlmProtocol.OPENAI_COMPATIBLE && type == "adaptive") invalid("thinking")
            val budget = thinking["budget_tokens"]?.let { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.longOrNull ?: invalid("thinking budget") }
            if (type != "enabled" && budget != null || budget?.let { it <= 0 || it > Int.MAX_VALUE } == true) invalid("thinking budget")
            if (protocol == LlmProtocol.ANTHROPIC_MESSAGES && type == "enabled" && (budget == null || budget < 1024 || budget >= (maxTokens ?: 4096))) invalid("thinking budget/max_tokens")
            if (type != "disabled" && protocol == LlmProtocol.ANTHROPIC_MESSAGES) {
                if (temperature != null && temperature != 1.0 || topP != null || "top_k" in extra) invalid("thinking sampling conflict")
                output.remove("temperature")
            }
            put("thinking", thinking)
        }
        objectValue("output_config")?.let { config ->
            if (protocol != LlmProtocol.ANTHROPIC_MESSAGES) unsupported("output_config")
            if (config.keys.any { it != "effort" } || (config["effort"] as? JsonPrimitive)?.takeIf { it.isString }?.content !in setOf("low", "medium", "high", "xhigh", "max")) invalid("output_config")
            put("output_config", config)
        }
        val budget = integer("thinking_budget", -1)
        val level = choice("thinking_level", setOf("minimal", "low", "medium", "high"))
        val thoughts = boolean("include_thoughts")
        if (budget != null || level != null || thoughts != null) {
            if (protocol != LlmProtocol.GEMINI_GENERATE_CONTENT) unsupported("thinkingConfig")
            if (budget != null && level != null) invalid("thinkingBudget/thinkingLevel conflict")
            put("thinkingConfig", buildJsonObject { budget?.let { put("thinkingBudget", it) }; level?.let { put("thinkingLevel", it) }; thoughts?.let { put("includeThoughts", it) } })
        }
        return JsonObject(output)
    }
    private fun invalid(name: String): Nothing = throw StError.Validation("invalid generation control: $name")
    private fun unsupported(name: String): Nothing = throw StError.Unsupported("generation control requires a protocol adapter: $name")
}
