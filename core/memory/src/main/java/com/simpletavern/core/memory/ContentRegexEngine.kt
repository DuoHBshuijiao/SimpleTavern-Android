package com.simpletavern.core.memory

import com.simpletavern.core.model.*
import com.google.re2j.Matcher
import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

/** Bounded cooperative execution, not a process sandbox or a hard real-time timeout. */
internal object ContentRegexEngine {
    // Android's replacement dialect depends on OS, target SDK and app-compat flags, not just API level.
    private val greedyKotlinGroups by lazy {
        java.util.regex.Pattern.compile("()()()()()()()()()()").matcher("").replaceAll("\$10").isEmpty()
    }
    private val kotlinPatterns = object : LinkedHashMap<String, Regex>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Regex>): Boolean = size > 256
    }
    private fun kotlinPattern(raw: String): Regex = try {
        synchronized(kotlinPatterns) {
            if (raw.length > 4_000) throw StError.Validation("regex pattern exceeds 4000 characters")
            kotlinPatterns.getOrPut(raw) { Regex(raw) }
        }
    } catch (_: java.util.regex.PatternSyntaxException) {
        throw StError.Validation("invalid Kotlin regex pattern")
    } catch (_: StackOverflowError) { throw StError.Validation("regex pattern is too complex") }
    private class BudgetExceeded : RuntimeException()
    private class Budget(private val context: CoroutineContext) {
        private val deadline = System.nanoTime() + 150_000_000L
        private var accesses = 0
        fun check() {
            if (++accesses % 256 == 0) {
                context.ensureActive()
                if (accesses > 1_000_000 || System.nanoTime() > deadline) throw BudgetExceeded()
            }
        }
    }
    private class GuardedText(private val text: String, private val budget: Budget) : CharSequence {
        override val length: Int get() { budget.check(); return text.length }
        override fun get(index: Int): Char { budget.check(); return text[index] }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = GuardedText(text.substring(startIndex, endIndex), budget)
        override fun toString(): String { budget.check(); return text }
    }
    private fun compile(raw: String): Pattern = try {
        if (raw.length > 4_000) throw StError.Validation("regex pattern exceeds 4000 characters")
        var body = raw
        var flags = Pattern.MULTILINE or Pattern.DOTALL
        if (raw.startsWith('/')) {
            var escaped = false
            var inClass = false
            var closing = -1
            for (i in 1 until raw.length) {
                val c = raw[i]
                if (escaped) { escaped = false; continue }
                when (c) {
                    '\\' -> escaped = true
                    '[' -> inClass = true
                    ']' -> inClass = false
                    '/' -> if (!inClass) closing = i
                }
            }
            if (closing > 0) {
                val suffix = raw.substring(closing + 1)
                if (suffix.any { it !in "imsu" } || suffix.toSet().size != suffix.length) {
                    throw StError.Unsupported("regex literal flags must be unique i/m/s/u")
                }
                body = raw.substring(1, closing)
                if ('i' in suffix) flags = flags or Pattern.CASE_INSENSITIVE
            }
        }
        if (body.contains("(?P<") || body.contains("(?P=") || body.contains("\\g<")) {
            throw StError.Unsupported("Python-specific named regex syntax is not supported")
        }
        val pattern = try { Pattern.compile(body, flags) }
        catch (_: PatternSyntaxException) { throw StError.Unsupported("invalid or unsupported RE2/J regex syntax") }
        pattern.also {
            if (it.programSize() > 10_000) throw StError.Validation("regex program exceeds budget")
        }
    } catch (_: StackOverflowError) { throw StError.Validation("regex pattern is too complex") }

    fun validate(rule: ContentRegexRule) {
        validateFields(rule)
        if (rule.enabled && (rule.pattern.isNotBlank() || rule.engine == "kotlin")) {
            if (rule.engine == "kotlin") kotlinPattern(rule.pattern) else compile(rule.pattern)
        }
    }

    private fun validateFields(rule: ContentRegexRule) {
        if (rule.id.isBlank() || rule.id.length > 200 || (rule.name?.length ?: 0) > 200 ||
            rule.action !in setOf("remove", "replace", "extract", "extract_and_replace") ||
            rule.matchMode !in setOf("first", "global") || rule.extractSource !in setOf("whole_match", "capture_group") ||
            rule.extractGroupIndex?.let { it < 0 } == true || rule.scanDepthOverride?.let { it !in 1..500 } == true ||
            (rule.replacement?.length ?: 0) > 12_000 || rule.engine !in setOf("re2", "kotlin") ||
            rule.roles.any { it !in setOf(ChatRole.user, ChatRole.assistant) } ||
            rule.engine == "kotlin" && (rule.action != "replace" || rule.matchMode != "global")) throw StError.Validation("invalid content regex rule")
    }

    suspend fun apply(text: String, rules: List<ContentRegexRule>, role: ChatRole = ChatRole.assistant,
        sourceProcessed: Boolean = false, reasoning: Boolean = false): ContentRegexOutput = withContext(Dispatchers.Default) {
        if (text.length > 50_000) return@withContext ContentRegexOutput(text, text,
            errors = listOf(RegexRuleError("", "budget", "source exceeds 50000 characters; no rules applied")),
            sourceProcessed = sourceProcessed, messageRole = role)
        var persisted = text
        var display = text
        val extracts = mutableListOf<RegexExtraction>()
        val errors = mutableListOf<RegexRuleError>()
        val ordered = rules.sortedWith(compareBy({ it.order }, { it.id }))
        val pipeline = ordered.filterNot { it.engine == "kotlin" && it.visualOnly } +
            ordered.filter { it.engine == "kotlin" && it.visualOnly }
        for (rule in pipeline) {
            ensureActive()
            if (!rule.enabled || role !in rule.roles || reasoning && (role != ChatRole.assistant || !rule.applyToReasoning) ||
                rule.pattern.isBlank() && rule.engine != "kotlin") continue
            if (rule.engine == "kotlin" && sourceProcessed && !rule.visualOnly) continue
            try {
                // Compile once for this application; save/preview validation still checks syntax at its own boundary.
                validateFields(rule)
                if (rule.engine == "kotlin") {
                    val pattern = kotlinPattern(rule.pattern)
                    val context = currentCoroutineContext()
                    fun replace(input: String): String = replaceKotlin(pattern, input, rule.replacement.orEmpty(), context)
                    val nextPersisted = if (rule.visualOnly) persisted else replace(persisted)
                    val nextDisplay = replace(display)
                    persisted = nextPersisted; display = nextDisplay
                    continue
                }
                val pattern = compile(rule.pattern)
                val budget = Budget(currentCoroutineContext())
                val newExtracts = mutableListOf<RegexExtraction>()
                fun transform(input: String, collect: Boolean, replace: Boolean): String {
                    val matcher = pattern.matcher(GuardedText(input, budget))
                    val output = StringBuilder()
                    var end = 0
                    var count = 0
                    while (matcher.find()) {
                        ensureActive()
                        if (++count > 128) throw BudgetExceeded()
                        if (collect) {
                            val index = if (rule.extractSource == "capture_group") rule.extractGroupIndex ?: 1 else 0
                            val value = if (index <= matcher.groupCount()) matcher.group(index).orEmpty() else ""
                            if (value.isNotEmpty()) newExtracts += RegexExtraction(rule.id, rule.name ?: rule.pattern.take(50),
                                rule.action, value, matcher.group(), matcher.start(), matcher.end())
                        }
                        if (replace) {
                            output.append(input, end, matcher.start())
                            if (rule.action != "remove") output.append(expand(rule.replacement.orEmpty(), matcher))
                            end = matcher.end()
                            if (output.length > 100_000) throw BudgetExceeded()
                        }
                        if (rule.matchMode == "first") break
                    }
                    if (!replace) return input
                    output.append(input, end, input.length)
                    if (output.length > 100_000) throw BudgetExceeded()
                    return output.toString()
                }
                val extracting = !rule.visualOnly && rule.action in setOf("extract", "extract_and_replace")
                val replacing = !rule.visualOnly && rule.action in setOf("remove", "replace")
                val nextPersisted = transform(persisted, extracting, replacing)
                val nextDisplay = if (replacing || rule.visualOnly || rule.action == "extract_and_replace") transform(display, false, true) else display
                if (extracts.size + newExtracts.size > 256 ||
                    extracts.sumOf { it.value.length + it.matchedText.length } + newExtracts.sumOf { it.value.length + it.matchedText.length } > 60_000) {
                    throw BudgetExceeded()
                }
                // A failed rule has no partial effect on either projection or the extraction queue.
                persisted = nextPersisted; display = nextDisplay; extracts += newExtracts
            } catch (e: CancellationException) { throw e }
            catch (_: BudgetExceeded) { errors += RegexRuleError(rule.id, "budget", "regex execution or output budget exceeded") }
            catch (_: StackOverflowError) { errors += RegexRuleError(rule.id, "budget", "regex nesting exceeded the engine stack") }
            catch (e: Exception) { errors += RegexRuleError(rule.id, (e as? StError)?.code ?: "regex", (e as? StError)?.message ?: "invalid regex or replacement") }
        }
        ContentRegexOutput(persisted, display, extracts, errors, sourceProcessed, role)
    }

    /** Java/Kotlin replacement syntax, with the existing output budget applied before each copy. */
    private fun replaceKotlin(pattern: Regex, input: String, replacement: String, context: CoroutineContext): String {
        val budget = Budget(context)
        val matcher = pattern.toPattern().matcher(GuardedText(input, budget))
        val output = StringBuilder()
        fun append(source: String, start: Int, end: Int) {
            budget.check()
            if (end - start > 100_000 - output.length) throw BudgetExceeded()
            output.append(source, start, end)
        }
        var copiedThrough = 0
        // Native find owns zero-length progression, including a final empty match at the input end.
        while (matcher.find()) {
            context.ensureActive()
            append(input, copiedThrough, matcher.start())
            var index = 0
            if (!greedyKotlinGroups) {
                var escaped = false
                var dollar = false
                var nameStart = -1
                while (index < replacement.length) {
                    val char = replacement[index]
                    when {
                        char == '\\' && !escaped -> escaped = true
                        char == '$' && !escaped -> dollar = true
                        char in '0'..'9' && dollar && nameStart < 0 -> {
                            val group = char - '0'
                            val start = matcher.start(group)
                            if (start >= 0) append(input, start, matcher.end(group))
                            dollar = false
                        }
                        char == '{' && dollar -> nameStart = index + 1
                        char == '}' && dollar && nameStart >= 0 -> {
                            val name = replacement.substring(nameStart, index)
                            val start = matcher.start(name)
                            if (start >= 0) append(input, start, matcher.end(name))
                            dollar = false
                            nameStart = -1
                        }
                        char != '}' && dollar && nameStart >= 0 -> Unit
                        else -> {
                            append(replacement, index, index + 1)
                            escaped = false
                            dollar = false
                            nameStart = -1
                        }
                    }
                    index++
                }
                if (escaped || dollar || nameStart >= 0) throw IllegalArgumentException("incomplete replacement escape or group")
            } else while (index < replacement.length) {
                when (replacement[index]) {
                    '\\' -> {
                        index++
                        if (index == replacement.length) throw IllegalArgumentException("incomplete replacement escape")
                        append(replacement, index, index + 1)
                        index++
                    }
                    '$' -> {
                        index++
                        if (index == replacement.length) throw IllegalArgumentException("incomplete replacement group")
                        val start: Int
                        val end: Int
                        if (replacement[index] == '{') {
                            val nameStart = ++index
                            while (index < replacement.length && (replacement[index] in 'a'..'z' ||
                                    replacement[index] in 'A'..'Z' || replacement[index] in '0'..'9')) index++
                            if (index == nameStart || index == replacement.length || replacement[index] != '}' ||
                                replacement[nameStart] in '0'..'9') throw IllegalArgumentException("invalid named replacement group")
                            val name = replacement.substring(nameStart, index++)
                            start = matcher.start(name)
                            end = matcher.end(name)
                        } else {
                            if (replacement[index] !in '0'..'9') throw IllegalArgumentException("invalid replacement group")
                            var group = replacement[index++] - '0'
                            while (index < replacement.length && replacement[index] in '0'..'9') {
                                val next = group * 10 + (replacement[index] - '0')
                                if (next > matcher.groupCount()) break
                                group = next
                                index++
                            }
                            start = matcher.start(group)
                            end = matcher.end(group)
                        }
                        // An unmatched optional group contributes nothing, as in Matcher.replaceAll.
                        if (start >= 0) append(input, start, end)
                    }
                    else -> {
                        val start = index++
                        while (index < replacement.length && replacement[index] != '\\' && replacement[index] != '$') index++
                        append(replacement, start, index)
                    }
                }
            }
            copiedThrough = matcher.end()
        }
        append(input, copiedThrough, input.length)
        return output.toString()
    }

    /** Desktop templates use $N / $$ and Python numeric backreferences, not Java replacement parsing. */
    private fun expand(template: String, matcher: Matcher): String = buildString {
        var i = 0
        fun group(index: Int) {
            if (index > matcher.groupCount()) throw StError.Validation("replacement capture group does not exist")
            append(matcher.group(index).orEmpty())
        }
        while (i < template.length) {
            val c = template[i++]
            when {
                c == '$' && i < template.length && template[i] == '$' -> { append('$'); i++ }
                c == '$' && i < template.length && template[i].isDigit() -> {
                    val start = i
                    while (i < template.length && template[i].isDigit()) i++
                    group(template.substring(start, i).toIntOrNull() ?: throw StError.Validation("invalid replacement group"))
                }
                c == '\\' && i < template.length -> {
                    val next = template[i++]
                    when {
                        next.isDigit() -> {
                            val start = i - 1
                            while (i < template.length && template[i].isDigit()) i++
                            group(template.substring(start, i).toIntOrNull() ?: throw StError.Validation("invalid replacement group"))
                        }
                        next == 'g' && i < template.length && template[i] == '<' -> {
                            val end = template.indexOf('>', ++i)
                            if (end < 0) throw StError.Validation("invalid replacement group")
                            group(template.substring(i, end).toIntOrNull() ?: throw StError.Unsupported("named replacement groups are not supported"))
                            i = end + 1
                        }
                        next == 'n' -> append('\n')
                        next == 'r' -> append('\r')
                        next == 't' -> append('\t')
                        next == '\\' -> append('\\')
                        else -> throw StError.Unsupported("unsupported replacement escape")
                    }
                }
                c == '\\' -> throw StError.Validation("incomplete replacement escape")
                else -> append(c)
            }
            if (length > 100_000) throw BudgetExceeded()
        }
    }
}
