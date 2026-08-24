package com.wkq.bao.feature.app

import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wkq.base.activity.BaseActivity
import com.wkq.bao.core.database.entity.EpisodeEntity
import com.wkq.bao.core.database.entity.MediaSeriesEntity
import com.wkq.bao.core.database.entity.SeasonEntity
import com.wkq.bao.core.media.repository.EnqueueDownloadsResult
import com.wkq.bao.feature.app.adapter.EpisodeAdapter
import com.wkq.bao.feature.app.databinding.ActivityDetailBinding
import com.wkq.bao.feature.app.utils.TvFocusHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Room 媒体索引驱动的剧集和电影详情页。 */
class DetailActivity : BaseActivity<ActivityDetailBinding>() {

    private val viewModel by viewModels<DetailViewModel> { DetailViewModel.Factory(applicationContext) }
    private lateinit var episodeAdapter: EpisodeAdapter
    private var seriesId = 0L
    private var renderedSeriesId = 0L
    private var renderedSeasons: List<SeasonEntity> = emptyList()

    override fun initView() {
        seriesId = intent.getLongExtra("seriesId", 0L)
        listOf(binding.btnPlay, binding.btnDownloadSeason, binding.btnFavorite).forEach(TvFocusHelper::applyFocusScale)
        episodeAdapter = EpisodeAdapter(onItemClick = ::playEpisode)
        binding.rvEpisodes.adapter = episodeAdapter
        binding.btnPlay.setOnClickListener { viewModel.playFirstEpisode() }
        binding.btnDownloadSeason.setOnClickListener { viewModel.enqueueCurrentSelection() }
        binding.btnFavorite.setOnClickListener { viewModel.toggleFavorite() }
        TvFocusHelper.requestInitialFocus(binding.root, binding.btnPlay)
    }

