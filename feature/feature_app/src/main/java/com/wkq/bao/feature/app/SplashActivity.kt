package com.wkq.bao.feature.app

import android.Manifest
import android.app.SearchManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.lifecycleScope
import androidx.activity.viewModels
import com.wkq.base.activity.BaseActivity
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.media.repository.RoomMediaBrowseRepository
import com.wkq.bao.core.media.router.AppCommand
import com.wkq.bao.core.media.router.AppCommandRouter
import com.wkq.bao.feature.app.databinding.ActivitySplashBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 圆宝TV 开屏页面 (支持 Deep Link、各品牌电视系统全局语音搜索与语音直达播放)
 */
class SplashActivity : BaseActivity<ActivitySplashBinding>() {
    private val viewModel by viewModels<SplashViewModel> { SplashViewModel.Factory(applicationContext) }

    companion object {
    }

    override fun initView() {
        // 品牌标题淡入与微缩放动效
        binding.llBrand.alpha = 0f
        binding.llBrand.scaleX = 0.94f
        binding.llBrand.scaleY = 0.94f

        binding.llBrand.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(450)
            .start()

        // 背景插画微缩放镜头推入动效 (Ken Burns 沉浸式电影质感)
        binding.ivSplashBg.scaleX = 1.04f
        binding.ivSplashBg.scaleY = 1.04f
        binding.ivSplashBg.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(600)
            .start()
    }

    override fun initData() {
        lifecycleScope.launch {

            // 优先检查系统语音直接播放请求 (如用户说 "播放流浪地球")，语音播放即刻响应不作人为停留
            if (intent?.action == "android.media.action.MEDIA_PLAY_FROM_SEARCH") {
                val query = extractSearchQuery(intent)
                if (query.isNotBlank() && tryDirectPlayFromSearch(query)) return@launch
            }

            val command = resolveCommandFromIntent(intent)
            if (command != null && routeCommand(command)) return@launch

            // 异步在 IO 线程预热数据库连接与媒体库列表，避免 HomeActivity 冷启动阻塞
            withContext(Dispatchers.IO) {
                runCatching {
                    val db = AppDatabase.getInstance(applicationContext)
                    db.openHelper.writableDatabase
                }
            }

            navigateToHome(viewModel.resolveInitialPage())
        }
    }

    private fun extractSearchQuery(intent: Intent?): String {
        if (intent == null) return ""
        return intent.getStringExtra(SearchManager.QUERY)
            ?: intent.getStringExtra("android.intent.extra.title")
            ?: intent.getStringExtra("query")
            ?: intent.getStringExtra(Intent.EXTRA_TITLE)
            ?: ""
    }

    private suspend fun tryDirectPlayFromSearch(query: String): Boolean = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext false
        val db = AppDatabase.getInstance(applicationContext)
        val allSeries = runCatching { db.mediaDao().getAllSeriesSync() }.getOrNull() ?: return@withContext false
        val matched = allSeries.firstOrNull { series ->
            series.title.equals(trimmed, ignoreCase = true) ||
            series.originalTitle.equals(trimmed, ignoreCase = true)
        } ?: allSeries.firstOrNull { series ->
            series.title.contains(trimmed, ignoreCase = true) ||
            trimmed.contains(series.title, ignoreCase = true)
        } ?: return@withContext false

        val episode = RoomMediaBrowseRepository.create(this@SplashActivity).getPlaybackEpisode(matched.id)
            ?: return@withContext false

        withContext(Dispatchers.Main) {
            PlayerActivity.start(this@SplashActivity, matched.id, episode.seasonId, episode.id, matched.title)
            finish()
        }
        true
    }

    private fun resolveCommandFromIntent(intent: Intent?): AppCommand? {
        if (intent == null) return null
        // 1. 处理 Deep Link (mytv://...)
        intent.data?.let { uri ->
            AppCommandRouter.parse(uri)?.let { return it }
        }
        // 2. 处理各电视助手系统级语音搜索与媒体搜索 Intent
        val action = intent.action
        if (action == "android.media.action.MEDIA_PLAY_FROM_SEARCH" ||
            action == Intent.ACTION_SEARCH
        ) {
            val query = extractSearchQuery(intent)
            if (query.isNotBlank()) {
                return AppCommand.Search(query.trim())
            }
        }
        return null
    }

    private fun navigateTo(target: Class<*>) {
        val intent = Intent(this, target)
        startActivity(intent)
        finish()
    }

    private fun navigateToHome(page: Int) {
        startActivity(Intent(this, HomeActivity::class.java).putExtra(HomeActivity.EXTRA_INITIAL_PAGE, page))
        finish()
    }

    private fun routeCommand(command: AppCommand): Boolean {
        return when (command) {
            is AppCommand.PlayEpisode -> {
                if (command.seriesId <= 0L || command.episodeId <= 0L) false
                else {
                    PlayerActivity.start(this, command.seriesId, command.episodeId, "")
                    finish()
                    true
                }
            }
            is AppCommand.OpenSeries -> {
                if (command.seriesId <= 0L) false
                else {
                    startActivity(Intent(this, DetailActivity::class.java).putExtra("seriesId", command.seriesId))
                    finish()
                    true
                }
            }
            is AppCommand.Search -> {
                val homeIntent = Intent(this, HomeActivity::class.java).apply {
                    putExtra(HomeActivity.EXTRA_INITIAL_PAGE, MainPageNavigator.LIBRARY)
                    putExtra(HomeActivity.EXTRA_SEARCH_QUERY, command.query)
                }
                startActivity(homeIntent)
                finish()
                true
            }
            AppCommand.ContinueWatching -> {
                navigateToHome(MainPageNavigator.HOME)
                true
            }
            AppCommand.OpenDownloads -> {
                navigateToHome(MainPageNavigator.DOWNLOADS)
                true
            }
            AppCommand.OpenSettings -> {
                navigateToHome(MainPageNavigator.NAS)
                true
            }
        }
    }
}
