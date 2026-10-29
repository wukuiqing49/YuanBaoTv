package com.wkq.bao.feature.app

import android.content.Context
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.lxj.xpopup.core.DrawerPopupView
import com.wkq.bao.feature.app.databinding.PopupPlayerSettingsDrawerBinding
import com.wkq.bao.feature.app.utils.TvFocusHelper
import com.wkq.bao.feature.res.R as ResR

class PlayerSettingsDrawerPopup(
    context: Context,
    private val callbacks: Callbacks
) : DrawerPopupView(context) {

    interface Callbacks {
        fun onSelectEpisode()
        fun onToggleAspectRatio(): String
        fun onSwitchSource()
        fun onToggleSkipIntro(): String
        fun onToggleSpeed(): String
        fun onSelectSubtitles()
        fun onSelectAudioTracks()
        fun onToggleAutoNext(): String

        fun getCurrentAspectRatioLabel(): String
        fun getCurrentSourceLabel(): String
        fun getCurrentSkipIntroLabel(): String
        fun getCurrentSpeedLabel(): String
        fun getCurrentAutoNextLabel(): String
        fun getCurrentSubtitlesLabel(): String = ""
        fun getCurrentAudioLabel(): String = ""
        fun hasMultipleEpisodes(): Boolean
    }

    private lateinit var binding: PopupPlayerSettingsDrawerBinding

    override val implLayoutId: Int
        get() = R.layout.popup_player_settings_drawer

    override fun onCreate() {
        super.onCreate()
        binding = PopupPlayerSettingsDrawerBinding.bind(popupImplView)
        (popupImplView as? ViewGroup)?.clipChildren = false
        (popupImplView as? ViewGroup)?.clipToPadding = false
        (popupImplView.parent as? ViewGroup)?.clipChildren = false
        (popupImplView.parent as? ViewGroup)?.clipToPadding = false

        binding.itemSelectEpisode.isVisible = callbacks.hasMultipleEpisodes()
        binding.itemSelectEpisode.setOnClickListener {
            dismiss()
            callbacks.onSelectEpisode()
        }

        refreshLabels()

        binding.itemAspectRatio.setOnClickListener {
            callbacks.onToggleAspectRatio()
            refreshLabels()
        }

        binding.itemSwitchSource.setOnClickListener {
            callbacks.onSwitchSource()
        }

        binding.itemSkipIntro.setOnClickListener {
            callbacks.onToggleSkipIntro()
            refreshLabels()
        }

        binding.itemSpeed.setOnClickListener {
            val newLabel = callbacks.onToggleSpeed()
            binding.itemSpeed.text = newLabel
        }

        binding.itemSubtitles.setOnClickListener {
            callbacks.onSelectSubtitles()
        }

        binding.itemAudioTracks.setOnClickListener {
            callbacks.onSelectAudioTracks()
        }

        binding.itemAutoNext.setOnClickListener {
            callbacks.onToggleAutoNext()
            refreshLabels()
        }

        listOf(
            binding.itemSelectEpisode,
            binding.itemAspectRatio,
            binding.itemSwitchSource,
            binding.itemSkipIntro,
            binding.itemSpeed,
            binding.itemSubtitles,
            binding.itemAudioTracks,
            binding.itemAutoNext
        ).forEach { btn ->
            btn.backgroundTintList = null
            TvFocusHelper.applyFocusScale(btn, 1.03f)
        }

        val initialFocus = if (callbacks.hasMultipleEpisodes()) binding.itemSelectEpisode else binding.itemAspectRatio
        post { if (TvFocusHelper.isTelevision(binding.root)) initialFocus.requestFocus() }
    }

    fun refreshLabels() {
        if (!::binding.isInitialized) return
        fun label(title: Int, value: String) = context.getString(ResR.string.player_setting_value, context.getString(title), value)
        binding.itemAspectRatio.text = label(ResR.string.player_aspect_ratio, callbacks.getCurrentAspectRatioLabel())
        binding.itemSwitchSource.text = label(ResR.string.player_switch_source, callbacks.getCurrentSourceLabel())
        binding.itemSkipIntro.text = label(ResR.string.player_skip_intro, callbacks.getCurrentSkipIntroLabel())
        binding.itemSpeed.text = callbacks.getCurrentSpeedLabel()
        binding.itemAutoNext.text = label(ResR.string.player_auto_next_setting, callbacks.getCurrentAutoNextLabel())
        binding.itemSubtitles.text = label(ResR.string.player_subtitle_setting, callbacks.getCurrentSubtitlesLabel())
        binding.itemAudioTracks.text = label(ResR.string.player_audio_tracks, callbacks.getCurrentAudioLabel())
    }
}
