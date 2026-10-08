package com.wkq.bao.feature.app.utils

import android.content.res.Configuration
import android.view.View

/**
 * 电视遥控器焦点放大与动效辅助类
 */
object TvFocusHelper {

    fun applyFocusScale(view: View, scale: Float = 1.08f) {
        if (!isTelevision(view)) return
        val originalElevation = view.elevation
        view.setOnFocusChangeListener { v, hasFocus ->
            v.animate().cancel()
            if (hasFocus) {
                v.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .setDuration(150)
                    .start()
                v.elevation = originalElevation + 12f * v.resources.displayMetrics.density
            } else {
                v.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(150)
                    .start()
                v.elevation = originalElevation
            }
        }
    }

    /** 回收时结束动效，避免放大状态带到另一个列表项。 */
    fun resetRecycledFocus(view: View) {
        if (!isTelevision(view)) return
        view.clearFocus()
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
    }

    /** 仅在电视设备上补齐首焦点，避免触屏设备无故显示键盘焦点环。 */
    fun requestInitialFocus(root: View, preferred: View) {
        if (!isTelevision(root)) return
        root.post {
            val focused = root.rootView.findFocus()
            if (root.isAttachedToWindow && (focused == null || !focused.isShown || !focused.isEnabled) &&
                preferred.isShown && preferred.isEnabled) {
                preferred.requestFocus()
            }
        }
    }

    fun isTelevision(view: View): Boolean {
        val type = view.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        return type == Configuration.UI_MODE_TYPE_TELEVISION
    }
}
