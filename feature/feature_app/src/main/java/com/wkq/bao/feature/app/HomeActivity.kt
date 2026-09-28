package com.wkq.bao.feature.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.wkq.base.activity.BaseActivity
import com.wkq.bao.feature.app.databinding.ActivityHomeHostBinding
import com.wkq.bao.feature.app.utils.TvFocusHelper

/** 主宿主：只管理全局页签、焦点与 ViewPager2，不承载各页面业务。 */
class HomeActivity : BaseActivity<ActivityHomeHostBinding>(), MainPageNavigator {

    companion object {
        const val EXTRA_INITIAL_PAGE = "extra_initial_page"
        private const val MIN_FONT_SCALE = 0.5f
        private const val MAX_TAB_FONT_SCALE = 1.15f

        fun open(context: Context, page: Int) {
            context.startActivity(
                Intent(context, HomeActivity::class.java)
                    .putExtra(EXTRA_INITIAL_PAGE, page)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    private val tabs by lazy { listOf(binding.tabHome, binding.tabLibrary, binding.tabDownloads) }
    private val navigationTabs by lazy { tabs + binding.tabNas }
    override fun initView() {
        configureInsets()
        capTabFontScale()
        binding.vpMainPages.apply {
            adapter = MainPagerAdapter()
            offscreenPageLimit = 2
            isUserInputEnabled = false
            // ViewPager2 内部列表只负责承载页面，不应成为电视遥控器的停留焦点。
            (getChildAt(0) as? RecyclerView)?.isFocusable = false
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    renderSelectedTab(position)
                    restoreVisibleFocus()
                }
            })
        }
        tabs.forEachIndexed { index, tab ->
            TvFocusHelper.applyFocusScale(tab, 1.04f)
            tab.setOnClickListener { showPage(index) }
        }
        TvFocusHelper.applyFocusScale(binding.tabNas, 1.04f)
        binding.tabNas.setOnClickListener { showPage(MainPageNavigator.NAS) }
        applyRequestedPage(intent)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.vpMainPages.currentItem == MainPageNavigator.HOME) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                } else {
                    showPage(MainPageNavigator.HOME)
                    binding.tabHome.requestFocus()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyRequestedPage(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) restoreVisibleFocus()
    }

    private fun applyRequestedPage(intent: Intent) {
        val requestedPage = intent.getIntExtra(EXTRA_INITIAL_PAGE, MainPageNavigator.HOME)
        val initialPage = if (requestedPage == MainPageNavigator.NAS) {
            MainPageNavigator.HOME
        } else {
            requestedPage.coerceIn(0, tabs.lastIndex)
        }
        binding.vpMainPages.setCurrentItem(initialPage, false)
        TvFocusHelper.requestInitialFocus(binding.root, tabs[binding.vpMainPages.currentItem])
        if (requestedPage == MainPageNavigator.NAS) {
            binding.root.post { NasBrowserActivity.start(this) }
        }
    }

    override fun initData() = Unit

    private fun configureInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        val initialBottomPadding = binding.layoutPageTabs.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right
            )
            binding.layoutPageTabs.updatePadding(bottom = initialBottomPadding + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    override fun showPage(page: Int) {
        if (page == MainPageNavigator.NAS) {
            NasBrowserActivity.start(this)
            return
        }
        val target = page.coerceIn(0, tabs.lastIndex)
        binding.vpMainPages.setCurrentItem(target, false)
        renderSelectedTab(target)
        if (TvFocusHelper.isTelevision(binding.root)) tabs[target].requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (TvFocusHelper.isTelevision(binding.root) && currentFocus?.isShown != true) {
            tabs[binding.vpMainPages.currentItem].requestFocus()
        }
        val focused = currentFocus
        if (focused in navigationTabs && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            val current = navigationTabs.indexOf(focused)
            val next = (current + if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1).coerceIn(0, navigationTabs.lastIndex)
            navigationTabs[next].requestFocus()
            if (next < tabs.size) showPage(next)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun restoreVisibleFocus() {
        if (!TvFocusHelper.isTelevision(binding.root)) return
        binding.root.post {
            val focused = currentFocus
            if (focused?.isShown != true || focused === binding.vpMainPages.getChildAt(0)) {
                tabs[binding.vpMainPages.currentItem].requestFocus()
            }
        }
    }

    private fun renderSelectedTab(selected: Int) {
        tabs.forEachIndexed { index, tab ->
            tab.isSelected = index == selected
            tab.setTextColor(getColor(if (index == selected) com.wkq.bao.feature.res.R.color.tv_text_primary else com.wkq.bao.feature.res.R.color.tv_text_secondary))
            tab.setBackgroundResource(if (index == selected) com.wkq.bao.feature.res.R.drawable.bg_nav_link_active else android.R.color.transparent)
        }
    }

    /**
     * 导航栏是固定高度、等宽的三项内容区；仅限制其超大系统字号，
     * 正文仍保留完整无障碍字体缩放能力。
     */
    private fun capTabFontScale() {
        val density = resources.displayMetrics.density
        val fontScale = resources.configuration.fontScale.coerceAtLeast(MIN_FONT_SCALE)
        navigationTabs.forEach { tab ->
            val baseSp = tab.textSize / (density * fontScale)
            tab.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                baseSp * density * minOf(fontScale, MAX_TAB_FONT_SCALE)
            )
        }
    }

    private inner class MainPagerAdapter : FragmentStateAdapter(this) {
        override fun getItemCount(): Int = 3

        override fun createFragment(position: Int): Fragment = when (position) {
            MainPageNavigator.HOME -> HomeFragment()
            MainPageNavigator.LIBRARY -> MediaLibraryFragment()
            else -> DownloadsFragment()
        }
    }
}
