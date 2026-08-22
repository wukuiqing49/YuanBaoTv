package com.wkq.bao.feature.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import com.wkq.base.activity.BaseActivity
import com.wkq.bao.core.media.controller.TvPlayerController
import com.wkq.bao.feature.app.databinding.ActivityPlayerBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 全屏播放页只显示并控制 MediaSession 中的唯一播放器实例。 */
class PlayerActivity : BaseActivity<ActivityPlayerBinding>() {

    companion object {
        private const val EXTRA_SERIES_ID = "extra_series_id"
        private const val EXTRA_SEASON_ID = "extra_season_id"
        private const val EXTRA_EPISODE_ID = "extra_episode_id"
        private const val EXTRA_TITLE = "extra_title"

        fun start(context: Context, seriesId: Long, seasonId: Long, episodeId: Long, title: String) {
            context.startActivity(Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_SERIES_ID, seriesId)
                putExtra(EXTRA_SEASON_ID, seasonId)
                putExtra(EXTRA_EPISODE_ID, episodeId)
                putExtra(EXTRA_TITLE, title)
            })
        }

        fun start(context: Context, seriesId: Long, episodeId: Long, title: String) {
            start(context, seriesId, 0L, episodeId, title)
        }
    }

    private lateinit var playerController: TvPlayerController
    private var seriesId = 0L
    private var seasonId = 0L
    private var episodeId = 0L
    private var mediaTitle = ""
    private var osdHideJob: Job? = null
    private var progressTrackerJob: Job? = null
    private var activePlayer: Player? = null

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) playNextEpisode()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            binding.btnPlayPause.text = getString(
                if (isPlaying) com.wkq.bao.feature.res.R.string.btn_pause
                else com.wkq.bao.feature.res.R.string.btn_play_now
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUi()
    }

    override fun initView() {
        seriesId = intent.getLongExtra(EXTRA_SERIES_ID, 0L)
        seasonId = intent.getLongExtra(EXTRA_SEASON_ID, 0L)
        episodeId = intent.getLongExtra(EXTRA_EPISODE_ID, 0L)
        mediaTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { getString(com.wkq.bao.feature.res.R.string.player_default_title) }
        binding.tvTitle.text = mediaTitle

        playerController = TvPlayerController(this, lifecycleScope)
        playerController.connect { player ->
            activePlayer = player
            binding.playerView.player = player
            player.addListener(playerListener)
        }

        binding.btnPlayPause.setOnClickListener { playerController.togglePlayPause(); resetOsdTimer() }
        binding.btnRewind.setOnClickListener { playerController.seekRewind(); resetOsdTimer() }
        binding.btnFastForward.setOnClickListener { playerController.seekForward(); resetOsdTimer() }
        binding.btnNextEpisode.setOnClickListener { playNextEpisode() }

        val speeds = listOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f)
        var speedIndex = 1
        binding.btnSpeed.setOnClickListener {
            speedIndex = (speedIndex + 1) % speeds.size
            activePlayer?.setPlaybackSpeed(speeds[speedIndex])
            binding.btnSpeed.text = getString(com.wkq.bao.feature.res.R.string.player_speed_format, speeds[speedIndex])
            resetOsdTimer()
        }

        binding.seekProgress.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = activePlayer?.duration?.takeIf { it > 0 } ?: return
                    binding.tvCurrentTime.text = formatTime((progress.toFloat() / 1000f * duration).toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = osdHideJob?.cancel().let { }

            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                val duration = activePlayer?.duration?.takeIf { it > 0 } ?: return
                activePlayer?.seekTo((seekBar?.progress ?: 0).toFloat().div(1000f).times(duration).toLong())
                resetOsdTimer()
            }
        })
    }

    override fun initData() {
        lifecycleScope.launch {
            if (seasonId == 0L) {
                seasonId = com.wkq.bao.core.database.AppDatabase
                    .getInstance(this@PlayerActivity)
                    .mediaDao()
                    .getEpisodeById(episodeId)
                    ?.seasonId
                    ?: 0L
            }
            loadEpisode(episodeId)
        }
        startProgressTracker()
    }

    private fun loadEpisode(targetEpisodeId: Long) {
        episodeId = targetEpisodeId
        playerController.playEpisode(seriesId, seasonId, targetEpisodeId) { success, message ->
            if (success) renderSourceBadge(message)
            else Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
        showOsd()
    }

    private fun renderSourceBadge(source: String) {
        val (labelRes, backgroundRes) = when (source) {
            "TF_CARD" -> com.wkq.bao.feature.res.R.string.badge_tf_card to com.wkq.bao.feature.res.R.drawable.bg_badge_local
            "USB_DRIVE" -> com.wkq.bao.feature.res.R.string.badge_usb_drive to com.wkq.bao.feature.res.R.drawable.bg_badge_local
            "NAS" -> com.wkq.bao.feature.res.R.string.badge_nas_stream to com.wkq.bao.feature.res.R.drawable.bg_badge_nas
            else -> com.wkq.bao.feature.res.R.string.badge_downloaded to com.wkq.bao.feature.res.R.drawable.bg_badge_local
        }
        binding.tvSourceBadge.setText(labelRes)
        binding.tvSourceBadge.setBackgroundResource(backgroundRes)
    }

    private fun startProgressTracker() {
        progressTrackerJob?.cancel()
        progressTrackerJob = lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                val player = activePlayer ?: continue
                if (!player.isPlaying) continue
                val duration = player.duration.takeIf { it > 0 } ?: continue
                binding.seekProgress.progress = ((player.currentPosition.toFloat() / duration) * 1000).toInt().coerceIn(0, 1000)
                binding.tvCurrentTime.text = formatTime(player.currentPosition)
                binding.tvTotalTime.text = formatTime(duration)
            }
        }
    }

    private fun playNextEpisode() {
        lifecycleScope.launch {
            val database = com.wkq.bao.core.database.AppDatabase.getInstance(this@PlayerActivity)
            val current = database.mediaDao().getEpisodeById(episodeId)
            val nextInSeason = current?.let { database.mediaDao().getEpisodeByNumber(it.seasonId, it.episodeNumber + 1) }
            val next = nextInSeason ?: current?.let { currentEpisode ->
                val seasons = database.mediaDao().getSeasonsSync(seriesId)
                val currentIndex = seasons.indexOfFirst { it.id == currentEpisode.seasonId }
                seasons.getOrNull(currentIndex + 1)?.let { nextSeason ->
                    database.mediaDao().getEpisodeByNumber(nextSeason.id, 1)
                }
            }
            if (next == null || next.seriesId != seriesId) {
                Toast.makeText(this@PlayerActivity, com.wkq.bao.feature.res.R.string.player_last_episode, Toast.LENGTH_SHORT).show()
                return@launch
            }
            seasonId = next.seasonId
            binding.tvTitle.text = next.title
            loadEpisode(next.id)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (binding.layoutOsd.visibility == View.VISIBLE) hideOsd() else showOsd()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> { playerController.seekRewind(); showOsd(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { playerController.seekForward(); showOsd(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> { playerController.togglePlayPause(); showOsd(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { playNextEpisode(); return true }
            KeyEvent.KEYCODE_BACK -> if (binding.layoutOsd.visibility == View.VISIBLE) { hideOsd(); return true }
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

    private fun formatTime(milliseconds: Long): String {
        val seconds = milliseconds / 1000
        return String.format("%02d:%02d", seconds / 60, seconds % 60)
    }

    private fun hideSystemUi() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onDestroy() {
        activePlayer?.removeListener(playerListener)
        binding.playerView.player = null
        progressTrackerJob?.cancel()
        osdHideJob?.cancel()
        playerController.release()
        super.onDestroy()
    }
}