    override fun initData() {
        if (seriesId <= 0L) {
            finish()
            return
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collectLatest(::renderState) }
                launch { viewModel.events.collect(::handleEvent) }
            }
        }
        viewModel.initialize(seriesId)
    }

    private fun renderState(state: DetailUiState) {
        if (state.missing) {
            finish()
            return
        }
        val series = state.series ?: return
        if (renderedSeriesId != series.id) {
            renderedSeriesId = series.id
            renderSeries(series, state.isMovie)
        }
        binding.btnFavorite.setText(
            if (state.isFavorite) com.wkq.bao.feature.res.R.string.btn_unfavorite
            else com.wkq.bao.feature.res.R.string.btn_favorite
        )
        binding.btnDownloadSeason.isEnabled = !state.actionInProgress
        if (state.isMovie) {
            renderMoviePresentation()
        } else {
            setEpisodeSectionVisible(true)
            renderSeasonTabs(state.seasons, state.selectedSeasonId)
            episodeAdapter.submitList(state.episodes)
        }
    }

    private fun renderSeries(series: MediaSeriesEntity, isMovie: Boolean) {
        val year = series.year.ifBlank { "-" }
        val genre = series.genre.ifBlank { series.type }
        binding.tvTitle.text = series.title
        binding.tvMetaTags.text = if (isMovie) {
            getString(com.wkq.bao.feature.res.R.string.detail_movie_meta, year, genre)
        } else {
            getString(com.wkq.bao.feature.res.R.string.detail_series_meta, year, genre, series.totalSeasons)
        }
        binding.tvDesc.text = series.description
        binding.btnDownloadSeason.setText(
            if (isMovie) com.wkq.bao.feature.res.R.string.btn_download_movie
            else com.wkq.bao.feature.res.R.string.btn_download_season
        )
        if (series.posterUri.isNotBlank()) {
            coil.Coil.imageLoader(this).enqueue(
                coil.request.ImageRequest.Builder(this)
                    .data(series.posterUri)
                    .target(binding.ivSeriesPoster)
                    .crossfade(true)
                    .build()
            )
        }
        if (series.backdropUri.isNotBlank()) {
            coil.Coil.imageLoader(this).enqueue(
                coil.request.ImageRequest.Builder(this)
                    .data(series.backdropUri)
                    .target(binding.ivBackdrop)
                    .crossfade(true)
                    .build()
            )
        }
    }

    private fun renderMoviePresentation() {
        renderedSeasons = emptyList()
        binding.llSeasonTabs.removeAllViews()
        setEpisodeSectionVisible(false)
        episodeAdapter.submitList(emptyList())
    }

    private fun renderSeasonTabs(seasons: List<SeasonEntity>, selectedSeasonId: Long) {
        if (renderedSeasons != seasons) {
            renderedSeasons = seasons
            binding.llSeasonTabs.removeAllViews()
            seasons.forEach { season ->
                val button = android.widget.Button(this).apply {
                    text = getString(com.wkq.bao.feature.res.R.string.season_format, season.seasonNumber)
                    setBackgroundResource(com.wkq.bao.feature.res.R.drawable.bg_tv_button_focus)
                    setTextColor(getColor(com.wkq.bao.feature.res.R.color.tv_text_primary))
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        (40 * resources.displayMetrics.density).toInt()
                    ).apply { marginEnd = (12 * resources.displayMetrics.density).toInt() }
                    tag = season.id
                    setOnClickListener { viewModel.selectSeason(season.id) }
                    TvFocusHelper.applyFocusScale(this)
                }
                binding.llSeasonTabs.addView(button)
            }
        }
        updateSelectedSeasonTab(selectedSeasonId)
    }

    private fun setEpisodeSectionVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        binding.root.findViewById<View?>(R.id.layout_episodes_section)?.visibility = visibility
        (binding.llSeasonTabs.parent as? View)?.visibility = visibility
        binding.rvEpisodes.visibility = visibility
    }

    private fun updateSelectedSeasonTab(selectedSeasonId: Long) {
        for (index in 0 until binding.llSeasonTabs.childCount) {
            val tab = binding.llSeasonTabs.getChildAt(index)
            val selected = tab.tag == selectedSeasonId
            tab.setBackgroundResource(
                if (selected) com.wkq.bao.feature.res.R.drawable.bg_hero_play_btn
                else com.wkq.bao.feature.res.R.drawable.bg_tv_button_focus
            )
            (tab as? TextView)?.setTextColor(
                getColor(
                    if (selected) com.wkq.bao.feature.res.R.color.tv_hero_btn_text
                    else com.wkq.bao.feature.res.R.color.tv_text_primary
                )
            )
        }
    }

    private fun handleEvent(event: DetailEvent) {
        when (event) {
            is DetailEvent.Play -> playEpisode(event.episode)
            DetailEvent.NoPlayableEpisode -> Toast.makeText(
                this,
                com.wkq.bao.feature.res.R.string.no_playable_episode,
                Toast.LENGTH_SHORT
            ).show()
            is DetailEvent.DownloadsQueued -> showDownloadResult(event.result)
            DetailEvent.ActionFailed -> Toast.makeText(
                this,
                com.wkq.bao.feature.res.R.string.download_status_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showDownloadResult(result: EnqueueDownloadsResult) {
        val message = when (result) {
            EnqueueDownloadsResult.StorageTargetRequired -> getString(com.wkq.bao.feature.res.R.string.storage_target_required)
            EnqueueDownloadsResult.NoItemsQueued -> getString(com.wkq.bao.feature.res.R.string.download_no_items_queued)
            EnqueueDownloadsResult.MovieQueued -> getString(com.wkq.bao.feature.res.R.string.download_movie_queued)
            is EnqueueDownloadsResult.SeasonQueued -> getString(
                com.wkq.bao.feature.res.R.string.download_season_queued,
                result.count
            )
        }
        Toast.makeText(
            this,
            message,
            if (result == EnqueueDownloadsResult.StorageTargetRequired) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        ).show()
    }

    private fun playEpisode(episode: EpisodeEntity) {
        PlayerActivity.start(this, seriesId, episode.seasonId, episode.id, episode.title)
    }
}
