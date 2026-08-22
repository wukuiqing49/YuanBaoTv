package com.wkq.bao.feature.app

import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.wkq.base.activity.BaseActivity
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.database.entity.EpisodeEntity
import com.wkq.bao.core.media.controller.TvPlayerController
import com.wkq.bao.feature.app.adapter.EpisodeAdapter
import com.wkq.bao.feature.app.databinding.ActivityDetailBinding
import com.wkq.bao.feature.app.utils.TvFocusHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 圆宝TV 详情页
 */
class DetailActivity : BaseActivity<ActivityDetailBinding>() {

    private lateinit var episodeAdapter: EpisodeAdapter
    private val database by lazy { AppDatabase.getInstance(this) }
    private lateinit var playerController: TvPlayerController

    private var seriesId: Long = 1L
    private var currentSeasonId: Long = 1L

    override fun initView() {
        seriesId = intent.getLongExtra("seriesId", 1L)

        // 绑定 TV 焦点缩放
        TvFocusHelper.applyFocusScale(binding.btnPlay)
        TvFocusHelper.applyFocusScale(binding.btnDownloadSeason)
        TvFocusHelper.applyFocusScale(binding.btnFavorite)

        // 初始化播放控制器
        playerController = TvPlayerController(this, lifecycleScope)
        playerController.connect {
            // 已连接 MediaSession 服务
        }

        episodeAdapter = EpisodeAdapter { episode ->
            playEpisode(episode.id)
        }
        binding.rvEpisodes.adapter = episodeAdapter

        binding.btnPlay.setOnClickListener {
            playEpisode(1L)
        }

        binding.btnDownloadSeason.setOnClickListener {
            lifecycleScope.launch {
                val task1 = com.wkq.bao.core.database.entity.DownloadTaskEntity(
                    seriesId = seriesId,
                    seasonId = currentSeasonId,
                    episodeId = 1L,
                    sourceUri = "smb://192.168.1.100/Media/PawPatrol/S01E01.mkv",
                    targetUri = "content://com.android.externalstorage.documents/tree/primary%3AMedia/PawPatrol_S01E01.mkv",
                    totalBytes = 1200000000L,
                    downloadedBytes = 0L,
                    status = "WAITING"
                )
                database.downloadDao().insertTask(task1)
                com.wkq.bao.core.media.download.DownloadForegroundService.enqueueDownload(this@DetailActivity)
                Toast.makeText(this@DetailActivity, "已将该季剧集加入下载队列并启动下载服务", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playEpisode(episodeId: Long) {
        val seriesTitle = binding.tvTitle.text.toString().ifEmpty { "汪汪队立大功" }
        PlayerActivity.start(this, seriesId, episodeId, "$seriesTitle - 第 ${episodeId} 集")
    }

    private fun switchSeason(seasonId: Long) {
        loadEpisodes(seasonId)
    }

    override fun initData() {
        lifecycleScope.launch {
            val series = database.mediaDao().getSeriesById(seriesId)
            if (series != null) {
                binding.tvTitle.text = series.title
                binding.tvMetaTags.text = "${series.year.ifEmpty { "2023" }} • ${series.genre.ifEmpty { "动画 / 益智" }} • ${series.totalSeasons} 季"
                if (series.description.isNotEmpty()) {
                    binding.tvDesc.text = series.description
                }
                if (series.posterUri.isNotEmpty()) {
                    coil.Coil.imageLoader(this@DetailActivity).enqueue(
                        coil.request.ImageRequest.Builder(this@DetailActivity)
                            .data(series.posterUri)
                            .target(binding.ivSeriesPoster)
                            .crossfade(true)
                            .build()
                    )
                }
                if (series.backdropUri.isNotEmpty()) {
                    coil.Coil.imageLoader(this@DetailActivity).enqueue(
                        coil.request.ImageRequest.Builder(this@DetailActivity)
                            .data(series.backdropUri)
                            .target(binding.ivBackdrop)
                            .crossfade(true)
                            .build()
                    )
                }
            }
        }

        // 动态加载分季 Tab
        lifecycleScope.launch {
            database.mediaDao().getSeasonsBySeriesId(seriesId).collectLatest { seasons ->
                binding.llSeasonTabs.removeAllViews()
                val seasonList = if (seasons.isEmpty()) {
                    listOf(
                        com.wkq.bao.core.database.entity.SeasonEntity(1, seriesId, 1, "Season 1"),
                        com.wkq.bao.core.database.entity.SeasonEntity(2, seriesId, 2, "Season 2"),
                        com.wkq.bao.core.database.entity.SeasonEntity(3, seriesId, 3, "Season 3")
                    )
                } else {
                    seasons
                }

                seasonList.forEachIndexed { index, season ->
                    val button = android.widget.Button(this@DetailActivity).apply {
                        text = "Season ${season.seasonNumber}"
                        setTextColor(if (index == 0) getColor(com.wkq.bao.feature.res.R.color.tv_text_primary) else getColor(com.wkq.bao.feature.res.R.color.tv_text_secondary))
                        setBackgroundResource(com.wkq.bao.feature.res.R.drawable.bg_tv_button_focus)
                        val params = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            (36 * resources.displayMetrics.density).toInt()
                        ).apply {
                            if (index > 0) marginStart = (12 * resources.displayMetrics.density).toInt()
                        }
                        layoutParams = params
                        setOnClickListener {
                            switchSeason(season.id)
                        }
                        TvFocusHelper.applyFocusScale(this)
                    }
                    binding.llSeasonTabs.addView(button)
                }

                if (seasonList.isNotEmpty()) {
                    loadEpisodes(seasonList.first().id)
                }
            }
        }
    }

    private fun loadEpisodes(seasonId: Long) {
        currentSeasonId = seasonId
        lifecycleScope.launch {
            database.mediaDao().getEpisodes(seriesId, seasonId).collectLatest { list ->
                if (list.isEmpty()) {
                    // 初始化示例单集列表
                    episodeAdapter.submitList(
                        listOf(
                            EpisodeEntity(1, seriesId, seasonId, 1, "狗狗拯救海象", durationMs = 1200000),
                            EpisodeEntity(2, seriesId, seasonId, 2, "幽灵海盗的宝藏", durationMs = 1200000),
                            EpisodeEntity(3, seriesId, seasonId, 3, "小砾与喷气背包", durationMs = 1200000),
                            EpisodeEntity(4, seriesId, seasonId, 4, "拯救冒险湾大停电", durationMs = 1200000),
                            EpisodeEntity(5, seriesId, seasonId, 5, "天天飞向雪山", durationMs = 1200000)
                        )
                    )
                } else {
                    episodeAdapter.submitList(list)
                }
            }
        }
    }

    override fun onDestroy() {
        playerController.release()
        super.onDestroy()
    }
}
