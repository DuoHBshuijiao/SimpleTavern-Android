package com.simpletavern.runtime.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Platform host for background generation/shell — not a business UI.
 * Stop action cancels the bound task via broadcast handled by the app process.
 */
class CoreForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_TASK -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID)
                sendBroadcast(Intent(ACTION_STOP_TASK).putExtra(EXTRA_TASK_ID, taskId).setPackage(packageName))
                if (taskId == null) stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val title = intent?.getStringExtra(EXTRA_TITLE) ?: "SimpleTavern"
                val text = intent?.getStringExtra(EXTRA_TEXT) ?: "任务运行中"
                val taskId = intent?.getStringExtra(EXTRA_TASK_ID)
                startForeground(NOTIFICATION_ID, buildNotification(title, text, taskId))
            }
        }
        return START_STICKY
    }

    private fun buildNotification(title: String, text: String, taskId: String?): Notification {
        ensureChannel()
        val stopIntent = Intent(this, CoreForegroundService::class.java).apply {
            action = ACTION_STOP_TASK
            putExtra(EXTRA_TASK_ID, taskId)
        }
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .addAction(0, "停止", stopPi)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "后台任务", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "st_core_tasks"
        const val NOTIFICATION_ID = 42
        const val ACTION_STOP_TASK = "com.simpletavern.action.STOP_TASK"
        const val EXTRA_TASK_ID = "taskId"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"

        fun start(context: Context, title: String, text: String, taskId: String?) {
            val i = Intent(context, CoreForegroundService::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_TASK_ID, taskId)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CoreForegroundService::class.java))
        }
    }
}
