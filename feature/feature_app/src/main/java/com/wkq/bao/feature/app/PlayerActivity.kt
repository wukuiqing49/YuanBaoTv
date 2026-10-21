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
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackGroup
import androidx.media3.ui.DefaultTrackNameProvider
import androidx.appcompat.app.AlertDialog
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
    private var isSeeking = false
    private var restoredState: Bundle? = null
    private var pendingRestorePosition: Long? = null
    private var restorePaused = false
    private var trackDialog: AlertDialog? = null
    private val playbackPreferences by lazy { getSharedPreferences("playback_preferences", MODE_PRIVATE) }
    private val speeds = listOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f)

    private data class TrackOption(val group: TrackGroup, val index: Int, val label: String, val selected: Boolean)

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            binding.progressBuffering.visibility = if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            if (playbackState == Player.STATE_READY) {
                pendingRestorePosition?.takeIf { activePlayer?.currentMediaItem?.mediaId == episodeId.toString() }?.let { position ->
                    pendingRestorePosition = null
                    val duration = activePlayer?.duration?.takeIf { it > 0L } ?: Long.MAX_VALUE
                    activePlayer?.seekTo(position.coerceAtMost(duration))
                    if (restorePaused) activePlayer?.pause()
                }
                hidePlaybackError()
                renderProgress()
                resetOsdTimer()
            }
            if (playbackState == Player.STATE_ENDED) {
                val mode = playbackPreferences.getInt("auto_next", 2)
                if (mode == 0 || viewModel.uiState.value.isMovie) {
                    Toast.makeText(this@PlayerActivity, com.wkq.bao.feature.res.R.string.player_finished, Toast.LENGTH_SHORT).show()
                } else viewModel.playNextEpisode(seriesId, episodeId, allowCrossSeason = mode == 2)
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            subtitlesEnabled = activePlayer?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) != true
            renderSubtitleButton(tracks.containsType(C.TRACK_TYPE_TEXT))
            binding.btnAudioTracks.isEnabled = tracks.containsType(C.TRACK_TYPE_AUDIO)
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            renderSpeed(playbackParameters.speed)
        }

        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
            subtitlesEnabled = C.TRACK_TYPE_TEXT !in parameters.disabledTrackTypes
            renderSubtitleButton(activePlayer?.currentTracks?.containsType(C.TRACK_TYPE_TEXT) == true)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            binding.btnPlayPause.text = getString(
                if (isPlaying) com.wkq.bao.feature.res.R.string.btn_pause
                else com.wkq.bao.feature.res.R.string.btn_play_now
            )
            if (isPlaying) resetOsdTimer() else osdHideJob?.cancel()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        restoredState = savedInstanceState
        pendingRestorePosition = savedInstanceState?.takeIf { it.containsKey("playback_position") }
            ?.getLong("playback_position")?.takeIf { it >= 0L }
        restorePaused = savedInstanceState?.getBoolean("playback_paused") == true
        super.onCreate(savedInstanceState)
        hideSystemUi()
        applySubtitleSize()
    }

    override fun initView() {
        seriesId = intent.getLongExtra(EXTRA_SERIES_ID, 0L)
        seasonId = restoredState?.getLong(EXTRA_SEASON_ID) ?: intent.getLongExtra(EXTRA_SEASON_ID, 0L)
        episodeId = restoredState?.getLong(EXTRA_EPISODE_ID) ?: intent.getLongExtra(EXTRA_EPISODE_ID, 0L)
        mediaTitle = (restoredState?.getString(EXTRA_TITLE) ?: intent.getStringExtra(EXTRA_TITLE)).orEmpty()
            .ifBlank { getString(com.wkq.bao.feature.res.R.string.player_default_title) }
        binding.tvTitle.text = mediaTitle
        binding.layoutBottomPanel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateSubtitlePosition() }
        binding.playerView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateSubtitlePosition() }
        binding.playerView.setOnClickListener {
            if (binding.layoutOsd.visibility == View.VISIBLE) hideOsd() else showOsd()
        }

        playerController = TvPlayerController(this, lifecycleScope)
        playerController.connect { player ->
            activePlayer = player
            binding.playerView.player = player
            player.addListener(playerListener)
            subtitlesEnabled = playbackPreferences.getBoolean("subtitles_enabled", true)
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesEnabled)
                .setPreferredAudioLanguage(playbackPreferences.getString("audio_language", null))
                .setPreferredTextLanguage(playbackPreferences.getString("subtitle_language", null))
                .build()
            player.setPlaybackSpeed(playbackPreferences.getFloat("speed", 1f).takeIf { it in speeds } ?: 1f)
            renderSpeed(player.playbackParameters.speed)
            renderSubtitleButton(player.currentTracks.containsType(C.TRACK_TYPE_TEXT))
            binding.btnAudioTracks.isEnabled = player.currentTracks.containsType(C.TRACK_TYPE_AUDIO)
        }

        binding.btnPlayPause.setOnClickListener { playerController.togglePlayPause(); resetOsdTimer() }
        binding.btnRewind.setOnClickListener { playerController.seekRewind(); resetOsdTimer() }
        binding.btnFastForward.setOnClickListener { playerController.seekForward(); resetOsdTimer() }
        binding.btnNextEpisode.setOnClickListener { playNextEpisode() }
        renderAutoNext()
        binding.btnAutoNext.setOnClickListener {
            val mode = (playbackPreferences.getInt("auto_next", 2) + 1) % 3
            playbackPreferences.edit().putInt("auto_next", mode).apply()
            renderAutoNext()
            resetOsdTimer()
        }
        binding.btnSubtitles.setOnClickListener { showTrackPicker(C.TRACK_TYPE_TEXT) }
        binding.btnAudioTracks.setOnClickListener { showTrackPicker(C.TRACK_TYPE_AUDIO) }
        binding.btnPlayerRetry.setOnClickListener { loadEpisode(episodeId) }
        listOf(
            binding.btnPlayPause,
            binding.btnRewind,
            binding.btnFastForward,
            binding.btnNextEpisode,
            binding.btnAutoNext,
            binding.btnSpeed,
            binding.btnSubtitles,
            binding.btnAudioTracks,
            binding.btnPlayerRetry
        ).forEach { button ->
            button.backgroundTintList = null
            TvFocusHelper.applyFocusScale(button)
        }
        TvFocusHelper.applyFocusScale(binding.seekProgress)
        TvFocusHelper.requestInitialFocus(binding.root, binding.btnPlayPause)

        binding.btnSpeed.setOnClickListener {
            val player = activePlayer ?: return@setOnClickListener
            val speedIndex = speeds.indexOf(player.playbackParameters.speed).takeIf { it >= 0 } ?: 1
            val speed = speeds[(speedIndex + 1) % speeds.size]
            player.setPlaybackSpeed(speed)
            playbackPreferences.edit().putFloat("speed", speed).apply()
            resetOsdTimer()
        }

        binding.seekProgress.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = activePlayer?.duration?.takeIf { it > 0 } ?: return
                    val position = progress.toLong() * duration / 1000L
                    binding.tvCurrentTime.text = formatTime(position)
                    // 遥控器和键盘不会触发触摸拖动回调，需要在此提交跳转。
                    if (!isSeeking) {
                        activePlayer?.seekTo(position)
                        resetOsdTimer()
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                isSeeking = true
                osdHideJob?.cancel()
            }

            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                isSeeking = false
                activePlayer?.duration?.takeIf { it > 0 }?.let { duration ->
                    activePlayer?.seekTo((seekBar?.progress ?: 0).toLong() * duration / 1000L)
                }
                resetOsdTimer()
            }
        })
    }

    override fun initData() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        binding.btnNextEpisode.visibility = if (state.isMovie) View.GONE else View.VISIBLE
                        binding.btnAutoNext.visibility = if (state.isMovie) View.GONE else View.VISIBLE
                        if (state.title.isNotBlank()) {
                            mediaTitle = state.title
                            binding.tvTitle.text = mediaTitle
                        }
                        val resolvedSeasonId = state.initialSeasonId ?: return@collect
                        if (!initialEpisodeLoaded) {
                            initialEpisodeLoaded = true
                            if (seasonId <= 0L) seasonId = resolvedSeasonId
                            loadEpisode(episodeId)
                        }
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is PlayerEvent.PlayNext -> {
                                if (event.hasGap) {
                                    trackDialog?.dismiss()
                                    trackDialog = AlertDialog.Builder(this@PlayerActivity)
                                        .setMessage(getString(com.wkq.bao.feature.res.R.string.player_episode_gap, event.title))
                                        .setPositiveButton(com.wkq.bao.feature.res.R.string.btn_play_now) { _, _ -> openNextEpisode(event.episode, event.title) }
                                        .setNegativeButton(android.R.string.cancel, null).show()
                                } else openNextEpisode(event.episode, event.title)
                            }
                            PlayerEvent.NavigationFailed -> Toast.makeText(this@PlayerActivity, com.wkq.bao.feature.res.R.string.player_navigation_failed, Toast.LENGTH_SHORT).show()
                            PlayerEvent.PlaybackFinished -> Toast.makeText(this@PlayerActivity, com.wkq.bao.feature.res.R.string.player_finished, Toast.LENGTH_SHORT).show()
                            PlayerEvent.LastEpisodeReached -> {
                                Toast.makeText(this@PlayerActivity, com.wkq.bao.feature.res.R.string.player_last_episode, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }
        viewModel.initialize(seasonId, episodeId, seriesId)
        startProgressTracker()
    }

    private fun renderAutoNext() {
        val labels = intArrayOf(com.wkq.bao.feature.res.R.string.player_auto_off,
            com.wkq.bao.feature.res.R.string.player_auto_season, com.wkq.bao.feature.res.R.string.player_auto_all)
        binding.btnAutoNext.setText(labels[playbackPreferences.getInt("auto_next", 2).coerceIn(0, 2)])
    }

    private fun openNextEpisode(episode: com.wkq.bao.core.database.entity.EpisodeEntity, title: String) {
        seasonId = episode.seasonId
        mediaTitle = title.ifBlank { mediaTitle }
        viewModel.setCurrentTitle(mediaTitle)
        binding.tvTitle.text = mediaTitle
        loadEpisode(episode.id)
    }

    private fun loadEpisode(targetEpisodeId: Long) {
        trackDialog?.dismiss()
        if (targetEpisodeId != episodeId) pendingRestorePosition = null
        episodeId = targetEpisodeId
        binding.btnAudioTracks.isEnabled = false
        binding.btnSubtitles.isEnabled = false
        isSeeking = false
        binding.seekProgress.progress = 0
        binding.tvCurrentTime.text = formatTime(0)
        binding.tvTotalTime.text = formatTime(0)
        binding.tvSourceBadge.visibility = View.INVISIBLE
        hidePlaybackError()
        binding.progressBuffering.visibility = View.VISIBLE
        playerController.playEpisode(seriesId, seasonId, targetEpisodeId) { success, message ->
            if (success) renderSourceBadge(message) else showPlaybackError()
        }
        showOsd()
    }

    private fun renderSpeed(speed: Float) {
        binding.btnSpeed.text = getString(com.wkq.bao.feature.res.R.string.player_speed_format, speed)
    }

    private fun showTrackPicker(type: Int) {
        val player = activePlayer ?: return
        if (trackDialog?.isShowing == true) return
        val mediaId = player.currentMediaItem?.mediaId
        val nameProvider = DefaultTrackNameProvider(resources)
        val options = player.currentTracks.groups.filter { it.type == type }.flatMap { group ->
            (0 until group.length).filter { group.isTrackSupported(it) }.map { index ->
                TrackOption(group.mediaTrackGroup, index, nameProvider.getTrackName(group.getTrackFormat(index)),
                    group.isTrackSelected(index))
            }
        }
        if (options.isEmpty()) return
        val subtitles = type == C.TRACK_TYPE_TEXT
        val prefix = if (subtitles) listOf(getString(com.wkq.bao.feature.res.R.string.player_subtitles_off),
            getString(com.wkq.bao.feature.res.R.string.player_track_auto))
        else listOf(getString(com.wkq.bao.feature.res.R.string.player_track_auto))
        val trackOverride = player.trackSelectionParameters.overrides.values.firstOrNull { it.type == type }
        val checked = when {
            subtitles && type in player.trackSelectionParameters.disabledTrackTypes -> 0
            trackOverride == null -> prefix.lastIndex
            else -> options.indexOfFirst { it.selected }.takeIf { it >= 0 }?.plus(prefix.size) ?: prefix.lastIndex
        }
        val returnFocus = if (subtitles) binding.btnSubtitles else binding.btnAudioTracks
        osdHideJob?.cancel()
        val builder = AlertDialog.Builder(this)
            .setTitle(if (subtitles) com.wkq.bao.feature.res.R.string.player_subtitle_tracks
                else com.wkq.bao.feature.res.R.string.player_audio_tracks)
            .setSingleChoiceItems((prefix + options.map { it.label }).toTypedArray(), checked) { picker, position ->
                if (player.currentMediaItem?.mediaId == mediaId) {
                    val selected = options.getOrNull(position - prefix.size)
                    if (selected == null || player.currentTracks.groups.any { it.mediaTrackGroup == selected.group }) {
                        val parameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
                            .setTrackTypeDisabled(type, subtitles && position == 0)
                        if (selected != null) parameters.setOverrideForType(TrackSelectionOverride(selected.group, selected.index))
                        val language = selected?.let { it.group.getFormat(it.index).language }
                        if (subtitles) {
                            parameters.setPreferredTextLanguage(language)
                            playbackPreferences.edit().putBoolean("subtitles_enabled", position != 0)
                                .putString("subtitle_language", language).apply()
                        } else {
                            parameters.setPreferredAudioLanguage(language)
                            playbackPreferences.edit().putString("audio_language", language).apply()
                        }
                        player.trackSelectionParameters = parameters.build()
                    }
                }
                picker.dismiss()
            }
        if (subtitles) builder.setNeutralButton(com.wkq.bao.feature.res.R.string.player_subtitle_settings) { _, _ ->
            showSubtitleSettings()
        }
        val dialog = builder.create()
        trackDialog = dialog
        dialog.setOnDismissListener {
            if (trackDialog === dialog) {
                trackDialog = null
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    if (TvFocusHelper.isTelevision(binding.root) && returnFocus.isEnabled) returnFocus.requestFocus()
                    resetOsdTimer()
                }
            }
        }
        dialog.show()
    }

    private fun applySubtitleSize() {
        val index = playbackPreferences.getInt("subtitle_size", 1).coerceIn(0, 2)
        binding.playerView.subtitleView?.setFractionalTextSize(listOf(0.04f, 0.0533f, 0.07f)[index])
    }

    private fun updateSubtitlePosition() {
        val height = binding.playerView.height.takeIf { it > 0 } ?: return
        val fraction = if (binding.layoutOsd.visibility == View.VISIBLE) {
            ((binding.layoutBottomPanel.height + binding.layoutOsd.paddingBottom +
                16 * resources.displayMetrics.density) / height).coerceIn(0.08f, 0.6f)
        } else 0.08f
        binding.playerView.subtitleView?.setBottomPaddingFraction(fraction)
    }

    private fun showSubtitleSettings() {
        trackDialog = null
        val menu = AlertDialog.Builder(this).setTitle(com.wkq.bao.feature.res.R.string.player_subtitle_settings)
            .setItems(arrayOf(getString(com.wkq.bao.feature.res.R.string.player_subtitle_size),
                getString(com.wkq.bao.feature.res.R.string.player_subtitle_delay))) { _, index ->
                showSubtitleSetting(index == 0)
            }.create()
        showSubtitleDialog(menu)
    }

    private fun showSubtitleSetting(size: Boolean) {
        val offsets = (-20..20).map { it * 500 }
        val labels = if (size) listOf(com.wkq.bao.feature.res.R.string.player_subtitle_size_small,
            com.wkq.bao.feature.res.R.string.player_subtitle_size_normal,
            com.wkq.bao.feature.res.R.string.player_subtitle_size_large).map(::getString)
        else offsets.map { getString(com.wkq.bao.feature.res.R.string.player_subtitle_delay_value, it / 1000f) }
        val checked = if (size) playbackPreferences.getInt("subtitle_size", 1).coerceIn(0, 2)
            else offsets.indexOf(playbackPreferences.getInt("subtitle_offset_ms", 0)).takeIf { it >= 0 } ?: 20
        val player = activePlayer
        val mediaId = player?.currentMediaItem?.mediaId
        val dialog = AlertDialog.Builder(this).setTitle(if (size) com.wkq.bao.feature.res.R.string.player_subtitle_size
            else com.wkq.bao.feature.res.R.string.player_subtitle_delay)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { picker, index ->
                if (size) {
                    playbackPreferences.edit().putInt("subtitle_size", index).apply()
                    applySubtitleSize()
                } else {
                    playbackPreferences.edit().putInt("subtitle_offset_ms", offsets[index]).apply()
                    // 重建解析器及已缓冲字幕，保留进度、选轨与暂停状态。
                    if (player != null && player.currentMediaItem?.mediaId == mediaId) {
                        player.currentMediaItem?.let { item ->
                            val position = player.currentPosition
                            val shouldPlay = player.playWhenReady
                            player.setMediaItem(item, position)
                            player.prepare()
                            player.playWhenReady = shouldPlay
                        }
                    }
                }
                picker.dismiss()
            }.create()
        showSubtitleDialog(dialog)
    }

    private fun showSubtitleDialog(dialog: AlertDialog) {
        osdHideJob?.cancel()
        trackDialog = dialog
        dialog.setOnDismissListener {
            if (trackDialog === dialog) {
                trackDialog = null
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    if (TvFocusHelper.isTelevision(binding.root)) binding.btnSubtitles.requestFocus()
                    resetOsdTimer()
                }
            }
        }
        dialog.show()
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
        isSeeking = false
        binding.progressBuffering.visibility = View.GONE
        binding.layoutPlayerError.visibility = View.VISIBLE
        binding.btnPlayerRetry.requestFocus()
        osdHideJob?.cancel()
    }

    private fun hidePlaybackError() {
        val retryHadFocus = binding.btnPlayerRetry.hasFocus()
        binding.layoutPlayerError.visibility = View.GONE
        if (retryHadFocus && binding.layoutOsd.visibility == View.VISIBLE) binding.btnPlayPause.requestFocus()
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
        binding.tvSourceBadge.visibility = View.VISIBLE
    }

    private fun startProgressTracker() {
        progressTrackerJob?.cancel()
        progressTrackerJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    renderProgress()
                    delay(1000)
                }
            }
        }
    }

    private fun renderProgress() {
        if (isSeeking) return
        val player = activePlayer ?: return
        val duration = player.duration.takeIf { it > 0 } ?: return
        val position = player.currentPosition.coerceIn(0L, duration)
        binding.seekProgress.progress = (position * 1000L / duration).toInt().coerceIn(0, 1000)
        binding.tvCurrentTime.text = formatTime(position)
        binding.tvTotalTime.text = formatTime(duration)
    }

    private fun playNextEpisode() {
        if (viewModel.uiState.value.isMovie) return
        viewModel.playNextEpisode(seriesId, episodeId)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> resetOsdTimer()
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_REWIND -> { playerController.seekRewind(); showOsd(); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { playerController.seekForward(); showOsd(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { activePlayer?.play(); showOsd(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { activePlayer?.pause(); showOsd(); return true }
        }
        if (binding.layoutPlayerError.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                finish()
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
        // OSD 可见时交给已聚焦的控件处理方向键和确认键，保证倍速、下一集等按钮可达。
        if (binding.layoutOsd.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
            ) resetOsdTimer()
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                    playerController.togglePlayPause()
                    resetOsdTimer()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    if (event?.repeatCount == 0) playNextEpisode()
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
            KeyEvent.KEYCODE_MEDIA_NEXT -> { if (event?.repeatCount == 0) playNextEpisode(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showOsd() {
        if (binding.layoutPlayerError.visibility == View.VISIBLE) return
        binding.layoutOsd.visibility = View.VISIBLE
        updateSubtitlePosition()
        binding.btnPlayPause.requestFocus()
        resetOsdTimer()
    }

    private fun hideOsd() {
        if (isSeeking || binding.layoutPlayerError.visibility == View.VISIBLE) return
        binding.layoutOsd.visibility = View.GONE
        updateSubtitlePosition()
        binding.playerView.requestFocus()
        osdHideJob?.cancel()
    }

    private fun resetOsdTimer() {
        osdHideJob?.cancel()
        if (isSeeking || trackDialog?.isShowing == true || binding.layoutPlayerError.visibility == View.VISIBLE ||
            binding.layoutOsd.visibility != View.VISIBLE || activePlayer?.isPlaying != true ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) return
        osdHideJob = lifecycleScope.launch {
            delay(5000)
            hideOsd()
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
        trackDialog?.dismiss()
        isSeeking = false
        osdHideJob?.cancel()
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

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(EXTRA_SEASON_ID, seasonId)
        outState.putLong(EXTRA_EPISODE_ID, episodeId)
        outState.putString(EXTRA_TITLE, mediaTitle)
        activePlayer?.takeIf { it.currentMediaItem?.mediaId == episodeId.toString() }?.let {
            outState.putLong("playback_position", it.currentPosition.coerceAtLeast(0L))
            outState.putBoolean("playback_paused", !it.playWhenReady)
        }
        super.onSaveInstanceState(outState)
    }
}
