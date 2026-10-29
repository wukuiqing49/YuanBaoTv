package com.wkq.bao.feature.app.utils

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.core.view.doOnLayout
import coil.load
import coil.size.Scale
import com.wkq.bao.feature.app.R
import com.wkq.bao.feature.res.R as ResR

/** 封面等比例铺满，图片轮廓独立于加载中、成功和失败的 drawable。 */
object MediaArtwork {
    fun loadPoster(imageView: ImageView, uri: String?, onUnavailable: (Boolean) -> Unit = {}) {
        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
        configureCorners(imageView)
        onUnavailable(uri.isNullOrBlank())
        imageView.load(uri?.takeIf(String::isNotBlank)) {
            scale(Scale.FILL)
            placeholder(ResR.drawable.bg_poster_image_surface)
            fallback(ResR.drawable.bg_poster_image_surface)
            error(ResR.drawable.bg_poster_image_surface)
            crossfade(true)
            listener(onError = { _, _ -> onUnavailable(true) }, onSuccess = { _, result ->
                // 横卡遇到竖海报（或反向）完整容纳主体，保留中性底色。
                val drawable = result.drawable
                val artWide = drawable.intrinsicWidth > drawable.intrinsicHeight
                val cardWide = imageView.width > imageView.height
                imageView.scaleType = if (artWide != cardWide) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
                onUnavailable(false)
            })
        }
    }

    fun load(imageView: ImageView, uri: String?, @DrawableRes placeholder: Int) {
        val card = imageView.parent as? View
        val isCover = card?.id == R.id.card_container || imageView.id == R.id.iv_cached_poster
        if (isCover) imageView.scaleType = ImageView.ScaleType.CENTER_CROP
        if (isCover || imageView.clipToOutline) configureCorners(imageView)
        imageView.load(uri?.takeIf(String::isNotBlank)) {
            if (imageView.scaleType == ImageView.ScaleType.CENTER_CROP) scale(Scale.FILL)
            placeholder(placeholder)
            fallback(placeholder)
            error(placeholder)
            crossfade(true)
        }
    }

    private fun configureCorners(imageView: ImageView) {
        val card = (imageView.parent as? View)?.takeIf { it.id == R.id.card_container }
        val outerRadius = imageView.resources.getDimension(ResR.dimen.ui_card_corner_radius)
        val innerRadius = imageView.resources.getDimension(ResR.dimen.ui_artwork_corner_radius)
        // 有内边距的图文卡片使用内圆角；满版选集与详情海报沿用外圆角。
        val radius = when {
            imageView.id == R.id.iv_series_poster -> outerRadius
            card != null -> (outerRadius - card.paddingLeft).coerceAtLeast(0f)
            else -> innerRadius
        }
        imageView.clipToOutline = true
        imageView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        imageView.doOnLayout { it.invalidateOutline() }
    }
}
