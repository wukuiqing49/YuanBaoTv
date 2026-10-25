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
import com.lxj.xpopup.XPopup
import com.lxj.xpopup.core.BasePopupView
import com.wkq.base.activity.BaseActivity
import com.wkq.bao.core.media.controller.TvPlayerController
import com.wkq.bao.feature.app.databinding.ActivityPlayerBinding
import com.wkq.bao.feature.app.utils.TvFocusHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/** 全屏播放页只显示并控制 MediaSession 中的唯一播放器实例。 */
open class PlayerActivity : BaseActivity<ActivityPlayerBinding>() {

    companion object {
        private const val EXTRA_SERIES_ID = "extra_series_id"
        private const val EXTRA_SEASON_ID = "extra_season_id"
        private const val EXTRA_EPISODE_ID = "extra_episode_id"
        private const val EXTRA_TITLE = "extra_title"

        fun resolvePlayerClass(context: Context): Class<out PlayerActivity> {
            val manufacturer = android.os.Build.MANUFACTURER.orEmpty()
            val brand = android.os.Build.BRAND.orEmpty()
            val television = context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_TYPE_MASK == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
            return if (television && (manufacturer.contains("Xiaomi", ignoreCase = true) ||
                brand.contains("Xiaomi", ignoreCase = true) ||
                brand.contains("Redmi", ignoreCase = true))
            ) {
                com.wkq.bao.feature.app.mitv.MiTvPlayerActivity::class.java
            } else {
                PlayerActivity::class.java
            }
        }

        fun start(context: Context, seriesId: Long, seasonId: Long, episodeId: Long, title: String) {
            // ????/????????????????????????????????????????
            launchPlayer(context, resolvePlayerClass(context), seriesId, seasonId, episodeId, title)
        }

        fun startInternal(context: Context, seriesId: Long, seasonId: Long, episodeId: Long, title: String) {
            launchPlayer(context, resolvePlayerClass(context), seriesId, seasonId, episodeId, title)
        }

        private fun launchPlayer(context: Context, targetClass: Class<*>, seriesId: Long, seasonId: Long, episodeId: Long, title: String) {
            context.startActivity(Intent(context, targetClass).apply {
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
    private var activePopup: BasePopupView? = null
    private var isFailoverAttempting = false
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
            val speedText = if (playbackParameters.speed == 1.0f) "正常速度" else "${playbackParameters.speed}x 倍速"
            Toast.makeText(this@PlayerActivity, "播放速度：$speedText", Toast.LENGTH_SHORT).show()
        }

        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
            subtitlesEnabled = C.TRACK_TYPE_TEXT !in parameters.disabledTrackTypes
            renderSubtitleButton(activePlayer?.currentTracks?.containsType(C.TRACK_TYPE_TEXT) == true)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            (binding.btnPlayPause as? com.google.android.material.button.MaterialButton)?.apply {
                setIconResource(
                    if (isPlaying) com.wkq.bao.feature.res.R.drawable.ic_tv_pause
                    else com.wkq.bao.feature.res.R.drawable.ic_tv_play
                )
                text = ""
            }
            resetOsdTimer()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            handlePlaybackFailover()
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
        playerController.onNextEpisodeRequested = {
            if (!isFinishing && !isDestroyed) playNextEpisode()
        }
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
        val savedResizeMode = playbackPreferences.getInt("resize_mode", androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT)
        binding.playerView.resizeMode = savedResizeMode
        }

        binding.btnPlayPause.text = ""
        binding.btnRewind.text = ""
        binding.btnFastForward.text = ""
        binding.btnNextEpisode.text = ""

        binding.btnPlayPause.setOnClickListener { playerController.togglePlayPause(); resetOsdTimer() }
        binding.btnRewind.setOnClickListener { performRewind(); resetOsdTimer() }
        binding.btnFastForward.setOnClickListener { performFastForward(); resetOsdTimer() }
        binding.btnNextEpisode.setOnClickListener { playNextEpisode(); resetOsdTimer() }
        binding.btnMore.setOnClickListener { showMoreSettingsDialog() }
        renderAutoNext()
        binding.btnAutoNext.setOnClickListener {
            val mode = (playbackPreferences.getInt("auto_next", 2) + 1) % 3
            playbackPreferences.edit().putInt("auto_next", mode).apply()
            renderAutoNext()
            resetOsdTimer()
        }
        binding.btnSubtitles.setOnClickListener { showTrackPicker(C.TRACK_TYPE_TEXT) }
        binding.btnAudioTracks.setOnClickListener { showTrackPicker(C.TRACK_TYPE_AUDIO) }
        binding.btnPlayerRetry.setOnClickListener { isFailoverAttempting = false; loadEpisode(episodeId) }
        listOf(
            binding.btnPlayPause,
            binding.btnRewind,
            binding.btnFastForward,
            binding.btnNextEpisode,
            binding.btnMore,
            binding.btnPlayerRetry
        ).forEach { button ->
            button.backgroundTintList = null
            TvFocusHelper.applyFocusScale(button, if (button.id == binding.btnPlayPause.id) 1.12f else 1.05f)
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
                                    activePopup?.dismiss()
                                    activePopup = XPopup.Builder(this@PlayerActivity)
                                        .isDarkTheme(true)
                                        .asConfirm(
                                            getString(com.wkq.bao.feature.res.R.string.player_default_title),
                                            getString(com.wkq.bao.feature.res.R.string.player_episode_gap, event.title),
                                            { openNextEpisode(event.episode, event.title) }
                                        ).show()
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
        activePopup?.dismiss()
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
            if (success) {
                renderSourceBadge(message)
                val skipIntroSeconds = playbackPreferences.getInt("skip_intro", 0)
                if (skipIntroSeconds > 0 && (activePlayer?.currentPosition ?: 0L) < 3000L) {
                    activePlayer?.seekTo(skipIntroSeconds * 1000L)
                    Toast.makeText(this@PlayerActivity, getString(com.wkq.bao.feature.res.R.string.player_skip_intro_toast, skipIntroSeconds), Toast.LENGTH_SHORT).show()
                }
            } else {
                handlePlaybackFailover()
            }
        }
        showOsd()
    }

    private fun renderSpeed(speed: Float) {
        binding.btnSpeed.text = getString(com.wkq.bao.feature.res.R.string.player_speed_format, speed)
    }

    private fun showTrackPicker(type: Int) {
        val player = activePlayer ?: return
        if (activePopup?.isShow == true) return
        val mediaId = player.currentMediaItem?.mediaId
        val nameProvider = DefaultTrackNameProvider(resources)
        val options = player.currentTracks.groups.filter { it.type == type }.flatMap { group ->
            (0 until group.length).filter { group.isTrackSupported(it) }.map { index ->
                val trackName = nameProvider.getTrackName(group.getTrackFormat(index))
                val isSelected = group.isTrackSelected(index)
                val label = if (isSelected) "✓ $trackName" else trackName
                TrackOption(group.mediaTrackGroup, index, label, isSelected)
            }
        }
        if (options.isEmpty()) return
        val subtitles = type == C.TRACK_TYPE_TEXT
        val hasAnySelected = options.any { it.selected }
        val prefix = if (subtitles) {
            val offLabel = if (!hasAnySelected) "✓ " + getString(com.wkq.bao.feature.res.R.string.player_subtitles_off)
                           else getString(com.wkq.bao.feature.res.R.string.player_subtitles_off)
            listOf(offLabel, getString(com.wkq.bao.feature.res.R.string.player_track_auto))
        } else {
            val autoLabel = if (!hasAnySelected) "✓ " + getString(com.wkq.bao.feature.res.R.string.player_track_auto)
                            else getString(com.wkq.bao.feature.res.R.string.player_track_auto)
            listOf(autoLabel)
        }
        val returnFocus = if (subtitles) binding.btnSubtitles else binding.btnAudioTracks
        osdHideJob?.cancel()

        val allItems = (prefix + options.map { it.label }).toMutableList()
        if (subtitles) {
            allItems.add("⚙ " + getString(com.wkq.bao.feature.res.R.string.player_subtitle_settings))
        }

        val title = getString(if (subtitles) com.wkq.bao.feature.res.R.string.player_subtitle_tracks
            else com.wkq.bao.feature.res.R.string.player_audio_tracks)

        activePopup?.dismiss()
        val popup = XPopup.Builder(this)
            .isDarkTheme(true)
            .maxWidth((460 * resources.displayMetrics.density).toInt())
            .setPopupCallback(object : com.lxj.xpopup.interfaces.SimpleCallback() {
                override fun onDismiss(popupView: com.lxj.xpopup.core.BasePopupView?) {
                    if (activePopup === popupView) {
                        activePopup = null
                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                            if (TvFocusHelper.isTelevision(binding.root) && returnFocus.isEnabled) returnFocus.requestFocus()
                            resetOsdTimer()
                        }
                    }
                }
            })
            .asCenterList(title, allItems.toTypedArray()) { position, _ ->
                if (subtitles && position == allItems.lastIndex) {
                    showSubtitleSettings()
                    return@asCenterList
                }
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
            }
        activePopup = popup
        popup.show()
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
        activePopup?.dismiss()
        val title = getString(com.wkq.bao.feature.res.R.string.player_subtitle_settings)
        val items = arrayOf(
            getString(com.wkq.bao.feature.res.R.string.player_subtitle_size),
            getString(com.wkq.bao.feature.res.R.string.player_subtitle_delay)
        )
        val popup = XPopup.Builder(this)
            .isDarkTheme(true)
            .maxWidth((460 * resources.displayMetrics.density).toInt())
            .asCenterList(title, items) { index, _ ->
                showSubtitleSetting(index == 0)
            }
        activePopup = popup
        popup.show()
    }

