package com.wkq.bao.feature.app

import android.content.Intent
import androidx.lifecycle.lifecycleScope
import com.wkq.base.activity.BaseActivity
import com.wkq.bao.feature.app.databinding.ActivitySplashBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 圆宝TV 开屏页面
 */
class SplashActivity : BaseActivity<ActivitySplashBinding>() {

    override fun initView() {
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

    override fun initData() {
        lifecycleScope.launch {
            // 等待资源与数据库初始化完成
            delay(1500)
            navigateToHome()
        }
    }

    private fun navigateToHome() {
        val intent = Intent(this, HomeActivity::class.java)
        startActivity(intent)
        finish()
    }
}
