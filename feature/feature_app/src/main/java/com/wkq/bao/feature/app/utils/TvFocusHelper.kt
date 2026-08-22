package com.wkq.bao.feature.app.utils

import android.view.View

/**
 * 电视遥控器焦点放大与动效辅助类
 */
object TvFocusHelper {

    fun applyFocusScale(view: View, scale: Float = 1.08f) {
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
}
