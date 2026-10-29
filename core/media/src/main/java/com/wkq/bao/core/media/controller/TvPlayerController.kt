package com.wkq.bao.core.media.controller

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.wkq.bao.core.media.service.PlaybackSessionCommands
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.database.entity.WatchHistoryEntity
import com.wkq.bao.core.media.resolver.MediaResolver
import com.wkq.bao.core.media.resolver.LocalSubtitleResolver
import com.wkq.bao.core.media.resolver.NasSubtitleResolver
import com.wkq.bao.core.media.resolver.PlaybackSource
import com.wkq.bao.core.media.service.TvPlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private val failedLocalUris = mutableSetOf<String>()
    private var lastFailure = "source"
    private val mediaResolver = MediaResolver(context)
    private val subtitleResolver = LocalSubtitleResolver(context)
    private val nasSubtitleResolver = NasSubtitleResolver(context)
    private var subtitleJob: Job? = null
    private val watchHistoryDao = AppDatabase.getInstance(context).watchHistoryDao()

    var onNextEpisodeRequested: (() -> Unit)? = null

    private var controllerFuture: ListenableFuture<MediaController>? = null
    var player: Player? = null
        private set

    private var currentSeriesId: Long = 0L
    private var currentSeasonId: Long = 0L
    private var currentEpisodeId: Long = 0L
    private var progressTrackingJob: Job? = null
    private var playbackJob: Job? = null
    private var historyWriteJob: Job? = null
    private val historyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var playbackRequestId = 0L
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
        controllerFuture = MediaController.Builder(context, sessionToken)
            .setConnectionHints(Bundle().apply { putBoolean(PlaybackSessionCommands.UI_ACTIVE, true) })
            .setListener(object : MediaController.Listener {
                override fun onCustomCommand(
                    controller: MediaController, command: SessionCommand, args: Bundle
                ): ListenableFuture<SessionResult> {
                    val handler = onNextEpisodeRequested
                    if (command.customAction == PlaybackSessionCommands.NEXT_EPISODE && handler != null) {
                        handler()
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
                }
            }).buildAsync()
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
        }, ContextCompat.getMainExecutor(context))
    }

    fun setPlaybackControlsActive(active: Boolean) {
        (player as? MediaController)?.sendCustomCommand(
            SessionCommand(PlaybackSessionCommands.UI_ACTIVE, Bundle.EMPTY),
            Bundle().apply { putBoolean(PlaybackSessionCommands.EXTRA_ACTIVE, active) }
        )
    }

    private fun setupPlayerListeners(p: Player?) {
        p?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> if (p.isPlaying) startProgressTracking()
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
                    stopProgressTracking()
                    saveWatchProgress(completed = p.playbackState == Player.STATE_ENDED)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                lastFailure = com.wkq.bao.core.media.resolver.PlaybackFailure.classify(error.errorCode, error)
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
        saveWatchProgress(completed = player?.playbackState == Player.STATE_ENDED)
        playbackJob?.cancel()
        val requestId = ++playbackRequestId
        activeSource = null
        player?.pause()
        player?.clearMediaItems()
        stopProgressTracking()
        currentSeriesId = seriesId
        currentSeasonId = seasonId
        currentEpisodeId = episodeId
        activeResultCallback = onResult
        failedNasSourceIds.clear()
        failedLocalUris.clear()
        lastFailure = "source"

        playbackJob = scope.launch {
            try {
                val indexedEpisode = AppDatabase.getInstance(context).mediaDao().getEpisodeById(episodeId)
                if (indexedEpisode == null || indexedEpisode.seriesId != seriesId) {
                    if (requestId == playbackRequestId) onResult?.invoke(false, "Invalid playback target")
                    return@launch
                }
                if (requestId != playbackRequestId) return@launch
                currentSeasonId = indexedEpisode.seasonId
                when (val source = mediaResolver.resolve(episodeId)) {
                    is PlaybackSource.Local -> {
                        if (requestId != playbackRequestId) return@launch
                        activeSource = source
                        if (!startPlay(source.uri.toString(), source.title, requestId)) return@launch
                        onResult?.invoke(true, source.location.name)
                    }
                    is PlaybackSource.NasStream -> {
                        if (requestId != playbackRequestId) return@launch
                        activeSource = source
                        if (!startPlay(source.uri.toString(), source.title, requestId)) return@launch
                        onResult?.invoke(true, "NAS")
                    }
                    is PlaybackSource.Unavailable -> {
                        if (requestId == playbackRequestId) onResult?.invoke(false, source.reason)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (requestId == playbackRequestId) {
                    activeSource = null
                    onResult?.invoke(false, "Unable to load playback source")
                }
            }
        }
    }

    /** 本地盘或当前 NAS 播放失败时，使用未失败的 NAS 来源继续播放。 */
    private fun fallbackFromPlaybackError() {
        if (player?.currentMediaItem?.mediaId != currentEpisodeId.toString()) return
        val failedSource = activeSource ?: return
        val failedEpisodeId = currentEpisodeId
        val requestId = playbackRequestId
        val resumePosition = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
        val callback = activeResultCallback
        if (failedSource is PlaybackSource.NasStream) failedNasSourceIds += failedSource.nasSourceId
        if (failedSource is PlaybackSource.Local) failedLocalUris += failedSource.uri.toString()
        activeSource = null // 等待新来源解析，防止同一次错误重复触发回退。
        val excludedSources = failedNasSourceIds.toSet()

        playbackJob?.cancel()
        playbackJob = scope.launch {
            try {
                when (val fallback = mediaResolver.resolve(
                    episodeId = failedEpisodeId,
                    allowLocal = true,
                    excludedNasSourceIds = excludedSources,
                    excludedLocalUris = failedLocalUris.toSet()
                )) {
                    is PlaybackSource.NasStream -> {
                        if (requestId != playbackRequestId) return@launch
                        activeSource = fallback
                        if (!startPlay(fallback.uri.toString(), fallback.title, requestId, resumePosition)) return@launch
                        callback?.invoke(true, "NAS")
                    }
                    is PlaybackSource.Unavailable -> {
                        if (requestId == playbackRequestId) callback?.invoke(false, lastFailure)
                    }
                    is PlaybackSource.Local -> {
                        if (requestId != playbackRequestId) return@launch
                        activeSource = fallback
                        if (startPlay(fallback.uri.toString(), fallback.title, requestId, resumePosition)) {
                            callback?.invoke(true, fallback.location.name)
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (requestId == playbackRequestId) {
                    activeSource = null
                    callback?.invoke(false, "Unable to switch playback source")
                }
            }
        }
    }

    private suspend fun startPlay(uriString: String, title: String, requestId: Long, resumePosition: Long? = null): Boolean {
        subtitleJob?.cancel()
        val episodeId = currentEpisodeId
        // 先读取历史，再更换播放器内容，避免挂起期间把上一集进度写入下一集。
        historyWriteJob?.join()
        val history = watchHistoryDao.getHistoryByEpisodeId(episodeId)
        val subtitles = subtitleResolver.resolve(Uri.parse(uriString))
        if (requestId != playbackRequestId) return false
        val p = player ?: return false
        val series = runCatching { AppDatabase.getInstance(context).mediaDao().getSeriesById(currentSeriesId) }.getOrNull()
        val artworkUri = series?.posterUri?.takeIf { it.isNotBlank() } ?: series?.backdropUri?.takeIf { it.isNotBlank() }
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(title)
            .setDisplayTitle(title)
            .setExtras(Bundle().apply { putBoolean("is_movie", series?.type == "MOVIE") })
        if (series != null) {
            metadataBuilder.setArtist(series.title)
        }
        if (!artworkUri.isNullOrBlank()) {
            runCatching { metadataBuilder.setArtworkUri(Uri.parse(artworkUri)) }
        }
        val mediaItem = MediaItem.Builder()
            .setUri(uriString)
            .setMediaId(episodeId.toString())
            .setMediaMetadata(metadataBuilder.build())
            .setSubtitleConfigurations(subtitles)
            .build()

        // 将内容与起播位置一起提交，避免 MediaController 跨进程准备时覆盖单独的 seek。
        val initialPosition = resumePosition ?: history
            ?.takeIf { !it.completed && it.positionMs > 5000 }
            ?.positionMs
        p.setMediaItem(mediaItem, initialPosition?.coerceAtLeast(0L) ?: 0L)

        p.prepare()
        p.play()
        val source = activeSource as? PlaybackSource.NasStream
        if (source != null) subtitleJob = scope.launch {
            val remoteSubtitles = nasSubtitleResolver.resolve(source)
            if (remoteSubtitles.isEmpty()) return@launch
            // 先开始视频，目录请求不能延迟首帧；加载后保留当前进度和暂停状态。
            while (requestId == playbackRequestId && p.playbackState == Player.STATE_BUFFERING) delay(100)
            if (requestId != playbackRequestId || p.currentMediaItem?.mediaId != episodeId.toString() ||
                p.playbackState != Player.STATE_READY) return@launch
            val position = p.currentPosition
            val shouldPlay = p.playWhenReady
            p.setMediaItem(mediaItem.buildUpon().setSubtitleConfigurations(remoteSubtitles).build(), position)
            p.prepare()
            p.playWhenReady = shouldPlay
        }
        return true
    }

    // 遥控器操作快捷方法
    fun togglePlayPause() {
        val p = player ?: return
        if (p.playWhenReady) p.pause() else play()
    }

    fun play() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        if (p.playbackState == Player.STATE_ENDED) p.seekToDefaultPosition()
        p.play()
    }

    fun seekForward(ms: Long = 15000) {
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
        if (currentEpisodeId == 0L || p.currentMediaItem?.mediaId != currentEpisodeId.toString()) return
        if (p.duration <= 0L) return
        // 在切集前固定归属，避免异步写库时将上一集进度写进下一集。
        val seriesId = currentSeriesId
        val seasonId = currentSeasonId
        val episodeId = currentEpisodeId
        val pos = if (completed) 0L else p.currentPosition
        val duration = p.duration.coerceAtLeast(0L)

        val previousWrite = historyWriteJob
        historyWriteJob = historyScope.launch {
            previousWrite?.join()
            try {
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
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // 空间不足等写库失败不能使正在播放的视频崩溃，日志不包含媒体信息。
                Log.w("TvPlayerController", "Cannot save progress: ${error.javaClass.simpleName}")
            }
        }
    }

    fun release() {
        setPlaybackControlsActive(false)
        onNextEpisodeRequested = null
        subtitleJob?.cancel()
        saveWatchProgress(completed = player?.playbackState == Player.STATE_ENDED)
        playbackRequestId++
        playbackJob?.cancel()
        playbackJob = null
        stopProgressTracking()
        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }
        player = null
        pendingPlayback = null
        activeSource = null
        activeResultCallback = null
        val pendingWrite = historyWriteJob
        historyScope.launch {
            pendingWrite?.join()
            historyScope.cancel()
        }
    }

    /**
     * 获取指定集的所有可用播放源列表
     */
    suspend fun getAvailableSources(episodeId: Long): List<PlaybackSource> {
        return mediaResolver.resolveAllSources(episodeId)
    }

    /**
     * 获取当前生效的播放源
     */
    fun getCurrentSource(): PlaybackSource? = activeSource

    /**
     * 手动切换到指定播放源，保持当前播放进度与全量播放状态
     */
    fun switchToSource(source: PlaybackSource, onResult: ((Boolean, String) -> Unit)? = null) {
        val p = player ?: return
        val currentPos = p.currentPosition.coerceAtLeast(0L)
        val requestId = ++playbackRequestId
        playbackJob?.cancel()
        activeSource = source
        failedNasSourceIds.clear()
        failedLocalUris.clear()
        activeResultCallback = onResult

        playbackJob = scope.launch {
            try {
                val sourceLabel = when (source) {
                    is PlaybackSource.Local -> source.location.name
                    is PlaybackSource.NasStream -> "NAS"
                    is PlaybackSource.Unavailable -> return@launch
                }
                val uriString = when (source) {
                    is PlaybackSource.Local -> source.uri.toString()
                    is PlaybackSource.NasStream -> source.uri.toString()
                    else -> return@launch
                }
                val title = when (source) {
                    is PlaybackSource.Local -> source.title
                    is PlaybackSource.NasStream -> source.title
                    else -> ""
                }
                if (startPlay(uriString, title, requestId, currentPos)) {
                    onResult?.invoke(true, sourceLabel)
                } else {
                    onResult?.invoke(false, "切换播放源失败")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (requestId == playbackRequestId) {
                    onResult?.invoke(false, "source")
                }
            }
        }
    }

}
