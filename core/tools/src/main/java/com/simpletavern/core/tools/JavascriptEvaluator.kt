package com.simpletavern.core.tools

import com.simpletavern.core.model.StError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/** Fresh, interpreted JavaScript scope per call; no filesystem, network or Java bindings. */
object JavascriptEvaluator {
    private class Interrupted(val cancellation: CancellationException? = null) : Error()

    suspend fun evaluate(code: String): JsonObject = withContext(Dispatchers.Default) {
        if (code.length > 256_000) throw StError.Validation("JavaScript source exceeds 256000 characters")
        val caller = currentCoroutineContext()
        val deadline = System.nanoTime() + 10_000_000_000L
        fun checkpoint() {
            try { caller.ensureActive() } catch (cancel: CancellationException) { throw Interrupted(cancel) }
            if (System.nanoTime() >= deadline) throw Interrupted()
        }
        val factory = object : ContextFactory() {
            override fun makeContext(): Context = super.makeContext().apply {
                setInterpretedMode(true)
                languageVersion = Context.VERSION_ES6
                instructionObserverThreshold = 10_000
                maximumInterpreterStackDepth = 128
                setClassShutter { false }
            }
            override fun observeInstructionCount(cx: Context, count: Int) = checkpoint()
        }
        val logs = StringBuilder()
        var logsTruncated = false
        try {
            val cx = factory.enterContext()
            try {
                val scope = cx.initSafeStandardObjects()
                val logger = object : BaseFunction(scope, ScriptableObject.getFunctionPrototype(scope)) {
                    override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                        checkpoint()
                        val line = (args.firstOrNull() as? CharSequence)?.toString() ?: ""
                        val remaining = 16_000 - logs.length
                        if (remaining > 0) logs.append((line + "\n").take(remaining))
                        if (line.length + 1 > remaining) logsTruncated = true
                        return Context.getUndefinedValue()
                    }
                }
                ScriptableObject.putProperty(scope, "__captureLog", logger)
                // Capture formatting functions before user code can replace them. Serialize in the
                // interpreter too, so getters/toJSON remain subject to its instruction checkpoints.
                val script = """
                    (function(log, stringify, str) {
                        delete globalThis.__captureLog;
                        function format(value) {
                            if (value === undefined || value === null) return null;
                            if (typeof value === 'object' || typeof value === 'function') {
                                var json = stringify(value);
                                return json === undefined ? str(value) : json;
                            }
                            return str(value);
                        }
                        globalThis.console = {};
                        ['log', 'info', 'warn', 'error', 'debug'].forEach(function(name) {
                            console[name] = function() {
                                log(Array.prototype.map.call(arguments, function(value) {
                                    try { return format(value); } catch (e) { return str(value); }
                                }).join(' '));
                            };
                        });
                        var result = format((0, eval)(${JsonPrimitive(code)}));
                        if (result !== null && result.length > 128000) throw new Error('JavaScript result exceeds 128000 characters');
                        return result;
                    })(__captureLog, JSON.stringify, String)
                """.trimIndent()
                checkpoint()
                val result = (cx.evaluateString(scope, script, "tool.js", 1, null) as CharSequence?)?.toString()
                checkpoint()
                buildJsonObject {
                    put("result", result?.let(::JsonPrimitive) ?: JsonNull)
                    if (logs.isNotEmpty()) put("logs", logs.toString())
                    if (logsTruncated) put("logs_truncated", true)
                }
            } finally { Context.exit() }
        } catch (stop: Interrupted) {
            stop.cancellation?.let { throw it }
            throw StError.Validation("JavaScript exceeded its 10-second instruction deadline")
        } catch (error: RhinoException) {
            caller.ensureActive()
            buildJsonObject {
                put("error", error.message.orEmpty().take(16_000))
                if (logs.isNotEmpty()) put("logs", logs.toString())
                if (logsTruncated) put("logs_truncated", true)
            }
        }
    }
}
