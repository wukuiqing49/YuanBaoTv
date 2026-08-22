package com.wkq.bao.feature.app

import android.content.Intent
import androidx.lifecycle.lifecycleScope
import com.wkq.base.activity.BaseActivity
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.database.entity.MediaSeriesEntity
import com.wkq.bao.core.database.entity.WatchHistoryEntity
import com.wkq.bao.feature.app.adapter.ContinueWatchingAdapter
import com.wkq.bao.feature.app.adapter.PosterCardAdapter
import com.wkq.bao.feature.app.databinding.ActivityHomeBinding
import com.wkq.bao.feature.app.utils.TvFocusHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 圆宝TV 首页 (优化版)
 */
class HomeActivity : BaseActivity<ActivityHomeBinding>() {

    private lateinit var continueAdapter: ContinueWatchingAdapter
    private lateinit var posterAdapter: PosterCardAdapter
    private val database by lazy { AppDatabase.getInstance(this) }

    override fun initView() {
        // 绑定 TV 焦点缩放
        TvFocusHelper.applyFocusScale(binding.btnHeroPlay)
        TvFocusHelper.applyFocusScale(binding.btnHeroDetail)
        TvFocusHelper.applyFocusScale(binding.btnNavHome)
        TvFocusHelper.applyFocusScale(binding.btnNavLibrary)
        TvFocusHelper.applyFocusScale(binding.btnNavDownloads)
        TvFocusHelper.applyFocusScale(binding.btnNavNas)
        TvFocusHelper.applyFocusScale(binding.btnNavSettings)

        binding.btnNavHome.isSelected = true

        // 初始化 RecyclerView 适配器
        continueAdapter = ContinueWatchingAdapter { item ->
            PlayerActivity.start(this, item.seriesId, item.episodeId, "继续观看")
        }
        binding.rvContinueWatching.adapter = continueAdapter

        posterAdapter = PosterCardAdapter { series ->
            val intent = Intent(this, DetailActivity::class.java).apply {
                putExtra("seriesId", series.id)
            }
            startActivity(intent)
        }
        binding.rvCartoons.adapter = posterAdapter

        setupNavigationEvents()
        updateClock()
    }

    private fun updateClock() {
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        binding.tvClock.text = sdf.format(java.util.Date())
    }

    private fun setupNavigationEvents() {
        binding.btnNavLibrary.setOnClickListener {
            startActivity(Intent(this, MediaLibraryActivity::class.java))
        }
        binding.btnNavDownloads.setOnClickListener {
            startActivity(Intent(this, DownloadsActivity::class.java))
        }
        binding.btnNavNas.setOnClickListener {
            startActivity(Intent(this, NasSettingsActivity::class.java))
        }
        binding.btnNavSettings.setOnClickListener {
            startActivity(Intent(this, NasSettingsActivity::class.java))
        }
        binding.tvNasStatus.setOnClickListener {
            startActivity(Intent(this, NasSettingsActivity::class.java))
        }
        binding.btnHeroPlay.setOnClickListener {
            PlayerActivity.start(this, 1L, 1L, "汪汪队立大功：超级大电影")
        }
        binding.btnHeroDetail.setOnClickListener {
            startActivity(Intent(this, DetailActivity::class.java).putExtra("seriesId", 1L))
        }
    }

    private var heroSeriesId: Long = 1L

    override fun initData() {
        lifecycleScope.launch {
            database.watchHistoryDao().getContinueWatchingList().collectLatest { list ->
                if (list.isEmpty()) {
                    // 初始化示例继续观看数据
                    continueAdapter.submitList(
                        listOf(
                            WatchHistoryEntity(1, 1, 1, 1, 350000L, 720000L, false, System.currentTimeMillis()),
                            WatchHistoryEntity(2, 2, 1, 3, 500000L, 900000L, false, System.currentTimeMillis() - 3600000)
                        )
                    )
                } else {
                    continueAdapter.submitList(list)
                }
            }
        }

        lifecycleScope.launch {
            database.mediaDao().getAllSeries().collectLatest { list ->
                val seriesList = if (list.isEmpty()) {
                    listOf(
                        MediaSeriesEntity(1, "汪汪队立大功", type = "CARTOON", genre = "益智 / 冒险", totalSeasons = 7, description = "精通科技的10岁男孩莱德救了6条小狗，无论遇到什么困难，他们总能团结协作化解危机。", backdropUri = "https://images.unsplash.com/photo-1534447677768-be436bb09401?w=1280&q=80"),
                        MediaSeriesEntity(2, "小猪佩奇", type = "CARTOON", genre = "搞笑 / 亲子", totalSeasons = 9, description = "小猪佩奇是一个可爱的四岁小猪，她和爸爸猪、妈妈猪和弟弟乔治生活在一起，充满温馨与欢笑。", backdropUri = "https://images.unsplash.com/photo-1518791841217-8f162f1e1131?w=1280&q=80"),
                        MediaSeriesEntity(3, "海底小纵队", type = "CARTOON", genre = "科普 / 探险", totalSeasons = 5),
                        MediaSeriesEntity(4, "超级飞侠", type = "CARTOON", genre = "地理 / 友谊", totalSeasons = 12),
                        MediaSeriesEntity(5, "熊出没", type = "CARTOON", genre = "幽默 / 自然", totalSeasons = 10)
                    )
                } else {
                    list
                }
                posterAdapter.submitList(seriesList)

                // 动态刷新 Hero 区域
                val featured = seriesList.firstOrNull()
                if (featured != null) {
                    heroSeriesId = featured.id
                    binding.tvHeroTitle.text = featured.title
                    binding.tvHeroSeasonTag.text = "第 ${featured.totalSeasons} 季 · ${featured.genre.ifEmpty { "动画" }}"
                    if (featured.description.isNotEmpty()) {
                        binding.tvHeroDesc.text = featured.description
                    }
                    if (featured.backdropUri.isNotEmpty()) {
                        coil.Coil.imageLoader(this@HomeActivity).enqueue(
                            coil.request.ImageRequest.Builder(this@HomeActivity)
                                .data(featured.backdropUri)
                                .target(binding.ivHeroBackdrop)
                                .crossfade(true)
                                .build()
                        )
                    }
                    binding.btnHeroPlay.setOnClickListener {
                        PlayerActivity.start(this@HomeActivity, featured.id, 1L, "${featured.title} - 第 1 集")
                    }
                    binding.btnHeroDetail.setOnClickListener {
                        startActivity(Intent(this@HomeActivity, DetailActivity::class.java).putExtra("seriesId", featured.id))
                    }
                }
            }
        }
    }
}
