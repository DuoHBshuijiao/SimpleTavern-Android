package com.simpletavern.runtime.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.ResultReceiver
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.simpletavern.core.api.ForegroundTaskHost
import com.simpletavern.core.api.SimpleTavernCore
import com.simpletavern.core.model.TaskKind
import com.simpletavern.core.model.TaskSnapshot
import com.simpletavern.core.model.TaskStatus
import com.simpletavern.core.model.TaskTerminalReason
import com.simpletavern.core.model.SpeechPlaybackProgress
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Owns platform lifetime, not business work. No automatic restart or task replay after process death. */
class CoreForegroundService : Service() {
    private val token = UUID.randomUUID().toString()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var core: SimpleTavernCore
    private lateinit var manager: NotificationManager
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var media: TaskMediaSession
    private var mediaEnabled = true
    private var observer: Job? = null
    private var idle: Job? = null
    private var wakeRenewal: Job? = null
    private var lastStartId = 0
    private var closing = false
    private var foreground = false
    private var active = emptyList<TaskSnapshot>()
    private var notifiedCount = -1
    private val notified = mutableMapOf<String, NotificationKey>()
    private data class NotificationKey(val kind: TaskKind, val status: TaskStatus, val phase: String? = null,
        val paused: Boolean? = null, val canPause: Boolean? = null, val segmentIndex: Int? = null, val media: Boolean = false)

