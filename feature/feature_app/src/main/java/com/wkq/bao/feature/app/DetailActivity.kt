package com.wkq.bao.feature.app

import android.view.View
import android.content.Intent
import android.os.Bundle
import com.wkq.bao.core.media.repository.LocalMetadataUpdate
import androidx.activity.result.contract.ActivityResultContracts
import com.lxj.xpopup.XPopup
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
    private var editorPopup: MediaEditorPopup? = null
    private var restoredEditor: Bundle? = null
    private var cachePopup: CacheSelectionPopup? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        restoredEditor = savedInstanceState?.getBundle("metadata_editor")
        super.onCreate(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (editorPopup?.isShow == true) editorPopup?.draft()?.let { draft ->
            outState.putBundle("metadata_editor", Bundle().apply {
                putString("title", draft.title); putString("year", draft.year); putString("genre", draft.genre)
                putString("description", draft.description); putString("type", draft.type); putString("poster", draft.posterUri)
            })
        }
        super.onSaveInstanceState(outState)
    }
    private val posterPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && editorPopup?.isShow == true) runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            editorPopup?.setPoster(uri.toString())
        }.onFailure {
            Toast.makeText(this, com.wkq.bao.feature.res.R.string.storage_permission_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun initView() {
        // 详情页为深色背景，覆盖基类可能设置的浅色系统栏图标模式。
        androidx.core.view.WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        seriesId = intent.getLongExtra("seriesId", 0L)
        listOf(
            binding.btnPlay,
            binding.btnFavorite,
            binding.btnDownloadSeason,
            binding.btnEditMetadata,
            binding.btnDetailStateAction
        ).forEach { button ->
            button.backgroundTintList = null
            TvFocusHelper.applyFocusScale(button)
        }
        episodeAdapter = EpisodeAdapter(onItemClick = ::playEpisode)
        binding.rvEpisodes.adapter = episodeAdapter
        binding.btnPlay.setOnClickListener { viewModel.playFirstEpisode() }
        binding.btnDownloadSeason.setOnClickListener { showCacheActions() }
        binding.btnFavorite.setOnClickListener { viewModel.toggleFavorite() }
        binding.btnEditMetadata.setOnClickListener { showMetadataEditor() }
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
        viewModel.initialize(seriesId, intent.getBooleanExtra("cachedOnly", false))
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPlaybackTarget()
    }

    private fun renderState(state: DetailUiState) {
        editorPopup?.setSaving(state.actionInProgress)
        renderPageState(state)
        if (state.loading || state.missing || state.loadFailed) return
        val series = state.series ?: return
        if (restoredEditor != null) showMetadataEditor()
        if (renderedSeries != series) {
            renderedSeries = series
            renderSeries(series, state.isMovie)
        }
        binding.btnDownloadSeason.isVisible = true
        binding.btnDownloadSeason.isEnabled = !state.actionInProgress
        binding.btnDownloadSeason.setText(if (state.cachedOnly) com.wkq.bao.feature.res.R.string.cache_manage_local else if (state.isMovie) com.wkq.bao.feature.res.R.string.cache_movie else com.wkq.bao.feature.res.R.string.cache_select_episodes)
        binding.btnPlay.isEnabled = !state.cachedOnly || state.cachedCopies.isNotEmpty()
        val target = state.playbackTarget
        binding.btnPlay.text = when {
            state.cachedOnly -> getString(com.wkq.bao.feature.res.R.string.cache_play_local)
            target == null -> getString(com.wkq.bao.feature.res.R.string.btn_play_now)
            state.isMovie -> getString(if (target.resume) com.wkq.bao.feature.res.R.string.btn_continue_play else com.wkq.bao.feature.res.R.string.btn_play_now)
            else -> getString(if (target.resume) com.wkq.bao.feature.res.R.string.detail_resume_episode else com.wkq.bao.feature.res.R.string.detail_play_episode,
                target.seasonNumber, target.episode.episodeNumber)
        }
        binding.btnFavorite.isSelected = state.isFavorite
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
                if (state.episodesLoading) com.wkq.bao.feature.res.R.string.detail_loading
                else if (noSeasons) com.wkq.bao.feature.res.R.string.detail_no_seasons
                else com.wkq.bao.feature.res.R.string.detail_no_episodes
            )
            binding.rvEpisodes.isVisible = !noSeasons && !noEpisodes
        }
        TvFocusHelper.requestInitialFocus(binding.root, binding.btnPlay)
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
            val compact = resources.configuration.smallestScreenWidthDp < 600 &&
                !TvFocusHelper.isTelevision(binding.root)
            seasons.forEach { season ->
                val button = android.widget.Button(this).apply {
                    text = getString(com.wkq.bao.feature.res.R.string.season_format, season.seasonNumber)
                    setBackgroundResource(com.wkq.bao.feature.res.R.drawable.bg_tv_button_focus)
                    backgroundTintList = null
                    setTextColor(androidx.core.content.ContextCompat.getColorStateList(context, com.wkq.bao.feature.res.R.color.tv_control_text))
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(com.wkq.bao.feature.res.R.dimen.ui_text_action))
                    if (compact) {
                        isAllCaps = false

                        minWidth = 0
                        minHeight = 0
                        val horizontalPadding = (16 * resources.displayMetrics.density).toInt()
                        setPadding(horizontalPadding, 0, horizontalPadding, 0)
                    }
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        resources.getDimensionPixelSize(com.wkq.bao.feature.res.R.dimen.ui_action_min_height)
                    ).apply { marginEnd = resources.getDimensionPixelSize(com.wkq.bao.feature.res.R.dimen.feature_res_spacing_small) }
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
            tab.isSelected = selected
            tab.setBackgroundResource(
                if (selected) com.wkq.bao.feature.res.R.drawable.bg_nav_link_active
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
            DetailEvent.CopyRemoved -> {
                Toast.makeText(this, com.wkq.bao.feature.res.R.string.cache_removed, Toast.LENGTH_SHORT).show()
                viewModel.refreshPlaybackTarget()
            }
            DetailEvent.MetadataSaved -> {
                editorPopup?.dismiss()
                editorPopup = null
                if (TvFocusHelper.isTelevision(binding.root)) binding.btnEditMetadata.requestFocus()
            }
            is DetailEvent.Play -> playEpisode(event.episode)
            DetailEvent.NoPlayableEpisode -> Toast.makeText(
                this,
                com.wkq.bao.feature.res.R.string.no_playable_episode,
                Toast.LENGTH_SHORT
            ).show()
            is DetailEvent.DownloadsQueued -> showDownloadResult(event.result)
            DetailEvent.ActionFailed -> Toast.makeText(
                this,
                if (editorPopup?.isShow == true) com.wkq.bao.feature.res.R.string.media_edit_failed
                    else com.wkq.bao.feature.res.R.string.download_status_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showCacheActions() {
        val state = viewModel.uiState.value
        if (state.actionInProgress) return
        if (state.cachedOnly) {
            val copies = state.cachedCopies.filter { state.isMovie || it.seasonId == state.selectedSeasonId }
            if (copies.isEmpty()) return
            val labels = copies.map { "S%02dE%02d · %s · %s".format(it.seasonNumber, it.episodeNumber,
                it.storageType, android.text.format.Formatter.formatFileSize(this, it.fileSize)) }.toTypedArray()
            XPopup.Builder(this).isDarkTheme(true).asCenterList(
                getString(com.wkq.bao.feature.res.R.string.cache_manage_local), labels) { index, _ ->
                val copy = copies[index]
                XPopup.Builder(this).isDarkTheme(true).asConfirm(
                    getString(com.wkq.bao.feature.res.R.string.cache_remove_copy),
                    getString(com.wkq.bao.feature.res.R.string.cache_remove_confirmation, copy.uri),
                    { viewModel.removeCopy(copy.episodeId, copy.uri) }).show()
            }.show()
            return
        }
        if (state.isMovie) { enqueueCache(null); return }
        if (state.episodesLoading || cachePopup?.isShow == true) return
        val popup = CacheSelectionPopup(this, state.episodes, emptySet()) { enqueueCache(it) }
        cachePopup = popup
        XPopup.Builder(this).isDarkTheme(true).maxWidth((600 * resources.displayMetrics.density).toInt())
            .maxHeight((resources.displayMetrics.heightPixels * .85f).toInt()).asCustom(popup).show()
    }

    private fun enqueueCache(episodes: List<Long>?) {
        lifecycleScope.launch {
            val target = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.wkq.bao.core.media.storage.TvStorageManager(applicationContext).getAvailableExternalStorageTarget()
            }
            if (target == null) {
                XPopup.Builder(this@DetailActivity).isDarkTheme(true).asConfirm(
                    getString(com.wkq.bao.feature.res.R.string.storage_select_target),
                    getString(com.wkq.bao.feature.res.R.string.storage_target_required),
                    { HomeActivity.open(this@DetailActivity, MainPageNavigator.DOWNLOADS) }).show()
            } else {
                val destination = com.wkq.bao.core.media.repository.DownloadTarget(target.uri.toString(), target.location)
                if (episodes == null) viewModel.enqueueCurrentSelection(destination)
                else viewModel.enqueueSelection(episodes, destination)
            }
        }
    }

    private fun showMetadataEditor() {
        if (editorPopup?.isShow == true) return
        val series = viewModel.uiState.value.series ?: return
        val draft = restoredEditor?.let { LocalMetadataUpdate(it.getString("title", series.title),
            it.getString("year", series.year), it.getString("genre", series.genre),
            it.getString("description", series.description), it.getString("type", series.type), it.getString("poster")) }
        restoredEditor = null
        val popup = MediaEditorPopup(this, series, { posterPicker.launch(arrayOf("image/*")) }, viewModel::saveMetadata, draft)
        editorPopup = popup
        XPopup.Builder(this).isDarkTheme(true)
            .autoOpenSoftInput(false)
            .maxWidth((600 * resources.displayMetrics.density).toInt())
            .maxHeight((resources.displayMetrics.heightPixels * 0.85f).toInt())
            .asCustom(popup).show()
    }

    override fun onDestroy() {
        editorPopup?.dismiss()
        editorPopup = null
        cachePopup?.dismiss()
        cachePopup = null
        super.onDestroy()
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
