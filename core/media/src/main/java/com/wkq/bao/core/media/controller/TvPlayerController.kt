package com.wkq.bao.core.media.controller

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.database.entity.WatchHistoryEntity
import com.wkq.bao.core.media.resolver.MediaResolver
import com.wkq.bao.core.media.resolver.PlaybackSource
import com.wkq.bao.core.media.service.TvPlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 电视播放与遥控控制器
 */
class TvPlayerController(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val mediaResolver = MediaResolver(context)
    private val watchHistoryDao = AppDatabase.getInstance(context).watchHistoryDao()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    var player: Player? = null
        private set

    private var currentSeriesId: Long = 0L
    private var currentSeasonId: Long = 0L
    private var currentEpisodeId: Long = 0L
    private var progressTrackingJob: Job? = null
    private var pendingPlayback: PlaybackRequest? = null
    private var activeSource: PlaybackSource? = null
    private var activeResultCallback: ((Boolean, String) -> Unit)? = null
    private val failedNasSourceIds = mutableSetOf<Long>()

    private data class PlaybackRequest(
        val seriesId: Long,
        val seasonId: Long,
        val episodeId: Long,
        val onResult: ((Boolean, String) -> Unit)?
    )

    /**
     * 连接后台 PlaybackService
     */
    fun connect(onConnected: ((Player) -> Unit)? = null) {
        val sessionToken = SessionToken(context, ComponentName(context, TvPlaybackService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            runCatching { controllerFuture?.get() }.getOrNull()?.let { controller ->
                player = controller
                setupPlayerListeners(controller)
                onConnected?.invoke(controller)
                pendingPlayback?.let { request ->
                    pendingPlayback = null
                    playEpisode(request.seriesId, request.seasonId, request.episodeId, request.onResult)
                }
            }
        }, MoreExecutors.directExecutor())
    }

    private fun setupPlayerListeners(p: Player?) {
        p?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> startProgressTracking()
                    Player.STATE_ENDED -> {
                        stopProgressTracking()
                        saveWatchProgress(completed = true)
                    }
                    Player.STATE_IDLE -> stopProgressTracking()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    startProgressTracking()
                } else {
                    saveWatchProgress(completed = false)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                fallbackFromPlaybackError()
            }
        })
    }

    /**
     * 播放指定集并自动恢复历史播放进度
     */
    fun playEpisode(seriesId: Long, seasonId: Long, episodeId: Long, onResult: ((Boolean, String) -> Unit)? = null) {
        if (player == null) {
            pendingPlayback = PlaybackRequest(seriesId, seasonId, episodeId, onResult)
            return
        }
        currentSeriesId = seriesId
        currentSeasonId = seasonId
        currentEpisodeId = episodeId
        activeSource = null
        activeResultCallback = onResult
        failedNasSourceIds.clear()

        scope.launch {
            when (val source = mediaResolver.resolve(episodeId)) {
                is PlaybackSource.Local -> {
                    activeSource = source
                    startPlay(source.uri.toString(), source.title)
                    onResult?.invoke(true, source.location.name)
                }
                is PlaybackSource.NasStream -> {
                    activeSource = source
                    startPlay(source.uri.toString(), source.title)
                    onResult?.invoke(true, "NAS")
                }
                is PlaybackSource.Unavailable -> {
                    onResult?.invoke(false, source.reason)
                }
            }
        }
    }

    /** 本地盘或当前 NAS 播放失败时，使用未失败的 NAS 来源继续播放。 */
    private fun fallbackFromPlaybackError() {
        val failedSource = activeSource ?: return
        val failedEpisodeId = currentEpisodeId
        val resumePosition = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
        val callback = activeResultCallback
        if (failedSource is PlaybackSource.NasStream) {
            failedNasSourceIds += failedSource.nasSourceId
        }
        activeSource = null // 等待新来源解析，防止同一次错误重复触发回退。

        scope.launch {
            when (val fallback = mediaResolver.resolve(
                episodeId = failedEpisodeId,
                allowLocal = false,
                excludedNasSourceIds = failedNasSourceIds
            )) {
                is PlaybackSource.NasStream -> {
                    activeSource = fallback
                    startPlay(fallback.uri.toString(), fallback.title, resumePosition)
                    callback?.invoke(true, "NAS")
                }
                is PlaybackSource.Unavailable -> {
                    callback?.invoke(false, "播放源回退失败: ${fallback.reason}")
                }
                is PlaybackSource.Local -> Unit
            }
        }
    }

    private suspend fun startPlay(uriString: String, title: String, resumePosition: Long? = null) {
        val p = player ?: return
        val mediaItem = MediaItem.Builder()
            .setUri(uriString)
            .setMediaId(currentEpisodeId.toString())
            .build()

        p.setMediaItem(mediaItem)

        // 查找历史进度并恢复
        val history = watchHistoryDao.getHistoryByEpisodeId(currentEpisodeId)
        val initialPosition = resumePosition ?: history
            ?.takeIf { !it.completed && it.positionMs > 5000 }
            ?.positionMs
        if (initialPosition != null && initialPosition > 0L) {
            p.seekTo(initialPosition)
        }

        p.prepare()
        p.play()
    }

    // 遥控器操作快捷方法
    fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    fun seekForward(ms: Long = 10000) {
        val p = player ?: return
        val duration = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        p.seekTo((p.currentPosition + ms).coerceAtMost(duration))
    }

    fun seekRewind(ms: Long = 10000) {
        val p = player ?: return
        p.seekTo((p.currentPosition - ms).coerceAtLeast(0))
    }

    private fun startProgressTracking() {
        progressTrackingJob?.cancel()
        progressTrackingJob = scope.launch {
            while (isActive) {
                delay(15000) // 每 15 秒保存一次进度
                saveWatchProgress(completed = false)
            }
        }
    }

    private fun stopProgressTracking() {
        progressTrackingJob?.cancel()
        progressTrackingJob = null
    }

    private fun saveWatchProgress(completed: Boolean) {
        val p = player ?: return
        if (currentEpisodeId == 0L) return
        // 在切集前固定归属，避免异步写库时将上一集进度写进下一集。
        val seriesId = currentSeriesId
        val seasonId = currentSeasonId
        val episodeId = currentEpisodeId
        val pos = if (completed) 0L else p.currentPosition
        val duration = p.duration.coerceAtLeast(0L)

        scope.launch(Dispatchers.IO) {
            watchHistoryDao.saveHistory(
                WatchHistoryEntity(
                    seriesId = seriesId,
                    seasonId = seasonId,
                    episodeId = episodeId,
                    positionMs = pos,
                    durationMs = duration,
                    completed = completed,
                    lastPlayedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun release() {
        stopProgressTracking()
        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }
        player = null
        pendingPlayback = null
        activeSource = null
        activeResultCallback = null
    }
}
