package com.wkq.bao.feature.app

import android.content.Context
import com.lxj.xpopup.XPopup
import com.lxj.xpopup.core.CenterPopupView
import com.wkq.bao.core.media.external.PlaybackBackendPreference
import com.wkq.bao.feature.app.databinding.PopupPlaybackModeBinding
import com.wkq.bao.feature.res.R as Res

/** 播放前即可切换，无需先进入系统播放器再退出。 */
object PlaybackModePicker {
    fun show(context: Context, onChanged: () -> Unit = {}) {
        val maxWidth = minOf((680 * context.resources.displayMetrics.density).toInt(),
            context.resources.displayMetrics.widthPixels - (32 * context.resources.displayMetrics.density).toInt())
        XPopup.Builder(context).isDarkTheme(true).maxWidth(maxWidth)
            .asCustom(ModePopup(context, onChanged)).show()
    }

    private class ModePopup(context: Context, private val onChanged: () -> Unit) : CenterPopupView(context) {
        private lateinit var binding: PopupPlaybackModeBinding
        override val implLayoutId: Int get() = R.layout.popup_playback_mode

        override fun onCreate() {
            super.onCreate()
            binding = PopupPlaybackModeBinding.bind(popupImplView)
            val available = com.wkq.bao.core.media.external.TelevisionPlayerAdapter.resolve(context) != null
            binding.btnAuto.setText(if (available) Res.string.player_mode_auto else Res.string.player_mode_auto_other)
            val internal = PlaybackBackendPreference.prefersInternal(context)
            binding.btnAuto.isSelected = !internal
            binding.btnInternal.isSelected = internal
            binding.btnAuto.setOnClickListener { select(false) }
            binding.btnInternal.setOnClickListener { select(true) }
            binding.btnCancel.setOnClickListener { dismiss() }
            listOf(binding.btnAuto, binding.btnInternal, binding.btnCancel).forEach {
                it.isFocusable = true
                it.isFocusableInTouchMode = true
                it.backgroundTintList = null
                it.supportBackgroundTintList = null
                com.wkq.bao.feature.app.utils.TvFocusHelper.applyFocusScale(it, 1.02f)
            }
        }

        override fun onShow() {
            super.onShow()
            val target = if (PlaybackBackendPreference.prefersInternal(context)) binding.btnInternal else binding.btnAuto
            target.post { target.requestFocus() }
        }

        private fun select(internal: Boolean) {
            PlaybackBackendPreference.setInternal(context, internal)
            dismiss()
            onChanged()
        }
    }
}
