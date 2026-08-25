package com.wkq.bao.feature.app

import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.view.isVisible
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
import com.wkq.bao.feature.app.utils.MediaArtwork
import com.wkq.bao.feature.app.utils.MediaLabels
import com.wkq.bao.feature.app.utils.TvFocusHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Room 媒体索引驱动的剧集和电影详情页。 */
class DetailActivity : BaseActivity<ActivityDetailBinding>() {

    private val viewModel by viewModels<DetailViewModel> { DetailViewModel.Factory(applicationContext) }
    private lateinit var episodeAdapter: EpisodeAdapter
    private var seriesId = 0L
    private var renderedSeries: MediaSeriesEntity? = null
    private var renderedSeasons: List<SeasonEntity> = emptyList()

    override fun initView() {
        seriesId = intent.getLongExtra("seriesId", 0L)
        listOf(
            binding.btnPlay,
            binding.btnFavorite,
            binding.btnDetailStateAction
        ).forEach { button ->
            button.backgroundTintList = null
            TvFocusHelper.applyFocusScale(button)
        }
        episodeAdapter = EpisodeAdapter(onItemClick = ::playEpisode)
        binding.rvEpisodes.adapter = episodeAdapter
        binding.btnPlay.setOnClickListener { viewModel.playFirstEpisode() }
        binding.btnDownloadSeason.visibility = View.GONE
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
        renderPageState(state)
        if (state.loading || state.missing || state.loadFailed) return
        val series = state.series ?: return
        if (renderedSeries != series) {
            renderedSeries = series
            renderSeries(series, state.isMovie)
        }
        binding.btnFavorite.setText(
            if (state.isFavorite) com.wkq.bao.feature.res.R.string.btn_unfavorite
            else com.wkq.bao.feature.res.R.string.btn_favorite
        )
        if (state.isMovie) {
            renderMoviePresentation()
        } else {
            setEpisodeSectionVisible(true)
            renderSeasonTabs(state.seasons, state.selectedSeasonId)
            episodeAdapter.submitList(state.episodes)
            val noSeasons = state.seasons.isEmpty()
            val noEpisodes = !noSeasons && state.episodes.isEmpty()
            binding.tvEpisodeEmpty.isVisible = noSeasons || noEpisodes
            binding.tvEpisodeEmpty.setText(
                if (noSeasons) com.wkq.bao.feature.res.R.string.detail_no_seasons
                else com.wkq.bao.feature.res.R.string.detail_no_episodes
            )
            binding.rvEpisodes.isVisible = !noSeasons && !noEpisodes
        }
    }

    private fun renderPageState(state: DetailUiState) {
        val showState = state.loading || state.missing || state.loadFailed
        binding.layoutDetailState.isVisible = showState
        binding.layoutDetailContent?.isVisible = !showState
        binding.groupDetailContent?.isVisible = !showState
        if (!showState) return

        binding.progressDetail.isVisible = state.loading
        binding.btnDetailStateAction.isVisible = !state.loading
        when {
            state.loading -> {
                binding.tvDetailStateTitle.setText(com.wkq.bao.feature.res.R.string.detail_loading)
                binding.tvDetailStateMessage.text = ""
            }
            state.missing -> {
                binding.tvDetailStateTitle.setText(com.wkq.bao.feature.res.R.string.detail_missing_title)
                binding.tvDetailStateMessage.setText(com.wkq.bao.feature.res.R.string.detail_missing_message)
                binding.btnDetailStateAction.setText(com.wkq.bao.feature.res.R.string.detail_back_to_library)
                binding.btnDetailStateAction.setOnClickListener { finish() }
                binding.btnDetailStateAction.requestFocus()
            }
            else -> {
                binding.tvDetailStateTitle.setText(com.wkq.bao.feature.res.R.string.detail_load_failed_title)
                binding.tvDetailStateMessage.setText(com.wkq.bao.feature.res.R.string.detail_load_failed_message)
                binding.btnDetailStateAction.setText(com.wkq.bao.feature.res.R.string.btn_retry)
                binding.btnDetailStateAction.setOnClickListener { viewModel.retry() }
                binding.btnDetailStateAction.requestFocus()
            }
        }
    }

    private fun renderSeries(series: MediaSeriesEntity, isMovie: Boolean) {
        val year = series.year.ifBlank { "-" }
        val genre = MediaLabels.genreOrType(this, series.genre, series.type)
        binding.tvTitle.text = series.title
        binding.tvNasBadge.setText(
            if (isMovie) com.wkq.bao.feature.res.R.string.detail_badge_movie
            else com.wkq.bao.feature.res.R.string.detail_badge_series
        )
        binding.tvMetaTags.text = if (isMovie) {
            getString(com.wkq.bao.feature.res.R.string.detail_movie_meta, year, genre)
        } else {
            getString(com.wkq.bao.feature.res.R.string.detail_series_meta, year, genre, series.totalSeasons)
        }
        binding.tvDesc.text = series.description.ifBlank {
            getString(com.wkq.bao.feature.res.R.string.media_description_unavailable)
        }
        MediaArtwork.load(
            binding.ivSeriesPoster,
            series.posterUri.ifBlank { series.backdropUri },
            com.wkq.bao.feature.res.R.drawable.bg_media_placeholder_poster
        )
        binding.ivSeriesPoster.contentDescription = series.title
        MediaArtwork.load(
            binding.ivBackdrop,
            series.backdropUri.ifBlank { series.posterUri },
            com.wkq.bao.feature.res.R.drawable.bg_media_placeholder_landscape
        )
        binding.ivBackdrop.contentDescription = null
    }

    private fun renderMoviePresentation() {
        renderedSeasons = emptyList()
        binding.llSeasonTabs.removeAllViews()
        setEpisodeSectionVisible(false)
        binding.tvEpisodeEmpty.visibility = View.GONE
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
                        (48 * resources.displayMetrics.density).toInt()
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
        binding.layoutEpisodesSection.visibility = if (visible) View.VISIBLE else View.GONE
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
            EnqueueDownloadsResult.EpisodeQueued -> getString(com.wkq.bao.feature.res.R.string.download_episode_queued)
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
