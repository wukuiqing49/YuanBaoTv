package com.wkq.bao.feature.app

import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.wkq.base.activity.BaseActivity
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.database.entity.DownloadTaskEntity
import com.wkq.bao.core.database.entity.DownloadTaskStatus
import com.wkq.bao.core.database.entity.EpisodeEntity
import com.wkq.bao.core.database.entity.FavoriteEntity
import com.wkq.bao.core.media.download.DownloadForegroundService
import com.wkq.bao.core.media.storage.TvStorageManager
import com.wkq.bao.feature.app.adapter.EpisodeAdapter
import com.wkq.bao.feature.app.databinding.ActivityDetailBinding
import com.wkq.bao.feature.app.utils.TvFocusHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** 由 Room 媒体索引驱动的剧集详情页。 */
class DetailActivity : BaseActivity<ActivityDetailBinding>() {

    private lateinit var episodeAdapter: EpisodeAdapter
    private val database by lazy { AppDatabase.getInstance(this) }
    private var seriesId = 0L
    private var currentSeasonId = 0L
    private var episodeCollectionJob: Job? = null

    override fun initView() {
        seriesId = intent.getLongExtra("seriesId", 0L)
        listOf(binding.btnPlay, binding.btnDownloadSeason, binding.btnFavorite).forEach(TvFocusHelper::applyFocusScale)
        episodeAdapter = EpisodeAdapter(onItemClick = ::playEpisode)
        binding.rvEpisodes.adapter = episodeAdapter
        binding.btnPlay.setOnClickListener { playFirstEpisode() }
        binding.btnDownloadSeason.setOnClickListener { enqueueCurrentSeason() }
        binding.btnFavorite.setOnClickListener { toggleFavorite() }
    }

    override fun initData() {
        if (seriesId <= 0L) {
            finish()
            return
        }
        lifecycleScope.launch {
            val series = database.mediaDao().getSeriesById(seriesId) ?: run { finish(); return@launch }
            binding.tvTitle.text = series.title
            binding.tvMetaTags.text = "${series.year.ifBlank { "-" }} - ${series.genre.ifBlank { series.type }} - ${series.totalSeasons}"
            binding.tvDesc.text = series.description
            if (series.posterUri.isNotBlank()) {
                coil.Coil.imageLoader(this@DetailActivity).enqueue(
                    coil.request.ImageRequest.Builder(this@DetailActivity).data(series.posterUri).target(binding.ivSeriesPoster).crossfade(true).build()
                )
            }
            if (series.backdropUri.isNotBlank()) {
                coil.Coil.imageLoader(this@DetailActivity).enqueue(
                    coil.request.ImageRequest.Builder(this@DetailActivity).data(series.backdropUri).target(binding.ivBackdrop).crossfade(true).build()
                )
            }
        }
        lifecycleScope.launch { renderFavoriteState() }
        lifecycleScope.launch {
            database.mediaDao().getSeasonsBySeriesId(seriesId).collectLatest { seasons ->
                binding.llSeasonTabs.removeAllViews()
                seasons.forEach { season ->
                    val button = android.widget.Button(this@DetailActivity).apply {
                        text = getString(com.wkq.bao.feature.res.R.string.season_format, season.seasonNumber)
                        setBackgroundResource(com.wkq.bao.feature.res.R.drawable.bg_tv_button_focus)
                        setTextColor(getColor(com.wkq.bao.feature.res.R.color.tv_text_primary))
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            (40 * resources.displayMetrics.density).toInt()
                        ).apply { marginEnd = (12 * resources.displayMetrics.density).toInt() }
                        setOnClickListener { loadEpisodes(season.id) }
                        TvFocusHelper.applyFocusScale(this)
                    }
                    binding.llSeasonTabs.addView(button)
                }
                if (currentSeasonId == 0L && seasons.isNotEmpty()) loadEpisodes(seasons.first().id)
            }
        }
    }

    private fun loadEpisodes(seasonId: Long) {
        currentSeasonId = seasonId
        episodeCollectionJob?.cancel()
        episodeCollectionJob = lifecycleScope.launch {
            database.mediaDao().getEpisodesWithSource(seriesId, seasonId).collectLatest(episodeAdapter::submitList)
        }
    }

    private fun playFirstEpisode() {
        if (currentSeasonId == 0L) return
        lifecycleScope.launch {
            val episode = database.mediaDao().getEpisodesSync(seriesId, currentSeasonId).firstOrNull()
            if (episode == null) Toast.makeText(this@DetailActivity, "当前季没有可播放剧集", Toast.LENGTH_SHORT).show()
            else playEpisode(episode)
        }
    }

    private fun playEpisode(episode: EpisodeEntity) {
        PlayerActivity.start(this, seriesId, episode.seasonId, episode.id, episode.title)
    }

    private fun enqueueCurrentSeason() {
        lifecycleScope.launch {
            if (currentSeasonId == 0L) return@launch
            val storageTarget = TvStorageManager(this@DetailActivity).getAvailableStorageTarget()
            if (storageTarget == null) {
                Toast.makeText(this@DetailActivity, com.wkq.bao.feature.res.R.string.storage_target_required, Toast.LENGTH_LONG).show()
                return@launch
            }
            val episodes = database.mediaDao().getEpisodesSync(seriesId, currentSeasonId)
            var queuedCount = 0
            episodes.forEach { episode ->
                val mediaFile = database.mediaDao().getMediaFileByEpisodeId(episode.id) ?: return@forEach
                if (!mediaFile.localUri.isNullOrBlank() || mediaFile.nasUri.isBlank()) return@forEach
                val existing = database.downloadDao().getTaskByEpisodeId(episode.id)
                if (existing == null || existing.status in setOf(DownloadTaskStatus.FAILED, DownloadTaskStatus.CANCELLED)) {
                    database.downloadDao().insertTask(
                        DownloadTaskEntity(
                            id = existing?.id ?: 0L,
                            seriesId = seriesId,
                            seasonId = currentSeasonId,
                            episodeId = episode.id,
                            sourceUri = mediaFile.nasUri,
                            targetUri = storageTarget.uri.toString(),
                            targetStorageType = storageTarget.location.name,
                            status = DownloadTaskStatus.WAITING
                        )
                    )
                    queuedCount++
                }
            }
            if (queuedCount > 0) DownloadForegroundService.enqueueDownload(this@DetailActivity)
            Toast.makeText(
                this@DetailActivity,
                getString(com.wkq.bao.feature.res.R.string.download_season_queued, queuedCount),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun toggleFavorite() {
        lifecycleScope.launch {
            val existing = database.favoriteDao().getBySeriesId(seriesId)
            if (existing == null) database.favoriteDao().insert(FavoriteEntity(seriesId = seriesId))
            else database.favoriteDao().delete(existing)
            renderFavoriteState()
        }
    }

    private suspend fun renderFavoriteState() {
        val isFavorite = database.favoriteDao().getBySeriesId(seriesId) != null
        binding.btnFavorite.setText(
            if (isFavorite) com.wkq.bao.feature.res.R.string.btn_unfavorite
            else com.wkq.bao.feature.res.R.string.btn_favorite
        )
    }

    override fun onDestroy() {
        episodeCollectionJob?.cancel()
        super.onDestroy()
    }
}
