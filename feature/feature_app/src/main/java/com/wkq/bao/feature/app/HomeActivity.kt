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
        const val EXTRA_SEARCH_QUERY = "extra_search_query"
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
    var pendingSearchQuery: String? = null
    private var restoredPage: Int? = null
    private val contentFocus = mutableMapOf<Int, java.lang.ref.WeakReference<View>>()
    private var lastNasLaunchTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        restoredPage = savedInstanceState?.getInt("selected_page")
        super.onCreate(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("selected_page", binding.vpMainPages.currentItem)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        com.wkq.bao.core.media.storage.StorageReconnectWorker.enqueue(this, delaySeconds = 2)
    }
    override fun initView() {
        configureInsets()
        capTabFontScale()
        binding.vpMainPages.apply {
            adapter = MainPagerAdapter()
            offscreenPageLimit = 1
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
        if (restoredPage != null) {
            binding.vpMainPages.setCurrentItem(checkNotNull(restoredPage).coerceIn(0, tabs.lastIndex), false)
            restoreVisibleFocus(alignNavigation = true)
        } else {
            applyRequestedPage(intent)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (TvFocusHelper.isTelevision(binding.root) && currentFocus !in navigationTabs) {
                    currentFocus?.let { contentFocus[binding.vpMainPages.currentItem] = java.lang.ref.WeakReference(it) }
                    tabs[binding.vpMainPages.currentItem].requestFocus()
                    return
                }
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
        val searchQuery = intent.getStringExtra(EXTRA_SEARCH_QUERY)
        val requestedPage = if (!searchQuery.isNullOrBlank()) {
            pendingSearchQuery = searchQuery
            MainPageNavigator.LIBRARY
        } else {
            intent.getIntExtra(EXTRA_INITIAL_PAGE, MainPageNavigator.HOME)
        }
        val initialPage = if (requestedPage == MainPageNavigator.NAS) {
            MainPageNavigator.HOME
        } else {
            requestedPage.coerceIn(0, tabs.lastIndex)
        }
        binding.vpMainPages.setCurrentItem(initialPage, false)
        renderSelectedTab(initialPage)
        restoreVisibleFocus(alignNavigation = true)
        if (requestedPage == MainPageNavigator.NAS) {
            binding.root.post { NasBrowserActivity.start(this) }
        }
        if (!searchQuery.isNullOrBlank()) {
            supportFragmentManager.fragments
                .filterIsInstance<MediaLibraryFragment>()
                .firstOrNull()
                ?.applySearchQuery(searchQuery)
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
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastNasLaunchTime > 600L) {
                lastNasLaunchTime = now
                NasBrowserActivity.start(this)
            }
            return
        }
        val target = page.coerceIn(0, tabs.lastIndex)
        binding.vpMainPages.setCurrentItem(target, false)
        renderSelectedTab(target)
        if (TvFocusHelper.isTelevision(binding.root)) tabs[target].requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!TvFocusHelper.isTelevision(binding.root) || event.action != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event)
        }
        val page = binding.vpMainPages.currentItem
        if (currentFocus?.isShown != true) tabs[page].requestFocus()
        val focused = currentFocus
        val pageRoot = supportFragmentManager.findFragmentByTag("f$page")?.view
        if (focused in navigationTabs) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val visibleTabs = navigationTabs.filter { it.isShown && it.isEnabled }
                    val current = visibleTabs.indexOf(focused)
                    val next = (current + if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                        .coerceIn(0, visibleTabs.lastIndex)
                    visibleTabs[next].requestFocus()
                    // 焦点只移动，确认键切页；避免经过页签时意外刷新页面。
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val remembered = contentFocus[page]?.get()?.takeIf { it.isAttachedToWindow && it.isShown && it.isEnabled }
                    val preferredId = when (page) {
                        MainPageNavigator.HOME -> R.id.btn_hero_play
                        MainPageNavigator.LIBRARY -> R.id.btn_filter_all
                        else -> R.id.btn_select_storage
                    }
                    val preferred = pageRoot?.findViewById<View>(preferredId)?.takeIf { it.isShown && it.isEnabled }
                    if (remembered?.requestFocus() != true && preferred?.requestFocus() != true) pageRoot?.requestFocus(View.FOCUS_DOWN)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> return true
            }
        } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && focused != null) {
            val next = focused.focusSearch(View.FOCUS_UP)
            if (next == null || next in navigationTabs) {
                contentFocus[page] = java.lang.ref.WeakReference(focused)
                tabs[page].requestFocus()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun restoreVisibleFocus(alignNavigation: Boolean = false) {
        if (!TvFocusHelper.isTelevision(binding.root)) return
        binding.root.post {
            val focused = currentFocus
            if (focused?.isShown != true || focused === binding.vpMainPages.getChildAt(0) ||
                (alignNavigation && focused in navigationTabs)) {
                tabs[binding.vpMainPages.currentItem].requestFocus()
            }
        }
    }

    private fun renderSelectedTab(selected: Int) {
        if (TvFocusHelper.isTelevision(binding.root)) {
            val immersive = selected == MainPageNavigator.HOME
            val params = binding.vpMainPages.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            params.topToTop = if (immersive) androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID else -1
            params.topToBottom = if (immersive) -1 else binding.layoutPageTabs.id
            params.topMargin = if (immersive) 0 else (12 * resources.displayMetrics.density).toInt()
            binding.vpMainPages.layoutParams = params
        }
        tabs.forEachIndexed { index, tab ->
            tab.isSelected = index == selected
            tab.setTextColor(androidx.appcompat.content.res.AppCompatResources.getColorStateList(this, com.wkq.bao.feature.res.R.color.tv_navigation_text))
            tab.setBackgroundResource(com.wkq.bao.feature.res.R.drawable.bg_nav_link_active)
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
