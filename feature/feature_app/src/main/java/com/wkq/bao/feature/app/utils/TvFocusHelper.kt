package com.wkq.bao.feature.app.utils

import android.content.res.Configuration
import android.os.Build
import androidx.core.view.doOnLayout
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * 电视遥控器焦点放大与动效辅助类
 */
object TvFocusHelper {

    private val focusInterpolator = DecelerateInterpolator(1.5f)
    private const val FOCUS_GLOW_COLOR = 0x88FFD54F.toInt()

    fun applyFocusScale(view: View, scale: Float = 1.08f, onFocusChanged: ((Boolean) -> Unit)? = null) {
        // 明确卡片轮廓，避免部分电视系统无法从状态 drawable 推导圆角。
        if (view.id == com.wkq.bao.feature.app.R.id.card_container) {
            view.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(target: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, target.width, target.height,
                        target.resources.getDimension(com.wkq.bao.feature.res.R.dimen.ui_card_corner_radius))
                }
            }
            view.clipToOutline = true
            view.doOnLayout { it.invalidateOutline() }
        }
        if (!isTelevision(view)) return
        val originalElevation = view.elevation
        val originalSpotShadow = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) view.outlineSpotShadowColor else 0
        view.setOnFocusChangeListener { v, hasFocus ->
            onFocusChanged?.invoke(hasFocus)
            v.animate().cancel()
            v.findViewById<View>(com.wkq.bao.feature.app.R.id.fl_center_play_btn)?.animate()
                ?.alpha(if (hasFocus) 1f else 0f)?.setDuration(160)?.start()
            if (hasFocus) {
                allowFocusOverflow(v)
                if (v.id == com.wkq.bao.feature.app.R.id.card_container) v.post {
                    // ViewPager 会同时保留首页和媒体库；只能滚动当前卡片的祖先。
                    // 从 rootView 查找会拿到兄弟页面的首页，坐标转换因此抛异常。
                    val scroll = generateSequence(v.parent) { it.parent }
                        .filterIsInstance<androidx.core.widget.NestedScrollView>()
                        .firstOrNull { it.id == com.wkq.bao.feature.app.R.id.home_scroll ||
                            it.id == com.wkq.bao.feature.app.R.id.detail_scroll }
                    if (scroll != null && v.isAttachedToWindow && v.hasFocus()) {
                        val rect = android.graphics.Rect()
                        v.getDrawingRect(rect)
                        scroll.offsetDescendantRectToMyCoords(v, rect)
                        val margin = (16 * v.resources.displayMetrics.density).toInt()
                        val bottom = scroll.scrollY + scroll.height - scroll.paddingBottom - margin
                        val top = scroll.scrollY + scroll.paddingTop + margin
                        val delta = if (rect.bottom > bottom) rect.bottom - bottom else if (rect.top < top) rect.top - top else 0
                        if (delta != 0) scroll.smoothScrollBy(0, delta)
                    }
                }
                v.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .setInterpolator(focusInterpolator)
                    .setDuration(160)
                    .start()
                v.elevation = originalElevation + 12f * v.resources.displayMetrics.density
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    v.outlineSpotShadowColor = FOCUS_GLOW_COLOR
                }
            } else {
                v.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setInterpolator(focusInterpolator)
                    .setDuration(160)
                    .start()
                v.elevation = originalElevation
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    v.outlineSpotShadowColor = originalSpotShadow
                }
            }
        }
    }

    /** 只释放当前控件到最近视口之间的布局裁剪，不穿透列表、滚动或分页边界。 */
    private fun allowFocusOverflow(view: View) {
        var parent = view.parent
        while (parent is android.view.ViewGroup) {
            if (isFocusViewport(parent)) {
                clipViewport(parent)
                break
            }
            if (parent is androidx.viewpager2.widget.ViewPager2) break
            // 父布局允许焦点溢出后，列表本身仍须裁剪在视口内，不能画到弹窗操作栏上。
            for (index in 0 until parent.childCount) {
                val child = parent.getChildAt(index)
                if (isFocusViewport(child)) clipViewport(child)
            }
            parent.clipChildren = false
            parent.clipToPadding = false
            if (parent.id == android.R.id.content) break
            parent = parent.parent
        }
    }

    private fun isFocusViewport(view: View): Boolean = view is androidx.recyclerview.widget.RecyclerView ||
        view is androidx.core.widget.NestedScrollView || view is android.widget.ScrollView ||
        view is android.widget.HorizontalScrollView

    private fun clipViewport(view: View) {
        view.doOnLayout { it.clipBounds = android.graphics.Rect(0, 0, it.width, it.height) }
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
        val context = view.context
        val pm = context.packageManager
        if (pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) ||
            pm.hasSystemFeature("android.hardware.type.television")) {
            return true
        }
        val type = view.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        if (type == Configuration.UI_MODE_TYPE_TELEVISION) return true
        val uiModeManager = context.getSystemService(android.content.Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
        if (!pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)) return true
        // 圆宝TV 作为 TV 优先专属应用，默认全部开启大屏焦点动效
        return true
    }
}
