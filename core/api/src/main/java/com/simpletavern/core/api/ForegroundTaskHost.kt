package com.simpletavern.core.api

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import com.simpletavern.core.data.TaskRegistrationHost
import com.simpletavern.core.model.StError
import com.simpletavern.core.model.TaskSnapshot
import com.simpletavern.core.model.TaskKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

data class ForegroundHostState(val foreground: Boolean = false, val draining: Boolean = false,
    val lastError: String? = null)

/** Explicit Android bridge: core:api does not depend on runtime:host. No database work during promotion. */
class ForegroundTaskHost(context: Context, private val scope: CoroutineScope) : TaskRegistrationHost {
    private val context = context.applicationContext
    private val lock = Any()
    private val launchLock = Mutex()
    private val reservations = MutableStateFlow<Set<String>>(emptySet())
    private val state = MutableStateFlow(ForegroundHostState())
    private var owner: String? = null
    private val foregroundKinds = MutableStateFlow<Set<String>>(emptySet())
    fun observeState(): StateFlow<ForegroundHostState> = state.asStateFlow()
    fun pendingRegistrations(): StateFlow<Set<String>> = reservations.asStateFlow()
    fun requiredKinds(): StateFlow<Set<String>> = foregroundKinds.asStateFlow()

    override suspend fun beforeRegister(task: TaskSnapshot) {
        val hostKind = if (task.kind == TaskKind.SPEECH_PLAYBACK) task.kind.name else "data-sync"
        synchronized(lock) {
            if (state.value.draining) throw StError.Interrupted("foreground host is stopping; start a new task later")
            reservations.value = reservations.value + task.id
        }
        // A rolled-back enclosing transaction produces no Room invalidation. Its reservation must expire.
        scope.launch { delay(30_000); registrationFailed(task.id) }
        try {
            launchLock.withLock {
                val needsLaunch = synchronized(lock) {
                    if (state.value.draining) throw StError.Interrupted("foreground host is stopping")
                    !state.value.foreground || hostKind !in foregroundKinds.value
                }
                if (needsLaunch) {
                    val promoted = CompletableDeferred<Boolean>()
                    val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                            promoted.complete(resultCode == RESULT_READY)
                        }
                    }
                    val intent = Intent().setClassName(context.packageName, SERVICE_CLASS)
                        .setAction(ACTION_ENSURE_HOST).putExtra(EXTRA_READY_RECEIVER, receiver)
                        .putExtra(EXTRA_TASK_KIND, hostKind)
                    try {
                        context.startForegroundService(intent)
                    } catch (error: Exception) {
                        synchronized(lock) { state.value = state.value.copy(lastError = "foreground start denied (${error.javaClass.simpleName})") }
                        throw StError.Unsupported("foreground service could not start; open the app and start a new task")
                    }
                    if (withTimeoutOrNull(10_000) { promoted.await() } != true) {
                        promotionFailed("foreground promotion failed or timed out")
                        throw StError.Interrupted("foreground promotion failed or timed out; task was not started")
                    }
                    foregroundKinds.value = foregroundKinds.value + hostKind
                }
                synchronized(lock) {
                    if (!state.value.foreground || state.value.draining) throw StError.Interrupted("foreground host unavailable")
                }
            }
        } catch (error: Throwable) {
            registrationFailed(task.id)
            throw error
        }
    }

    override fun registrationFailed(taskId: String) = synchronized(lock) {
        reservations.value = reservations.value - taskId
    }
    fun registrationsObserved(taskIds: Set<String>) = synchronized(lock) {
        reservations.value = reservations.value - taskIds
    }
    override fun retainHandoff(): AutoCloseable? = synchronized(lock) {
        if (!state.value.foreground || state.value.draining) return@synchronized null
        val id = "handoff:${UUID.randomUUID()}"
        reservations.value = reservations.value + id
        AutoCloseable { registrationFailed(id) }
    }
    fun promotionFailed(message: String) = synchronized(lock) {
        state.value = state.value.copy(lastError = message)
    }
    fun promoted(token: String) = synchronized(lock) {
        if (!state.value.draining) {
            owner = token
            state.value = ForegroundHostState(foreground = true)
        }
    }
    /** Called on the service's main thread. Serialized with registration so idle cannot win over a new lease. */
    fun stopIfIdle(token: String, stop: () -> Boolean): Boolean = synchronized(lock) {
        if (owner != token || reservations.value.isNotEmpty() || state.value.draining) return@synchronized false
        if (!stop()) return@synchronized false
        owner = null
        state.value = state.value.copy(foreground = false)
        foregroundKinds.value = emptySet()
        true
    }
    fun beginInterruption(token: String, message: String): Boolean = synchronized(lock) {
        if (owner != token) return@synchronized false
        state.value = ForegroundHostState(draining = true, lastError = message)
        true
    }
    fun interruptionFinished(token: String) = synchronized(lock) {
        if (owner == token) {
            owner = null
            reservations.value = emptySet()
            foregroundKinds.value = emptySet()
            state.value = state.value.copy(draining = false)
        }
    }
    fun cleanupFailed(token: String) = synchronized(lock) {
        if (owner == token) state.value = state.value.copy(lastError = "foreground cleanup incomplete; tasks remain stopped or stopping")
    }
    fun notificationsEnabled(): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() &&
            (manager.getNotificationChannel(CHANNEL_ID)?.importance ?: NotificationManager.IMPORTANCE_LOW) != NotificationManager.IMPORTANCE_NONE
    }

    companion object {
        const val SERVICE_CLASS = "com.simpletavern.runtime.host.CoreForegroundService"
        const val CHANNEL_ID = "st_core_tasks"
        const val ACTION_ENSURE_HOST = "com.simpletavern.action.ENSURE_HOST"
        const val ACTION_STOP_TASK = "com.simpletavern.action.STOP_TASK"
        const val EXTRA_TASK_ID = "taskId"
        const val EXTRA_READY_RECEIVER = "readyReceiver"
        const val EXTRA_TASK_KIND = "taskKind"
        const val RESULT_READY = 1
        const val RESULT_FAILED = 0
    }
}
