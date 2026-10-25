package com.wkq.bao.core.media.service

import android.app.PendingIntent
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.ForwardingPlayer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.wkq.bao.core.media.smb.SchemeDataSource
import com.wkq.bao.core.media.subtitle.PlaybackSubtitleParserFactory

/**
 * 电视端核心媒体后台播放服务 (MediaSessionService)
 */
@OptIn(UnstableApi::class)
class TvPlaybackService : MediaSessionService() {

    companion object {
        private const val TAG = "TvPlaybackService"
    }

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null

    private var sessionPlayer: NavigationSessionPlayer? = null

    private val voiceInterruption = VoicePlaybackInterruption()
    private var playbackUi: MediaSession.ControllerInfo? = null
    private var controlsActive = false
    private var receiverRegistered = false

    private val miTvReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                voiceInterruption.clear()
                controlsActive = false
                sessionPlayer?.refreshCommands()
                player?.pause()
                return
            }
            if (intent.action != "com.xiaomi.mitv.player.control" || !controlsActive) return
            val p = player?.takeIf { it.mediaItemCount > 0 } ?: return
            val operation = runCatching { MiTvPlaybackOperation.parse(intent.getStringExtra("operate")) }
                .getOrNull() ?: return
            // 本机 seek_* 参数为整数秒，episode 的相对值为集数，不能混用单位。
            when (operation) {
                MiTvPlaybackOperation.SEEK -> {
                    val absolute = runCatching {
                        if (intent.hasExtra("seek_absolute_pos")) intent.getIntExtra("seek_absolute_pos", 0).toLong() * 1000 else null
                    }.getOrNull()
                    val relative = runCatching {
                        if (intent.hasExtra("seek_relative_pos")) intent.getIntExtra("seek_relative_pos", 0).toLong() * 1000 else null
                    }.getOrNull()
                    val target = absolute ?: relative?.let { p.currentPosition + it } ?: return
                    val duration = p.duration.takeIf { it > 0 } ?: return
                    p.seekTo(target.coerceIn(0, duration))
                    Log.d(TAG, "MiTV seek applied")
                    return
                }
                MiTvPlaybackOperation.EPISODE -> {
                    if (runCatching { intent.getIntExtra("seek_relative_pos", 0) }.getOrDefault(0) == 1) requestNext()
                    return
                }
                MiTvPlaybackOperation.PLAYRATE -> {
                    val speed = runCatching { intent.getFloatExtra("speed", Float.NaN) }.getOrDefault(Float.NaN)
                    if (speed in listOf(0.5f, 1f, 1.25f, 1.5f, 2f)) {
                        p.setPlaybackSpeed(speed)
                        getSharedPreferences("playback_preferences", MODE_PRIVATE).edit().putFloat("speed", speed).apply()
                        Log.d(TAG, "MiTV speed applied=$speed")
                    }
                    return
                }
                MiTvPlaybackOperation.FAST_FORWARD -> {
                    p.seekForward()
                    Log.d(TAG, "MiTV fast forward applied")
                    return
                }
                MiTvPlaybackOperation.REWIND -> {
                    p.seekBack()
                    Log.d(TAG, "MiTV rewind applied")
                    return
                }
                MiTvPlaybackOperation.REPLAY -> {
                    p.seekTo(0L)
                    p.play()
                    Log.d(TAG, "MiTV replay applied")
                    return
                }
                MiTvPlaybackOperation.STOP -> {
                    p.stop()
                    Log.d(TAG, "MiTV stop applied")
                    return
                }
                else -> Unit
            }
            val cause = runCatching { intent.getStringExtra("cause") }.getOrNull()
            Log.d(TAG, "MiTV control operation=$operation cause=$cause")
            when (operation) {
                MiTvPlaybackOperation.PAUSE -> {
                    val mediaId = p.currentMediaItem?.mediaId
                    if (cause == "voicecontrol") {
                        // 语音唤醒临时打断：仅在当前正在播放时，记录临时打断状态，以便小爱退出后自动恢复
                        voiceInterruption.pause(mediaId, p.playWhenReady)
                    } else {
                        // 用户明确叫暂停或遥控器操作，清除打断恢复标记
                        voiceInterruption.clear()
                    }
                    p.pause()
                }
                MiTvPlaybackOperation.RESUME -> {
                    val mediaId = p.currentMediaItem?.mediaId
                    val shouldResume = if (cause == "voicecontrol") {
                        // 小爱录音或对话结束退出：仅当刚才由语音临时打断且当时正在播放时，才自动恢复
                        voiceInterruption.resume(mediaId)
                    } else {
                        // 用户明确叫“继续播放”，始终恢复
                        voiceInterruption.clear()
                        true
                    }
                    if (shouldResume) {
                        if (p.playbackState == Player.STATE_IDLE) p.prepare()
                        if (p.playbackState == Player.STATE_ENDED) p.seekToDefaultPosition()
                        p.play()
                        Log.d(TAG, "MiTV resume applied (cause=$cause)")
                    } else {
                        Log.d(TAG, "Ignored automatic voicecontrol resume to protect user pause state")
                    }
                }
                else -> Unit
            }
        }
    }

    private fun isOwnController(controller: MediaSession.ControllerInfo): Boolean =
        controller.uid == applicationInfo.uid && controller.packageName == packageName

    /** 让标准 skipToNext 与页面按钮共用数据库导航，改用 ForwardingPlayer 杜绝 SimpleBasePlayer 状态断言崩溃。 */
    private inner class NavigationSessionPlayer(player: Player) : ForwardingPlayer(player) {
        fun refreshCommands() {
            // ForwardingPlayer 动态读取 availableCommands，无需调用破坏性 invalidateState
        }

        override fun getAvailableCommands(): Player.Commands {
            val commands = super.getAvailableCommands().buildUpon()
                .remove(Player.COMMAND_SEEK_TO_NEXT).remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            if (canRequestNext()) {
                commands.add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            }
            return commands.build()
        }

        override fun isCommandAvailable(command: Int): Boolean {
            if (command == Player.COMMAND_SEEK_TO_NEXT || command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) {
                return canRequestNext()
            }
            return super.isCommandAvailable(command)
        }

        override fun seekToNext() {
            if (canRequestNext()) {
                requestNext()
            } else {
                super.seekToNext()
            }
        }

        override fun seekToNextMediaItem() {
            if (canRequestNext()) {
                requestNext()
            } else {
                super.seekToNextMediaItem()
            }
        }
    }

    private fun requestNext(): ListenableFuture<SessionResult> {
        val session = mediaSession
        val ui = playbackUi
        if (!canRequestNext() || session == null || ui == null) {
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }
        voiceInterruption.clear()
        Log.d(TAG, "Next episode requested")
        return session.sendCustomCommand(ui, SessionCommand(PlaybackSessionCommands.NEXT_EPISODE, Bundle.EMPTY), Bundle.EMPTY)
    }

    private fun canRequestNext(): Boolean = controlsActive && playbackUi != null &&
        player?.currentMediaItem?.mediaMetadata?.extras?.getBoolean("is_movie", true) == false

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            Log.d(
                TAG,
                "MediaController connecting: pkg=${controller.packageName}, uid=${controller.uid}, isTrusted=${controller.isTrusted}"
            )
            // 外部控制器只获得基础播控权限，不能替换媒体或读取文件地址。
            val commands = if (isOwnController(controller)) {
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
            } else Player.Commands.Builder().addAll(
                Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_AUDIO_ATTRIBUTES, Player.COMMAND_GET_VOLUME
            ).build()
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
            if (isOwnController(controller)) {
                sessionCommands.add(SessionCommand(PlaybackSessionCommands.UI_ACTIVE, Bundle.EMPTY))
            }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(commands)
                .setAvailableSessionCommands(sessionCommands.build())
                .build()
        }

        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            if (isOwnController(controller) && controller.connectionHints.getBoolean(PlaybackSessionCommands.UI_ACTIVE)) {
                playbackUi = controller
                controlsActive = true
                sessionPlayer?.refreshCommands()
            }
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            if (playbackUi == controller) {
                controlsActive = false
                playbackUi = null
                sessionPlayer?.refreshCommands()
                voiceInterruption.clear()
                player?.pause()
            }
        }

        @Suppress("DEPRECATION")
        override fun onPlayerCommandRequest(
            session: MediaSession, controller: MediaSession.ControllerInfo, playerCommand: Int
        ): Int {
            Log.d(TAG, "Player command=$playerCommand from=${controller.packageName}")
            if (playerCommand == Player.COMMAND_PLAY_PAUSE || playerCommand == Player.COMMAND_STOP ||
                playerCommand == Player.COMMAND_SET_MEDIA_ITEM || playerCommand == Player.COMMAND_CHANGE_MEDIA_ITEMS) {
                voiceInterruption.clear()
            }
            return SessionResult.RESULT_SUCCESS
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == PlaybackSessionCommands.UI_ACTIVE &&
                isOwnController(controller) && playbackUi == controller) {
                controlsActive = args.getBoolean(PlaybackSessionCommands.EXTRA_ACTIVE)
                Log.d(TAG, "Playback UI active=$controlsActive")
                sessionPlayer?.refreshCommands()
                if (!controlsActive) {
                    voiceInterruption.clear()
                    player?.pause()
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                30_000,
                120_000,
                2_500,
                5_000
            )
            .setTargetBufferBytes(64 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val mediaSourceFactory = DefaultMediaSourceFactory(SchemeDataSource.Factory(this))
            .setSubtitleParserFactory(
                PlaybackSubtitleParserFactory(
                    getSharedPreferences("playback_preferences", MODE_PRIVATE)
                )
            )
        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setSeekForwardIncrementMs(15_000)
            .setSeekBackIncrementMs(10_000)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player = exoPlayer
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                Log.d(TAG, "Playback playWhenReady=$playWhenReady reason=$reason state=${exoPlayer.playbackState}")
            }
            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                Log.d(TAG, "Playback suppression=$playbackSuppressionReason")
            }
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                voiceInterruption.clear()
            }
        })
        // 监听小爱同学及系统电视播控广播；不强制要求发送方具备系统签名权限，兼容不同系统版本的小爱服务。
        ContextCompat.registerReceiver(
            this, miTvReceiver, IntentFilter("com.xiaomi.mitv.player.control").apply {
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            null, null, ContextCompat.RECEIVER_EXPORTED
        )
        receiverRegistered = true

        val sessionActivityPendingIntent = packageManager
            ?.getLaunchIntentForPackage(packageName)
            ?.let { sessionIntent ->
                PendingIntent.getActivity(
                    this,
                    0,
                    sessionIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            }

        val navigationPlayer = NavigationSessionPlayer(exoPlayer)
        sessionPlayer = navigationPlayer
        val sessionBuilder = MediaSession.Builder(this, navigationPlayer)
            .setCallback(sessionCallback)
        if (sessionActivityPendingIntent != null) {
            sessionBuilder.setSessionActivity(sessionActivityPendingIntent)
        }

        mediaSession = sessionBuilder.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 旧的系统媒体按键 PendingIntent 可能在应用重装/会话销毁后再次唤起空服务。
        // 没有可恢复媒体时立即停止，避免 startForegroundService 的超时崩溃。
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON && player?.mediaItemCount == 0) {
            Log.d(TAG, "Ignore stale media button: no loaded media")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        if (receiverRegistered) unregisterReceiver(miTvReceiver)
        receiverRegistered = false
        controlsActive = false
        playbackUi = null
        voiceInterruption.clear()
        mediaSession?.run {
            release()
            player.release()
            mediaSession = null
        }
        sessionPlayer = null
        player = null
        super.onDestroy()
    }
}