    override fun onCreate() {
        super.onCreate()
        core = SimpleTavernCore.get(this)
        media = TaskMediaSession(this, core)
        manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "后台任务", NotificationManager.IMPORTANCE_LOW))
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SimpleTavern:CoreTasks").apply { setReferenceCounted(false) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val receiver = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(ForegroundTaskHost.EXTRA_READY_RECEIVER, ResultReceiver::class.java)
            else @Suppress("DEPRECATION") intent?.getParcelableExtra<ResultReceiver>(ForegroundTaskHost.EXTRA_READY_RECEIVER)
        if (closing || core.taskHost.observeState().value.draining) {
            receiver?.send(ForegroundTaskHost.RESULT_FAILED, null)
            closing = true
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (intent != null && intent.action in setOf(ACTION_STOP_TASK, TaskMediaSession.ACTION_PAUSE, TaskMediaSession.ACTION_RESUME,
                ACTION_PLAYLIST_NEXT, ACTION_PLAYLIST_PREVIOUS)) {
            intent.getStringExtra(EXTRA_TASK_ID)?.let { taskId -> core.scope.launch {
                try { when (intent.action) {
                    TaskMediaSession.ACTION_PAUSE -> core.pauseSpeechPlayback(taskId)
                    TaskMediaSession.ACTION_RESUME -> core.resumeSpeechPlayback(taskId)
                    ACTION_PLAYLIST_NEXT -> core.nextSpeechPlaybackItem(taskId)
                    ACTION_PLAYLIST_PREVIOUS -> core.previousSpeechPlaybackItem(taskId)
                    else -> core.stopTask(taskId)
                } }
                catch (error: Exception) { Log.w(TAG, "task control failed (${error.javaClass.simpleName})") }
            } }
            if (!foreground) { closing = true; stopSelfResult(startId) }
            return START_NOT_STICKY
        }
        // A ResultReceiver acknowledgement is sent only after Android accepted foreground promotion.
        try {
            val kinds = core.taskHost.requiredKinds().value + listOfNotNull(intent?.getStringExtra(ForegroundTaskHost.EXTRA_TASK_KIND))
            val media = TaskKind.SPEECH_PLAYBACK.name in kinds
            val data = kinds.any { it != TaskKind.SPEECH_PLAYBACK.name } || !media
            val types = (if (media) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0) or
                (if (data) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
            ServiceCompat.startForeground(this, NOTIFICATION_ID, summary(active.size), if (Build.VERSION.SDK_INT >= 29) types else 0)
            foreground = true
            core.taskHost.promoted(token)
            receiver?.send(ForegroundTaskHost.RESULT_READY, null)
            observeTasks()
        } catch (error: Exception) {
            core.taskHost.promotionFailed("foreground promotion failed (${error.javaClass.simpleName})")
            receiver?.send(ForegroundTaskHost.RESULT_FAILED, null)
            Log.w(TAG, "foreground promotion failed (${error.javaClass.simpleName})")
            interrupt(TaskTerminalReason.HOST_UNAVAILABLE, "foreground promotion failed")
        }
        return START_NOT_STICKY
    }

    private fun observeTasks() {
        if (observer != null) return
        observer = serviceScope.launch {
            try {
                core.onProcessStart()
                combine(core.observeActiveTasks(), core.taskHost.pendingRegistrations(), core.observeSpeechPlayback()) { tasks, pending, playback -> Triple(tasks, pending, playback) }
                    .collect { (tasks, pending, playback) ->
                        if (closing) return@collect
                        active = tasks
                        core.taskHost.registrationsObserved(tasks.map { it.id }.toSet())
                        updateNotifications(tasks, playback)
                        updateWakeLock(tasks, pending)
                        idle?.cancel()
                        idle = null
                        if (tasks.isEmpty() && pending.isEmpty()) {
                            // A short handoff grace lets generation enqueue automatic summary/state jobs.
                            idle = serviceScope.launch {
                                delay(5_000)
                                if (active.isEmpty()) core.taskHost.stopIfIdle(token) {
                                    val stopped = stopSelfResult(lastStartId)
                                    if (stopped) { closing = true; removeNotifications(); releaseWakeLock() }
                                    stopped
                                }
                            }
                        }
                    }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { interrupt(TaskTerminalReason.HOST_UNAVAILABLE, "foreground task observation failed") }
        }
    }

    private fun updateNotifications(tasks: List<TaskSnapshot>, playback: SpeechPlaybackProgress?) {
        try { if (mediaEnabled) media.update(tasks.firstOrNull { it.kind == TaskKind.SPEECH_PLAYBACK }, playback) }
        catch (error: Exception) {
            mediaEnabled = false
            Log.w(TAG, "media session unavailable (${error.javaClass.simpleName})")
            runCatching { media.close() } // Keep ordinary task/stop notifications usable.
        }
        val ids = tasks.map { it.id }.toSet()
        (notified.keys - ids).forEach { id -> manager.cancel(id, TASK_NOTIFICATION_ID); notified.remove(id) }
        if (notifiedCount != tasks.size) {
            manager.notify(NOTIFICATION_ID, summary(tasks.size))
            notifiedCount = tasks.size
        }
        for (task in tasks) {
            val live = playback?.takeIf { task.kind == TaskKind.SPEECH_PLAYBACK && it.taskId == task.id }
            val mediaNotification = if (task.kind == TaskKind.SPEECH_PLAYBACK && mediaEnabled) try { media.notification(task, live) }
                catch (error: Exception) {
                    mediaEnabled = false
                    Log.w(TAG, "media notification unavailable (${error.javaClass.simpleName})")
                    runCatching { media.close() }
                    null
                } else null
            val key = NotificationKey(task.kind, task.status, live?.phase, live?.playback?.paused,
                live?.playback?.canPause, live?.segmentIndex, mediaNotification != null)
            if (notified[task.id] == key) continue
            if (mediaNotification != null) {
                manager.notify(task.id, TASK_NOTIFICATION_ID, mediaNotification)
                notified[task.id] = key
                continue
            }
            val stopIntent = Intent(this, CoreForegroundService::class.java).setAction(ACTION_STOP_TASK)
                .setData(Uri.Builder().scheme("simpletavern").authority("stop-task").appendPath(task.id).build())
                .putExtra(EXTRA_TASK_ID, task.id)
            // Extras do not distinguish PendingIntent identities; task-specific data does.
            val stop = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val builder = base().setContentTitle(taskTitle(task.kind)).setContentText(statusText(task.status))
                .setSubText("任务 ${task.id.take(8)}").setGroup(GROUP_KEY)
                .setStyle(NotificationCompat.BigTextStyle().bigText(statusText(task.status)))
            if (task.status != TaskStatus.STOPPING) builder.addAction(0, "停止此任务", stop)
            manager.notify(task.id, TASK_NOTIFICATION_ID, builder.build())
            notified[task.id] = key
        }
    }

    private fun base(): NotificationCompat.Builder = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW).setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).apply {
            packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
                setContentIntent(PendingIntent.getActivity(this@CoreForegroundService, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
        }
    private fun summary(count: Int): Notification = base().setContentTitle("SimpleTavern")
        .setContentText(if (count == 0) "正在准备或结束后台任务" else "$count 个后台任务；展开可逐项停止")
        .setGroup(GROUP_KEY).setGroupSummary(true).build()
    private fun taskTitle(kind: TaskKind): String = when (kind) {
        TaskKind.CHAT_GENERATION -> "对话生成"
        TaskKind.GROUP_GENERATION -> "群聊生成"
        TaskKind.MEMORY_SUMMARY -> "长期记忆总结"
        TaskKind.MVU_UPDATE -> "状态维护"
        TaskKind.KG_UPDATE -> "图谱维护"
        TaskKind.SANDBOX_SHELL -> "沙箱命令"
        TaskKind.SANDBOX_FILE -> "沙箱文件操作"
        TaskKind.FILE_COPY -> "文件复制"
        TaskKind.FILE_EXPORT -> "沙箱产物导出"
        TaskKind.SPEECH_SYNTHESIS -> "语音合成"
        TaskKind.SPEECH_VOICE_DESIGN -> "音色设计"
        TaskKind.SPEECH_VOICE_CLONE -> "音色克隆"
        TaskKind.SPEECH_PLAYBACK -> "语音播放"
        TaskKind.IMPORT -> "数据导入"
        TaskKind.BACKUP -> "备份处理"
        TaskKind.RESTORE -> "资料恢复"
        else -> "后台任务"
    }
    private fun statusText(status: TaskStatus): String = when (status) {
        TaskStatus.QUEUED -> "排队中"
        TaskStatus.AWAITING_APPROVAL -> "等待确认；请打开应用处理，或停止此任务"
        TaskStatus.STOPPING -> "正在停止并保存已完成的部分"
        else -> "运行中"
    }

    private fun updateWakeLock(tasks: List<TaskSnapshot>, pending: Set<String>) {
        val executing = tasks.any { it.status in setOf(TaskStatus.RUNNING, TaskStatus.STOPPING) } || pending.any { it.startsWith("handoff:") }
        if (!executing) { releaseWakeLock(); return }
        if (wakeRenewal != null) return
        wakeRenewal = serviceScope.launch {
            try {
                while (isActive && !closing) {
                    wakeLock.acquire(10 * 60_000L)
                    delay(5 * 60_000L)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                interrupt(TaskTerminalReason.HOST_UNAVAILABLE, "foreground wake lock unavailable")
            }
        }
    }
    private fun releaseWakeLock() {
        wakeRenewal?.cancel(); wakeRenewal = null
        runCatching { if (wakeLock.isHeld) wakeLock.release() }
    }
    private fun removeNotifications() {
        runCatching { media.close() }
        notified.keys.toList().forEach { runCatching { manager.cancel(it, TASK_NOTIFICATION_ID) } }
        notified.clear()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 gives only a few seconds. Do not wait for worker checkpoint/join before stopSelf.
        interrupt(TaskTerminalReason.HOST_TIMEOUT, "foreground dataSync time limit reached")
    }
    private fun interrupt(reason: TaskTerminalReason, message: String) {
        if (closing) return
        closing = true
        val owned = core.taskHost.beginInterruption(token, message)
        releaseWakeLock()
        removeNotifications()
        stopSelf()
        if (owned) core.signalDataTaskHostInterruption(reason)
        if (owned) core.scope.launch {
            try {
                core.onProcessStart()
                // Mark every queued writer first so a pump cannot start its successor during teardown.
                val tasks = core.tasks.requestStopAll(reason)
                coroutineScope { tasks.forEach { task -> launch {
                    try { core.stopTask(task.id, reason) }
                    catch (error: Exception) { Log.w(TAG, "host task cleanup failed (${error.javaClass.simpleName})") }
                } } }
            } catch (error: Exception) {
                Log.w(TAG, "host cleanup failed (${error.javaClass.simpleName})")
            } finally {
                // A failed cleanup must not re-admit work while old writers are still alive.
                if (runCatching { core.tasks.nonTerminal().isEmpty() }.getOrDefault(false)) core.taskHost.interruptionFinished(token)
                else core.taskHost.cleanupFailed(token)
            }
        }
    }
    override fun onDestroy() {
        if (!closing) interrupt(TaskTerminalReason.HOST_UNAVAILABLE, "foreground service destroyed")
        releaseWakeLock()
        removeNotifications()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = ForegroundTaskHost.CHANNEL_ID
        const val NOTIFICATION_ID = 42
        const val ACTION_STOP_TASK = ForegroundTaskHost.ACTION_STOP_TASK
        const val ACTION_PLAYLIST_NEXT = "com.simpletavern.action.PLAYLIST_NEXT"
        const val ACTION_PLAYLIST_PREVIOUS = "com.simpletavern.action.PLAYLIST_PREVIOUS"
        const val ACTION_PLAYLIST_SKIP = "com.simpletavern.action.PLAYLIST_SKIP"
        const val EXTRA_TASK_ID = ForegroundTaskHost.EXTRA_TASK_ID
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        private const val TASK_NOTIFICATION_ID = 1
        private const val GROUP_KEY = "st_active_tasks"
        private const val TAG = "ST.CoreHost"
        @Suppress("UNUSED_PARAMETER")
        fun start(context: Context, title: String, text: String, taskId: String?) {
            context.startForegroundService(Intent(context, CoreForegroundService::class.java)
                .setAction(ForegroundTaskHost.ACTION_ENSURE_HOST).putExtra(EXTRA_TASK_ID, taskId))
        }
        fun stop(context: Context) { context.stopService(Intent(context, CoreForegroundService::class.java)) }
    }
}
