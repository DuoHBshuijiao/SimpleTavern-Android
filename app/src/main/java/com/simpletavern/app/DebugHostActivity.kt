package com.simpletavern.app

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.simpletavern.core.api.SimpleTavernCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Minimal debug host — NOT a product UI.
 * Only available to launch the process and show core health for manual diagnosis.
 * Codex owns real Compose screens.
 */
class DebugHostActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) {
            // Release builds must not expose diagnostic surface beyond empty launch.
            finish()
            return
        }
        val text = TextView(this).apply {
            setPadding(48, 48, 48, 48)
            textSize = 14f
            text = "SimpleTavern Core debug host\nloading…"
        }
        setContentView(ScrollView(this).apply {
            addView(LinearLayout(this@DebugHostActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text)
            })
        })
        val core = (application as StApplication).core
        CoroutineScope(Dispatchers.Main).launch {
            val chats = runCatching { core.listChats().size }.getOrElse { -1 }
            val sandboxes = runCatching { core.listSandboxes().size }.getOrElse { -1 }
            val pending = runCatching { core.pendingApprovalCount() }.getOrElse { -1 }
            text.text = buildString {
                appendLine("SimpleTavern Core debug host")
                appendLine("DEBUG only — no product UI")
                appendLine("chats=$chats sandboxes=$sandboxes pendingApprovals=$pending")
                appendLine()
                appendLine("Public entry: SimpleTavernCore")
                appendLine("Docs: docs/android/CORE_API.md")
            }
        }
    }
}
