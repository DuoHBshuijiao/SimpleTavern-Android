package com.simpletavern.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug-only command receiver. Guarded by BuildConfig.DEBUG.
 * Release builds ignore commands.
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val cmd = intent.getStringExtra("cmd") ?: return
        val app = context.applicationContext as? StApplication ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                when (cmd) {
                    "status" -> Log.i(TAG, "tasks=${app.core.recentTasks(10)}")
                    "create_sandbox" -> {
                        val id = app.core.createSandbox(intent.getStringExtra("name") ?: "sb")
                        Log.i(TAG, "sandbox=$id")
                    }
                    else -> Log.w(TAG, "unknown cmd $cmd")
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object { private const val TAG = "StDebug" }
}
