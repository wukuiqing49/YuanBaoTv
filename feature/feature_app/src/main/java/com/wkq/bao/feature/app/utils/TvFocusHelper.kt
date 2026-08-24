package com.wkq.bao.feature.app.utils

import android.content.res.Configuration
import android.view.View

/**
 * 电视遥控器焦点放大与动效辅助类
 */
object TvFocusHelper {

    fun applyFocusScale(view: View, scale: Float = 1.08f) {
        if (!isTelevision(view)) return
        view.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .setDuration(150)
                    .start()
                v.elevation = 12f
            } else {
                v.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(150)
                    .start()
                v.elevation = 0f
            }
        }
    }

    /** 仅在电视设备上补齐首焦点，避免触屏设备无故显示键盘焦点环。 */
    fun requestInitialFocus(root: View, preferred: View) {
        if (!isTelevision(root)) return
        root.post {
            if (!root.hasFocus() && preferred.isShown && preferred.isEnabled) preferred.requestFocus()
        }
    }

    fun isTelevision(view: View): Boolean {
        val type = view.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        return type == Configuration.UI_MODE_TYPE_TELEVISION
    }
}
