package com.simpletavern.runtime.host

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.simpletavern.core.api.SimpleTavernCore
import com.simpletavern.core.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Platform transport for one existing playback task. No source URI, browse catalog or playback resumption. */
internal class TaskMediaSession(private val context: Context, private val core: SimpleTavernCore) {
    private var session: MediaSession? = null
    private var owner: String? = null
    private var metadataKey: Pair<Long?, Int>? = null
    private var queueItems: List<SpeechPlaylistItem> = emptyList()

    @Suppress("DEPRECATION")
    fun update(task: TaskSnapshot?, progress: SpeechPlaybackProgress?) {
        if (task == null) { close(); return }
        if (owner != task.id) {
            close()
            val id = task.id
            val created = MediaSession(context, "SimpleTavernSpeech")
            session = created; owner = id
            created.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            created.setMediaButtonReceiver(null) // Never wake a killed process to replay old speech.
            created.setCallback(object : MediaSession.Callback() {
                override fun onPlay() = dispatch(id, ACTION_RESUME)
                override fun onPause() = dispatch(id, ACTION_PAUSE)
                override fun onStop() = dispatch(id, CoreForegroundService.ACTION_STOP_TASK)
                override fun onSeekTo(pos: Long) = dispatch(id, ACTION_SEEK, pos)
                override fun onSkipToNext() = dispatch(id, CoreForegroundService.ACTION_PLAYLIST_NEXT)
                override fun onSkipToPrevious() = dispatch(id, CoreForegroundService.ACTION_PLAYLIST_PREVIOUS)
                override fun onSkipToQueueItem(queueId: Long) {
                    if (queueId in 0..Int.MAX_VALUE.toLong()) dispatch(id, CoreForegroundService.ACTION_PLAYLIST_SKIP, queueId)
                }
                override fun onCustomAction(action: String, extras: Bundle?) {
                    if (action == CoreForegroundService.ACTION_STOP_TASK) dispatch(id, action)
                }
            }, Handler(Looper.getMainLooper()))
            launchIntent()?.let(created::setSessionActivity)
            created.isActive = true
        }
        val live = progress?.takeIf { it.taskId == task.id }
        val playlist = live?.mode == "playlist" && live.playlist.isNotEmpty()
        val items = if (playlist) live!!.playlist else emptyList()
        if (items != queueItems) {
            session!!.setQueue(items.takeIf { it.isNotEmpty() }?.mapIndexed { index, _ ->
                MediaSession.QueueItem(MediaDescription.Builder().setMediaId(index.toString())
                    .setTitle("音频 ${index + 1}").build(), index.toLong())
            })
            queueItems = items
        }
        val audio = live?.playback
        val running = task.status == TaskStatus.RUNNING
        var actions = if (task.status == TaskStatus.STOPPING) 0L else PlaybackState.ACTION_STOP
        if (running && audio?.canPause == true) actions = actions or PlaybackState.ACTION_PLAY_PAUSE or
            (if (audio.paused) PlaybackState.ACTION_PLAY else PlaybackState.ACTION_PAUSE)
        if (running && audio?.canSeek == true) actions = actions or PlaybackState.ACTION_SEEK_TO
        if (running && playlist) {
            if (live!!.segmentIndex > 0) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
            if (live.segmentIndex < live.playlist.lastIndex) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
            actions = actions or PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM
        }
        val state = when {
            task.status == TaskStatus.STOPPING -> PlaybackState.STATE_STOPPED
            audio?.paused == true -> PlaybackState.STATE_PAUSED
            running && (audio?.ready == true || live?.mode == "system" && live.phase == "playing") -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_BUFFERING
        }
        val position = audio?.positionMs?.takeIf { audio.ready } ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN
        session!!.setPlaybackState(PlaybackState.Builder().setActions(actions)
            .apply { if (playlist) setActiveQueueItemId(live!!.segmentIndex.toLong()) }
            .apply { if (task.status != TaskStatus.STOPPING) addCustomAction(CoreForegroundService.ACTION_STOP_TASK, "停止", android.R.drawable.ic_menu_close_clear_cancel) }
            .setState(state, position, if (state == PlaybackState.STATE_PLAYING) live?.playbackSpeed?.toFloat() ?: 1f else 0f,
                SystemClock.elapsedRealtime()).build())
        val key = audio?.durationMs?.takeIf { audio.ready } to (live?.segmentIndex ?: 0)
        if (metadataKey != key) {
            session!!.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_MEDIA_ID, task.id)
                .putString(MediaMetadata.METADATA_KEY_TITLE, "语音播放")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "SimpleTavern")
                .apply { key.first?.let { putLong(MediaMetadata.METADATA_KEY_DURATION, it) } }.build())
            metadataKey = key
        }
    }

    fun notification(task: TaskSnapshot, progress: SpeechPlaybackProgress?): Notification? {
        val current = session?.takeIf { owner == task.id } ?: return null
        val live = progress?.takeIf { it.taskId == task.id }
        val audio = live?.playback
        val playlist = live?.mode == "playlist" && live.playlist.isNotEmpty() && task.status == TaskStatus.RUNNING
        val text = when {
            task.status == TaskStatus.STOPPING -> "正在停止"
            audio?.paused == true -> "已暂停"
            live?.phase == "preprocessing" -> "正在整理朗读稿"
            live?.phase == "synthesizing" -> "正在合成语音"
            task.status == TaskStatus.QUEUED || audio?.ready != true && live?.mode != "system" -> "正在准备播放"
            else -> "正在播放"
        }
        val builder = Notification.Builder(context, CoreForegroundService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("语音播放").setContentText(text)
            .setSubText(if (live != null && live.segmentCount > 1) "第 ${live.segmentIndex + 1}/${live.segmentCount} 段" else "SimpleTavern")
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
        launchIntent()?.let(builder::setContentIntent)
        var count = 0
        val compactActions = mutableListOf<Int>()
        fun addAction(icon: Int, title: String, action: String, compact: Boolean) {
            val index = count
            builder.addAction(Notification.Action.Builder(Icon.createWithResource(context, icon), title,
                actionIntent(task.id, action)).build())
            count++
            if (compact && compactActions.size < 3) compactActions += index
        }
        if (playlist && live!!.segmentIndex > 0) addAction(android.R.drawable.ic_media_previous, "上一项",
            CoreForegroundService.ACTION_PLAYLIST_PREVIOUS, true)
        if (task.status == TaskStatus.RUNNING && audio?.canPause == true) {
            val paused = audio.paused
            addAction(if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (paused) "继续" else "暂停", if (paused) ACTION_RESUME else ACTION_PAUSE, true)
        }
        if (playlist && live!!.segmentIndex < live.playlist.lastIndex) addAction(android.R.drawable.ic_media_next, "下一项",
            CoreForegroundService.ACTION_PLAYLIST_NEXT, true)
        if (task.status != TaskStatus.STOPPING) {
            addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", CoreForegroundService.ACTION_STOP_TASK, true)
        }
        val style = Notification.MediaStyle().setMediaSession(current.sessionToken)
        if (compactActions.isNotEmpty()) style.setShowActionsInCompactView(*compactActions.toIntArray())
        return builder.setStyle(style).build()
    }

    /** Capture the original ID, including callbacks already queued when an old session is released. */
    private fun dispatch(id: String, action: String, positionMs: Long = 0) {
        core.scope.launch {
            try {
                val task = core.getTask(id)
                if (task.kind != TaskKind.SPEECH_PLAYBACK || task.status.isTerminal) return@launch
                when (action) {
                    ACTION_PAUSE -> core.pauseSpeechPlayback(id)
                    ACTION_RESUME -> core.resumeSpeechPlayback(id)
                    ACTION_SEEK -> core.seekSpeechPlayback(id, positionMs)
                    CoreForegroundService.ACTION_PLAYLIST_NEXT -> core.nextSpeechPlaybackItem(id)
                    CoreForegroundService.ACTION_PLAYLIST_PREVIOUS -> core.previousSpeechPlaybackItem(id)
                    CoreForegroundService.ACTION_PLAYLIST_SKIP -> core.skipSpeechPlaybackTo(id, positionMs.toInt())
                    CoreForegroundService.ACTION_STOP_TASK -> core.stopTask(id)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { Log.w("ST.MediaSession", "media command failed (${error.javaClass.simpleName})") }
        }
    }
    private fun actionIntent(id: String, action: String): PendingIntent = PendingIntent.getService(context, 0,
        Intent(context, CoreForegroundService::class.java).setAction(action)
            .setData(Uri.Builder().scheme("simpletavern").authority("playback-control").appendPath(id).appendPath(action).build())
            .putExtra(CoreForegroundService.EXTRA_TASK_ID, id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun launchIntent(): PendingIntent? = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
        PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    fun close() {
        val old = session
        session = null; owner = null; metadataKey = null; queueItems = emptyList()
        if (old != null) { try { old.isActive = false; old.setCallback(null) } finally { old.release() } }
    }
    companion object {
        const val ACTION_PAUSE = "com.simpletavern.action.PAUSE_PLAYBACK"
        const val ACTION_RESUME = "com.simpletavern.action.RESUME_PLAYBACK"
        private const val ACTION_SEEK = "seek"
    }
}
