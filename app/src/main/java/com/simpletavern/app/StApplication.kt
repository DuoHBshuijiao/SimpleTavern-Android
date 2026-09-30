package com.simpletavern.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.simpletavern.core.api.SimpleTavernCore
import com.simpletavern.runtime.host.CoreForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class StApplication : Application() {
    lateinit var core: SimpleTavernCore
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val taskId = intent?.getStringExtra(CoreForegroundService.EXTRA_TASK_ID) ?: return
            scope.launch {
                runCatching { core.stopTask(taskId) }
                CoreForegroundService.stop(this@StApplication)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        core = SimpleTavernCore.get(this)
        val filter = IntentFilter(CoreForegroundService.ACTION_STOP_TASK)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stopReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stopReceiver, filter)
        }
    }
}
