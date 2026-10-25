package com.wkq.bao.core.media.external

import android.content.Context

/** 只保存用户的播放方式选择，默认继续遵循厂商适配。 */
object PlaybackBackendPreference {
    private const val KEY = "prefer_internal_player"
    fun prefersInternal(context: Context): Boolean =
        context.getSharedPreferences("playback_preferences", Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun setInternal(context: Context, internal: Boolean) {
        context.getSharedPreferences("playback_preferences", Context.MODE_PRIVATE).edit().putBoolean(KEY, internal).apply()
    }
}