    private fun showSubtitleSetting(size: Boolean) {
        val offsets = (-20..20).map { it * 500 }
        val labels = if (size) listOf(
            com.wkq.bao.feature.res.R.string.player_subtitle_size_small,
            com.wkq.bao.feature.res.R.string.player_subtitle_size_normal,
            com.wkq.bao.feature.res.R.string.player_subtitle_size_large
        ).map(::getString)
        else offsets.map { getString(com.wkq.bao.feature.res.R.string.player_subtitle_delay_value, it / 1000f) }

        val title = getString(if (size) com.wkq.bao.feature.res.R.string.player_subtitle_size
            else com.wkq.bao.feature.res.R.string.player_subtitle_delay)
        val player = activePlayer
        val mediaId = player?.currentMediaItem?.mediaId

        activePopup?.dismiss()
        val popup = XPopup.Builder(this)
            .isDarkTheme(true)
            .maxWidth((460 * resources.displayMetrics.density).toInt())
            .asCenterList(title, labels.toTypedArray()) { index, _ ->
                if (size) {
                    playbackPreferences.edit().putInt("subtitle_size", index).apply()
                    applySubtitleSize()
                } else {
                    playbackPreferences.edit().putInt("subtitle_offset_ms", offsets[index]).apply()
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
            }
        activePopup = popup
        popup.show()
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

    private fun handlePlaybackFailover() {
        if (isFailoverAttempting || episodeId <= 0L) {
            showPlaybackError()
            return
        }
        isFailoverAttempting = true
        lifecycleScope.launch {
            val available = playerController.getAvailableSources(episodeId)
            val currentSource = playerController.getCurrentSource()
            val backupSource = available.firstOrNull { src ->
                src !is com.wkq.bao.core.media.resolver.PlaybackSource.Unavailable && src != currentSource
            }
            if (backupSource != null) {
                val label = when (backupSource) {
                    is com.wkq.bao.core.media.resolver.PlaybackSource.Local -> getString(com.wkq.bao.feature.res.R.string.player_source_local_format, backupSource.location.name)
                    is com.wkq.bao.core.media.resolver.PlaybackSource.NasStream -> getString(com.wkq.bao.feature.res.R.string.player_source_nas_format)
                    else -> ""
                }
                Toast.makeText(this@PlayerActivity, getString(com.wkq.bao.feature.res.R.string.player_failover_toast, label), Toast.LENGTH_LONG).show()
                playerController.switchToSource(backupSource) { success, msg ->
                    isFailoverAttempting = false
                    if (success) {
                        renderSourceBadge(msg)
                        hidePlaybackError()
                    } else {
                        showPlaybackError()
                    }
                }
            } else {
                isFailoverAttempting = false
                showPlaybackError()
            }
        }
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
                KeyEvent.KEYCODE_MENU -> {
                    if (activePopup?.isShow == true) {
                        return super.dispatchKeyEvent(event)
                    }
                    if (event.repeatCount == 0) {
                        cycleAspectRatioWithHud()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    resetOsdTimer()
                    if (binding.layoutOsd.visibility == View.VISIBLE && binding.playerView.hasFocus()) {
                        binding.btnPlayPause.requestFocus()
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_REWIND -> { performRewind(); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { performFastForward(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { playerController.play(); showOsd(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { activePlayer?.pause(); showOsd(); return true }
            KeyEvent.KEYCODE_MEDIA_STOP -> { activePlayer?.stop(); showOsd(); return true }
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
        // 全屏无 OSD 时按键：按确定/空格直接切换播放暂停并唤出 OSD；按上/下/菜单键仅唤出 OSD
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                playerController.togglePlayPause()
                showOsd()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                showOsd()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val step = if (event != null && event.repeatCount > 3) 30_000L else 10_000L
                performRewind(step)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val step = if (event != null && event.repeatCount > 3) 30_000L else 15_000L
                performFastForward(step)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { if (event?.repeatCount == 0) playNextEpisode(); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (event?.repeatCount == 0) {
                    val p = activePlayer
                    if (p != null) {
                        p.seekTo(0L)
                        showSeekBubble(0L, 0L, p.duration.coerceAtLeast(0L))
                    }
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private var seekBubbleJob: kotlinx.coroutines.Job? = null

    private fun showSeekBubble(deltaMs: Long, targetMs: Long, totalMs: Long) {
        val bubble = binding.layoutSeekBubble ?: return
        seekBubbleJob?.cancel()
        val sign = if (deltaMs >= 0) "+" else ""
        binding.tvSeekDelta?.text = String.format("%s%ds", sign, deltaMs / 1000)
        binding.tvSeekTimeTarget?.text = String.format("%s / %s", formatTime(targetMs), formatTime(totalMs))
        bubble.animate()?.cancel()
        bubble.alpha = 1f
        bubble.visibility = View.VISIBLE
        seekBubbleJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(1200)
            bubble.animate()?.alpha(0f)?.setDuration(250)?.withEndAction {
                bubble.visibility = View.GONE
            }?.start()
        }
    }

    private var aspectBubbleJob: kotlinx.coroutines.Job? = null

    private fun getCurrentAspectRatioLabel(): String {
        return when (binding.playerView.resizeMode) {
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> getString(com.wkq.bao.feature.res.R.string.player_aspect_zoom)
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL -> getString(com.wkq.bao.feature.res.R.string.player_aspect_fill)
            else -> getString(com.wkq.bao.feature.res.R.string.player_aspect_fit)
        }
    }

    private fun cycleAspectRatio(): String {
        val nextMode = when (binding.playerView.resizeMode) {
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        binding.playerView.resizeMode = nextMode
        playbackPreferences.edit().putInt("resize_mode", nextMode).apply()
        return getCurrentAspectRatioLabel()
    }

    private fun showAspectBubble(label: String) {
        val bubble = binding.layoutAspectBubble ?: return
        aspectBubbleJob?.cancel()
        binding.tvAspectMode?.text = label
        bubble.animate()?.cancel()
        bubble.alpha = 1f
        bubble.visibility = View.VISIBLE
        aspectBubbleJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(1500)
            bubble.animate()?.alpha(0f)?.setDuration(250)?.withEndAction {
                bubble.visibility = View.GONE
            }?.start()
        }
    }

    private fun cycleAspectRatioWithHud() {
        val label = cycleAspectRatio()
        showAspectBubble(label)
    }

    private fun performRewind(step: Long = 10_000L) {
        val p = activePlayer
        if (p != null) {
            val target = (p.currentPosition - step).coerceAtLeast(0L)
            p.seekTo(target)
            showSeekBubble(-step, target, p.duration.coerceAtLeast(0L))
        } else {
            playerController.seekRewind()
        }
        showOsd()
    }

    private fun performFastForward(step: Long = 15_000L) {
        val p = activePlayer
        if (p != null) {
            val target = (p.currentPosition + step).coerceAtMost(p.duration.coerceAtLeast(0L))
            p.seekTo(target)
            showSeekBubble(step, target, p.duration.coerceAtLeast(0L))
        } else {
            playerController.seekForward()
        }
        showOsd()
    }

    private fun showEpisodePicker() {
        if (seasonId <= 0L) return
        lifecycleScope.launch {
            val episodes = withContext(Dispatchers.IO) {
                com.wkq.bao.core.database.AppDatabase.getInstance(this@PlayerActivity).mediaDao().getEpisodesSync(seriesId, seasonId)
            }
            if (episodes.isEmpty()) {
                Toast.makeText(this@PlayerActivity, com.wkq.bao.feature.res.R.string.no_playable_episode, Toast.LENGTH_SHORT).show()
                return@launch
            }
            activePopup?.dismiss()
            val epPopup = PlayerEpisodePickerPopup(this@PlayerActivity, episodes, episodeId) { targetEpId ->
                loadEpisode(targetEpId)
            }
            activePopup = epPopup
            XPopup.Builder(this@PlayerActivity)
                .isDarkTheme(true)
                .asCustom(epPopup)
                .show()
        }
    }

    private fun showMoreSettingsDialog() {
        osdHideJob?.cancel()
        val drawerPopup = PlayerSettingsDrawerPopup(this, object : PlayerSettingsDrawerPopup.Callbacks {
            override fun onSelectEpisode() {
                showEpisodePicker()
            }

            override fun onToggleAspectRatio(): String {
                val label = cycleAspectRatio()
                showAspectBubble(label)
                return label
            }

            override fun onSwitchSource() {
                lifecycleScope.launch {
                    val available = playerController.getAvailableSources(episodeId)
                    if (available.size <= 1) {
                        Toast.makeText(this@PlayerActivity, com.wkq.bao.feature.res.R.string.player_single_source_hint, Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val sourceLabels = available.map { src ->
                        when (src) {
                            is com.wkq.bao.core.media.resolver.PlaybackSource.Local -> getString(com.wkq.bao.feature.res.R.string.player_source_local_format, src.location.name)
                            is com.wkq.bao.core.media.resolver.PlaybackSource.NasStream -> getString(com.wkq.bao.feature.res.R.string.player_source_nas_format)
                            is com.wkq.bao.core.media.resolver.PlaybackSource.Unavailable -> src.reason
                        }
                    }.toTypedArray()

                    XPopup.Builder(this@PlayerActivity)
                        .isDarkTheme(true)
                        .maxWidth((460 * resources.displayMetrics.density).toInt())
                        .asCenterList(getString(com.wkq.bao.feature.res.R.string.player_switch_source), sourceLabels) { idx, _ ->
                            val targetSource = available[idx]
                            playerController.switchToSource(targetSource) { success, msg ->
                                if (success) {
                                    renderSourceBadge(msg)
                                    Toast.makeText(this@PlayerActivity, getString(com.wkq.bao.feature.res.R.string.player_switch_source_success, msg), Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(this@PlayerActivity, msg, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }.show()
                }
            }

            override fun onToggleSkipIntro(): String {
                val currentSkip = playbackPreferences.getInt("skip_intro", 0)
                val skipSteps = listOf(0, 60, 90, 120)
                val nextSkip = skipSteps[((skipSteps.indexOf(currentSkip).takeIf { it >= 0 } ?: 0) + 1) % skipSteps.size]
                playbackPreferences.edit().putInt("skip_intro", nextSkip).apply()
                val skipToast = if (nextSkip == 0) getString(com.wkq.bao.feature.res.R.string.player_skip_intro_off) else getString(com.wkq.bao.feature.res.R.string.player_skip_intro_format, nextSkip)
                Toast.makeText(this@PlayerActivity, skipToast, Toast.LENGTH_SHORT).show()
                return getCurrentSkipIntroLabel()
            }

            override fun onToggleSpeed(): String {
                val p = activePlayer
                val currentSpeed = p?.playbackParameters?.speed ?: 1.0f
                val speedIndex = speeds.indexOf(currentSpeed).takeIf { it >= 0 } ?: 1
                val nextSpeed = speeds[(speedIndex + 1) % speeds.size]
                p?.setPlaybackSpeed(nextSpeed)
                playbackPreferences.edit().putFloat("speed", nextSpeed).apply()
                renderSpeed(nextSpeed)
                return getCurrentSpeedLabel()
            }

            override fun onSelectSubtitles() {
                showTrackPicker(C.TRACK_TYPE_TEXT)
            }

            override fun onSelectAudioTracks() {
                showTrackPicker(C.TRACK_TYPE_AUDIO)
            }

            override fun onToggleAutoNext(): String {
                val current = playbackPreferences.getInt("auto_next", 2)
                val next = (current + 1) % 3
                playbackPreferences.edit().putInt("auto_next", next).apply()
                renderAutoNext()
                return getCurrentAutoNextLabel()
            }

            override fun getCurrentAspectRatioLabel(): String {
                return this@PlayerActivity.getCurrentAspectRatioLabel()
            }

            override fun getCurrentSourceLabel(): String {
                return when (val current = playerController.getCurrentSource()) {
                    is com.wkq.bao.core.media.resolver.PlaybackSource.Local -> current.location.name
                    is com.wkq.bao.core.media.resolver.PlaybackSource.NasStream -> "NAS"
                    else -> ""
                }
            }

            override fun getCurrentSkipIntroLabel(): String {
                val s = playbackPreferences.getInt("skip_intro", 0)
                return if (s <= 0) getString(com.wkq.bao.feature.res.R.string.player_skip_intro_off) else getString(com.wkq.bao.feature.res.R.string.player_skip_intro_format, s)
            }

            override fun getCurrentSpeedLabel(): String {
                val speed = activePlayer?.playbackParameters?.speed ?: 1.0f
                return getString(com.wkq.bao.feature.res.R.string.player_speed_format, speed)
            }

            override fun getCurrentAutoNextLabel(): String {
                return when (playbackPreferences.getInt("auto_next", 2)) {
                    0 -> getString(com.wkq.bao.feature.res.R.string.player_auto_off)
                    1 -> getString(com.wkq.bao.feature.res.R.string.player_auto_season)
                    else -> getString(com.wkq.bao.feature.res.R.string.player_auto_all)
                }
            }

            override fun hasMultipleEpisodes(): Boolean = seasonId > 0L
        })

        activePopup?.dismiss()
        val popup = XPopup.Builder(this)
            .isDarkTheme(true)
            .popupPosition(com.lxj.xpopup.enums.PopupPosition.Right)
            .setPopupCallback(object : com.lxj.xpopup.interfaces.SimpleCallback() {
                override fun onDismiss(popupView: com.lxj.xpopup.core.BasePopupView?) {
                    if (activePopup === popupView) {
                        activePopup = null
                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                            if (TvFocusHelper.isTelevision(binding.root)) binding.btnMore.requestFocus()
                            resetOsdTimer()
                        }
                    }
                }
            })
            .asCustom(drawerPopup)

        activePopup = popup
        popup.show()
    }

    private fun showOsd() {
        if (binding.layoutPlayerError.visibility == View.VISIBLE) return
        binding.layoutOsd.animate().cancel()
        if (binding.layoutOsd.visibility != View.VISIBLE) {
            binding.layoutOsd.alpha = 0f
            binding.layoutOsd.visibility = View.VISIBLE
            binding.layoutOsd.animate().alpha(1f).setDuration(220).start()
        }
        updateSubtitlePosition()
        binding.btnPlayPause.requestFocus()
        resetOsdTimer()
    }

    private fun hideOsd() {
        if (isSeeking || binding.layoutPlayerError.visibility == View.VISIBLE || binding.layoutOsd.visibility != View.VISIBLE) return
        binding.layoutOsd.animate().cancel()
        binding.layoutOsd.animate().alpha(0f).setDuration(220).withEndAction {
            binding.layoutOsd.visibility = View.GONE
            updateSubtitlePosition()
            binding.playerView.requestFocus()
        }.start()
        osdHideJob?.cancel()
    }

    private fun resetOsdTimer() {
        osdHideJob?.cancel()
        if (isSeeking || activePopup?.isShow == true || binding.layoutPlayerError.visibility == View.VISIBLE ||
            binding.layoutOsd.visibility != View.VISIBLE ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) return
        val delayMs = if (activePlayer?.isPlaying == true) 5000L else 8000L
        osdHideJob = lifecycleScope.launch {
            delay(delayMs)
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

    override fun onStart() {
        super.onStart()
        if (::playerController.isInitialized) playerController.setPlaybackControlsActive(true)
    }

    override fun onUserLeaveHint() {
        // Home/切换应用属于主动离开；语音临时覆盖不再由 onStop 无条件暂停。
        if (::playerController.isInitialized) playerController.setPlaybackControlsActive(false)
        super.onUserLeaveHint()
    }

    override fun onStop() {
        activePopup?.dismiss()
        isSeeking = false
        osdHideJob?.cancel()
        if (isFinishing && ::playerController.isInitialized) {
            playerController.setPlaybackControlsActive(false)
        }
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
