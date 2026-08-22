package com.wkq.bao.feature.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.database.entity.WatchHistoryEntity
import com.wkq.bao.core.media.resolver.MediaResolver
import com.wkq.bao.core.media.resolver.PlaybackSource
import com.wkq.bao.feature.app.databinding.ActivityPlayerBinding
import com.wkq.base.activity.BaseActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 全屏流媒体播放器页面 (支持 D-Pad 遥控器、OSD 控制层与断点历史保存)
 */
class PlayerActivity : BaseActivity<ActivityPlayerBinding>() {

    companion object {
        private const val EXTRA_SERIES_ID = "extra_series_id"
        private const val EXTRA_EPISODE_ID = "extra_episode_id"
        private const val EXTRA_TITLE = "extra_title"

        fun start(context: Context, seriesId: Long, episodeId: Long, title: String) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_SERIES_ID, seriesId)
                putExtra(EXTRA_EPISODE_ID, episodeId)
                putExtra(EXTRA_TITLE, title)
            }
            context.startActivity(intent)
        }
    }

    private var exoPlayer: ExoPlayer? = null
    private val database by lazy { AppDatabase.getInstance(this) }
    private val mediaResolver by lazy { MediaResolver(this) }

    private var seriesId: Long = 0L
    private var seasonId: Long = 1L
    private var episodeId: Long = 0L
    private var mediaTitle: String = ""

    private var osdHideJob: Job? = null
    private var progressTrackerJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUi()
    }

    override fun initView() {
        seriesId = intent.getLongExtra(EXTRA_SERIES_ID, 0L)
        episodeId = intent.getLongExtra(EXTRA_EPISODE_ID, 0L)
        mediaTitle = intent.getStringExtra(EXTRA_TITLE) ?: "视频播放"

        binding.tvTitle.text = mediaTitle

        // 初始化 ExoPlayer
        exoPlayer = ExoPlayer.Builder(this).build().apply {
            binding.playerView.player = this
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        saveWatchProgress(isCompleted = true)
                        playNextEpisode()
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    binding.btnPlayPause.text = if (isPlaying) "暂停" else "播放"
                }
            })
        }

        // 按钮交互
        binding.btnPlayPause.setOnClickListener {
            exoPlayer?.let {
                if (it.isPlaying) it.pause() else it.play()
            }
            resetOsdTimer()
        }

        binding.btnRewind.setOnClickListener {
            exoPlayer?.let { it.seekTo((it.currentPosition - 10000).coerceAtLeast(0)) }
            resetOsdTimer()
        }

        binding.btnFastForward.setOnClickListener {
            exoPlayer?.let { it.seekTo((it.currentPosition + 10000).coerceAtMost(it.duration)) }
            resetOsdTimer()
        }

        // 倍速切换 (0.5x -> 1.0x -> 1.25x -> 1.5x -> 2.0x)
        val speeds = listOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f)
        var speedIndex = 1
        binding.btnSpeed.setOnClickListener {
            speedIndex = (speedIndex + 1) % speeds.size
            val targetSpeed = speeds[speedIndex]
            exoPlayer?.setPlaybackSpeed(targetSpeed)
            binding.btnSpeed.text = "${targetSpeed}x"
            resetOsdTimer()
        }

        binding.btnNextEpisode.setOnClickListener {
            playNextEpisode()
        }

        // SeekBar 进度条拖拽交互
        binding.seekProgress.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val player = exoPlayer ?: return
                    val targetMs = (progress.toFloat() / 1000f * player.duration).toLong()
                    binding.tvCurrentTime.text = formatTime(targetMs)
                }
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                osdHideJob?.cancel()
            }

            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                val player = exoPlayer ?: return
                val progress = seekBar?.progress ?: 0
                val targetMs = (progress.toFloat() / 1000f * player.duration).toLong()
                player.seekTo(targetMs)
                resetOsdTimer()
            }
        })
    }

    override fun initData() {
        loadAndPlay(episodeId)
        startProgressTracker()
    }

    private fun loadAndPlay(targetEpisodeId: Long) {
        this.episodeId = targetEpisodeId
        lifecycleScope.launch {
            val source = mediaResolver.resolve(targetEpisodeId)
            when (source) {
                is PlaybackSource.Local -> {
                    binding.tvSourceBadge.text = "本地离线"
                    binding.tvSourceBadge.setBackgroundResource(com.wkq.bao.feature.res.R.drawable.bg_badge_local)
                    playUri(source.uri.toString())
                }
                is PlaybackSource.NasStream -> {
                    binding.tvSourceBadge.text = "NAS 局域网"
                    binding.tvSourceBadge.setBackgroundResource(com.wkq.bao.feature.res.R.drawable.bg_badge_nas)
                    playUri(source.uri.toString())
                }
                is PlaybackSource.Unavailable -> {
                    binding.tvSourceBadge.text = "演示片源"
                    binding.tvSourceBadge.setBackgroundResource(com.wkq.bao.feature.res.R.drawable.bg_badge_nas)
                    Toast.makeText(this@PlayerActivity, "已为您加载高清演示片源", Toast.LENGTH_SHORT).show()
                    playUri("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4")
                }
            }
        }
    }

    private fun playUri(uri: String) {
        val player = exoPlayer ?: return
        val mediaItem = MediaItem.fromUri(Uri.parse(uri))
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = true

        // 恢复断点进度
        lifecycleScope.launch {
            val history = database.watchHistoryDao().getHistoryByEpisodeId(episodeId)
            if (history != null && !history.completed && history.positionMs > 0) {
                player.seekTo(history.positionMs)
                Toast.makeText(this@PlayerActivity, "已为您恢复上次观看进度", Toast.LENGTH_SHORT).show()
            }
        }
        showOsd()
    }

    private fun startProgressTracker() {
        progressTrackerJob?.cancel()
        progressTrackerJob = lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                val player = exoPlayer ?: continue
                if (player.isPlaying) {
                    val current = player.currentPosition
                    val total = player.duration.coerceAtLeast(1)
                    val progress = ((current.toFloat() / total) * 1000).toInt()

                    binding.seekProgress.progress = progress
                    binding.tvCurrentTime.text = formatTime(current)
                    binding.tvTotalTime.text = formatTime(total)

                    // 每隔 10 秒持久化一次进度
                    if ((current / 1000) % 10L == 0L) {
                        saveWatchProgress(isCompleted = false)
                    }
                }
            }
        }
    }

    private fun saveWatchProgress(isCompleted: Boolean) {
        val player = exoPlayer ?: return
        lifecycleScope.launch {
            val entity = WatchHistoryEntity(
                seriesId = seriesId,
                seasonId = seasonId,
                episodeId = episodeId,
                positionMs = if (isCompleted) 0L else player.currentPosition,
                durationMs = player.duration.coerceAtLeast(0L),
                completed = isCompleted
            )
            database.watchHistoryDao().saveHistory(entity)
        }
    }

    private fun playNextEpisode() {
        lifecycleScope.launch {
            val currentEp = database.mediaDao().getEpisodeById(episodeId)
            if (currentEp == null) {
                Toast.makeText(this@PlayerActivity, "已经是最后一集了", Toast.LENGTH_SHORT).show()
                return@launch
            }

            // 1. 同季查找下一集 (episodeNumber + 1)
            var nextEp = database.mediaDao().getEpisodeByNumber(currentEp.seasonId, currentEp.episodeNumber + 1)
            
            // 2. 跨季查找：若当前季播完，查找下一季的第 1 集
            if (nextEp == null) {
                val allSeasons = database.mediaDao().getSeasonsSync(seriesId)
                val currentSeason = allSeasons.find { it.id == currentEp.seasonId }
                if (currentSeason != null) {
                    val nextSeason = allSeasons.find { it.seasonNumber == currentSeason.seasonNumber + 1 }
                    if (nextSeason != null) {
                        nextEp = database.mediaDao().getEpisodeByNumber(nextSeason.id, 1)
                    }
                }
            }

            if (nextEp != null && nextEp.seriesId == seriesId) {
                binding.tvTitle.text = "${mediaTitle.substringBefore("-")} - ${nextEp.title}"
                seasonId = nextEp.seasonId
                loadAndPlay(nextEp.id)
                Toast.makeText(this@PlayerActivity, "正在播放: ${nextEp.title}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@PlayerActivity, "已经是全剧最后一集了", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 遥控器按键处理
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (binding.layoutOsd.visibility == View.VISIBLE) {
                    hideOsd()
                } else {
                    showOsd()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                exoPlayer?.let { it.seekTo((it.currentPosition - 10000).coerceAtLeast(0)) }
                showOsd()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                exoPlayer?.let { it.seekTo((it.currentPosition + 10000).coerceAtMost(it.duration)) }
                showOsd()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                exoPlayer?.let { if (it.isPlaying) it.pause() else it.play() }
                showOsd()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (binding.layoutOsd.visibility == View.VISIBLE) {
                    hideOsd()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showOsd() {
        binding.layoutOsd.visibility = View.VISIBLE
        binding.btnPlayPause.requestFocus()
        resetOsdTimer()
    }

    private fun hideOsd() {
        binding.layoutOsd.visibility = View.GONE
        osdHideJob?.cancel()
    }

    private fun resetOsdTimer() {
        osdHideJob?.cancel()
        osdHideJob = lifecycleScope.launch {
            delay(5000)
            binding.layoutOsd.visibility = View.GONE
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format("%02d:%02d", min, sec)
    }

    private fun hideSystemUi() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onDestroy() {
        super.onDestroy()
        saveWatchProgress(isCompleted = false)
        progressTrackerJob?.cancel()
        osdHideJob?.cancel()
        exoPlayer?.release()
        exoPlayer = null
    }
}
