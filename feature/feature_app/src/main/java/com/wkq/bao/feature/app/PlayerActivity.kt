package com.wkq.bao.feature.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.common.Tracks
import com.wkq.base.activity.BaseActivity
import com.wkq.bao.core.media.controller.TvPlayerController
import com.wkq.bao.feature.app.databinding.ActivityPlayerBinding
import com.wkq.bao.feature.app.utils.TvFocusHelper
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
    private val viewModel: PlayerViewModel by viewModels { PlayerViewModel.Factory(this) }
    private var seriesId = 0L
    private var seasonId = 0L
    private var episodeId = 0L
    private var mediaTitle = ""
    private var osdHideJob: Job? = null
    private var progressTrackerJob: Job? = null
    private var activePlayer: Player? = null
    private var initialEpisodeLoaded = false
    private var subtitlesEnabled = true

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            binding.progressBuffering.visibility = if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            if (playbackState == Player.STATE_READY) hidePlaybackError()
            if (playbackState == Player.STATE_ENDED) playNextEpisode()
        }

        override fun onTracksChanged(tracks: Tracks) {
            renderSubtitleButton(tracks.containsType(C.TRACK_TYPE_TEXT))
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
        binding.btnSubtitles.setOnClickListener { toggleSubtitles() }
        binding.btnPlayerRetry.setOnClickListener { loadEpisode(episodeId) }
        listOf(
            binding.btnPlayPause,
            binding.btnRewind,
            binding.btnFastForward,
            binding.btnNextEpisode,
            binding.btnSpeed,
            binding.btnSubtitles,
            binding.btnPlayerRetry
        ).forEach { button ->
            button.backgroundTintList = null
            TvFocusHelper.applyFocusScale(button)
        }
        TvFocusHelper.applyFocusScale(binding.seekProgress)
        TvFocusHelper.requestInitialFocus(binding.root, binding.btnPlayPause)

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
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        val resolvedSeasonId = state.initialSeasonId ?: return@collect
                        if (!initialEpisodeLoaded) {
                            initialEpisodeLoaded = true
                            seasonId = resolvedSeasonId
                            loadEpisode(episodeId)
                        }
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is PlayerEvent.PlayNext -> {
                                seasonId = event.episode.seasonId
                                binding.tvTitle.text = event.episode.title
                                loadEpisode(event.episode.id)
                            }
                            PlayerEvent.LastEpisodeReached -> {
                                Toast.makeText(this@PlayerActivity, com.wkq.bao.feature.res.R.string.player_last_episode, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }
        viewModel.initialize(seasonId, episodeId)
        startProgressTracker()
    }

    private fun loadEpisode(targetEpisodeId: Long) {
        episodeId = targetEpisodeId
        hidePlaybackError()
        binding.progressBuffering.visibility = View.VISIBLE
        playerController.playEpisode(seriesId, seasonId, targetEpisodeId) { success, message ->
            if (success) renderSourceBadge(message) else showPlaybackError()
        }
        showOsd()
    }

    private fun toggleSubtitles() {
        val player = activePlayer ?: return
        subtitlesEnabled = !subtitlesEnabled
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesEnabled)
            .build()
        renderSubtitleButton(player.currentTracks.containsType(C.TRACK_TYPE_TEXT))
        resetOsdTimer()
    }

    private fun renderSubtitleButton(hasSubtitleTrack: Boolean) {
        binding.btnSubtitles.isEnabled = hasSubtitleTrack
        binding.btnSubtitles.setText(
            when {
                !hasSubtitleTrack -> com.wkq.bao.feature.res.R.string.player_subtitles_none
                subtitlesEnabled -> com.wkq.bao.feature.res.R.string.player_subtitles_on
                else -> com.wkq.bao.feature.res.R.string.player_subtitles_off
            }
        )
    }

    private fun showPlaybackError() {
        binding.progressBuffering.visibility = View.GONE
        binding.layoutPlayerError.visibility = View.VISIBLE
        binding.btnPlayerRetry.requestFocus()
        osdHideJob?.cancel()
    }

    private fun hidePlaybackError() {
        binding.layoutPlayerError.visibility = View.GONE
    }

    private fun renderSourceBadge(source: String) {
        val (labelRes, backgroundRes) = when (source) {
            "INTERNAL_STORAGE" -> com.wkq.bao.feature.res.R.string.storage_internal to com.wkq.bao.feature.res.R.drawable.bg_badge_local
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
        viewModel.playNextEpisode(seriesId, episodeId)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // OSD 可见时交给已聚焦的控件处理方向键和确认键，保证倍速、下一集等按钮可达。
        if (binding.layoutOsd.visibility == View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                    playerController.togglePlayPause()
                    resetOsdTimer()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    playNextEpisode()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    hideOsd()
                    return true
                }
            }
            return super.onKeyDown(keyCode, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                showOsd()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> { playerController.seekRewind(); showOsd(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { playerController.seekForward(); showOsd(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> { playerController.togglePlayPause(); showOsd(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { playNextEpisode(); return true }
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
        binding.playerView.requestFocus()
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
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds % 60)
        else String.format("%02d:%02d", minutes, seconds % 60)
    }

    private fun hideSystemUi() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onStop() {
        activePlayer?.pause()
        super.onStop()
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
