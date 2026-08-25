package com.wkq.bao.feature.app.utils

import android.widget.ImageView
import androidx.annotation.DrawableRes
import coil.load

/** 统一媒体图片的加载中、空地址和失败状态，避免列表复用时残留旧图。 */
object MediaArtwork {
    fun load(
        imageView: ImageView,
        uri: String?,
        @DrawableRes placeholder: Int
    ) {
        imageView.load(uri?.takeIf(String::isNotBlank)) {
            placeholder(placeholder)
            fallback(placeholder)
            error(placeholder)
            crossfade(true)
        }
    }
}
