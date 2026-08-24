package com.wkq.bao.feature.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.lifecycleScope
import androidx.activity.viewModels
import com.wkq.base.activity.BaseActivity
import com.wkq.bao.core.media.router.AppCommand
import com.wkq.bao.core.media.router.AppCommandRouter
import com.wkq.bao.feature.app.databinding.ActivitySplashBinding
import kotlinx.coroutines.launch

/**
 * 圆宝TV 开屏页面
 */
class SplashActivity : BaseActivity<ActivitySplashBinding>() {
    private val viewModel by viewModels<SplashViewModel> { SplashViewModel.Factory(applicationContext) }

    override fun initView() {
        requestNotificationPermissionIfNeeded()
        // 品牌 Logo 淡入与缩放动画
        binding.llBrand.alpha = 0f
        binding.llBrand.scaleX = 0.9f
        binding.llBrand.scaleY = 0.9f

        binding.llBrand.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(800)
            .start()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    override fun initData() {
        lifecycleScope.launch {
            val command = intent?.data?.let(AppCommandRouter::parse)
            if (command != null && routeCommand(command)) return@launch
            navigateToHome(viewModel.resolveInitialPage())
        }
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
            AppCommand.OpenDownloads -> { navigateToHome(MainPageNavigator.DOWNLOADS); true }
            AppCommand.OpenSettings -> { navigateToHome(MainPageNavigator.NAS); true }
            AppCommand.ContinueWatching, is AppCommand.Search -> false
        }
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 100
    }
}
