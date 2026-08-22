package com.wkq.bao.feature.app

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.wkq.base.activity.BaseActivity
import com.wkq.bao.feature.app.databinding.ActivityHomeHostBinding
import com.wkq.bao.feature.app.utils.TvFocusHelper

/** 主宿主：只管理全局页签、焦点与 ViewPager2，不承载各页面业务。 */
class HomeActivity : BaseActivity<ActivityHomeHostBinding>(), MainPageNavigator {

    companion object {
        const val EXTRA_INITIAL_PAGE = "extra_initial_page"
    }

    private val tabs by lazy { listOf(binding.tabHome, binding.tabLibrary, binding.tabDownloads, binding.tabNas) }

    override fun initView() {
        binding.vpMainPages.apply {
            adapter = MainPagerAdapter()
            offscreenPageLimit = 3
            isUserInputEnabled = false
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) = renderSelectedTab(position)
            })
        }
        tabs.forEachIndexed { index, tab ->
            TvFocusHelper.applyFocusScale(tab, 1.04f)
            tab.setOnClickListener { showPage(index) }
        }
        binding.vpMainPages.setCurrentItem(
            intent.getIntExtra(EXTRA_INITIAL_PAGE, MainPageNavigator.HOME).coerceIn(0, tabs.lastIndex),
            false
        )
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

    override fun initData() = Unit

    override fun showPage(page: Int) {
        binding.vpMainPages.setCurrentItem(page.coerceIn(0, tabs.lastIndex), false)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val focused = currentFocus
        if (focused in tabs && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            val current = tabs.indexOf(focused)
            val next = (current + if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1).coerceIn(0, tabs.lastIndex)
            tabs[next].requestFocus()
            showPage(next)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun renderSelectedTab(selected: Int) {
        tabs.forEachIndexed { index, tab ->
            tab.isSelected = index == selected
            tab.setTextColor(getColor(if (index == selected) com.wkq.bao.feature.res.R.color.tv_text_primary else com.wkq.bao.feature.res.R.color.tv_text_secondary))
            tab.setBackgroundResource(if (index == selected) com.wkq.bao.feature.res.R.drawable.bg_nav_link_active else android.R.color.transparent)
        }
    }

    private inner class MainPagerAdapter : FragmentStateAdapter(this) {
        override fun getItemCount(): Int = 4

        override fun createFragment(position: Int): Fragment = when (position) {
            MainPageNavigator.HOME -> HomeFragment()
            MainPageNavigator.LIBRARY -> MediaLibraryFragment()
            MainPageNavigator.DOWNLOADS -> DownloadsFragment()
            else -> NasSettingsFragment()
        }
    }
}
