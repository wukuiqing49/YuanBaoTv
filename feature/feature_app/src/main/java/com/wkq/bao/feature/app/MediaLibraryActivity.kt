package com.wkq.bao.feature.app

import android.content.Intent
import androidx.lifecycle.lifecycleScope
import com.wkq.base.activity.BaseActivity
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.database.entity.MediaSeriesEntity
import com.wkq.bao.feature.app.adapter.PosterCardAdapter
import com.wkq.bao.feature.app.databinding.ActivityMediaLibraryBinding
import com.wkq.bao.feature.app.utils.TvFocusHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 圆宝TV 媒体库海报墙页面
 */
class MediaLibraryActivity : BaseActivity<ActivityMediaLibraryBinding>() {

    private lateinit var posterAdapter: PosterCardAdapter
    private val database by lazy { AppDatabase.getInstance(this) }
    private var currentType: String? = null

    override fun initView() {
        TvFocusHelper.applyFocusScale(binding.btnFilterAll)
        TvFocusHelper.applyFocusScale(binding.btnFilterCartoon)
        TvFocusHelper.applyFocusScale(binding.btnFilterTv)
        TvFocusHelper.applyFocusScale(binding.btnFilterMovie)

        posterAdapter = PosterCardAdapter { series ->
            val intent = Intent(this, DetailActivity::class.java).apply {
                putExtra("seriesId", series.id)
            }
            startActivity(intent)
        }
        binding.rvMediaGrid.adapter = posterAdapter

        binding.btnFilterAll.setOnClickListener { filterByType(null) }
        binding.btnFilterCartoon.setOnClickListener { filterByType("CARTOON") }
        binding.btnFilterTv.setOnClickListener { filterByType("TV") }
        binding.btnFilterMovie.setOnClickListener { filterByType("MOVIE") }
    }

    private fun filterByType(type: String?) {
        currentType = type
        loadMedia()
    }

    override fun initData() {
        loadMedia()
    }

    private fun loadMedia() {
        lifecycleScope.launch {
            val flow = if (currentType != null) {
                database.mediaDao().getSeriesByType(currentType!!)
            } else {
                database.mediaDao().getAllSeries()
            }

            flow.collectLatest { list ->
                if (list.isEmpty()) {
                    posterAdapter.submitList(
                        listOf(
                            MediaSeriesEntity(1, "汪汪队立大功", type = "CARTOON", genre = "益智 / 冒险", totalSeasons = 7),
                            MediaSeriesEntity(2, "小猪佩奇", type = "CARTOON", genre = "搞笑 / 亲子", totalSeasons = 9),
                            MediaSeriesEntity(3, "星际穿越", type = "MOVIE", genre = "科幻 / 剧情", totalSeasons = 1),
                            MediaSeriesEntity(4, "地球脉动", type = "TV", genre = "纪录片 / 自然", totalSeasons = 3),
                            MediaSeriesEntity(5, "银翼杀手2049", type = "MOVIE", genre = "动作 / 科幻", totalSeasons = 1),
                            MediaSeriesEntity(6, "千与千寻", type = "CARTOON", genre = "动画 / 奇幻", totalSeasons = 1),
                            MediaSeriesEntity(7, "角斗士", type = "MOVIE", genre = "动作 / 历史", totalSeasons = 1),
                            MediaSeriesEntity(8, "七宗罪", type = "MOVIE", genre = "悬疑 / 惊悚", totalSeasons = 1)
                        )
                    )
                } else {
                    posterAdapter.submitList(list)
                }
            }
        }
    }
}
