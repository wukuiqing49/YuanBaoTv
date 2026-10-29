package com.wkq.bao.feature.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.updateLayoutParams
import androidx.core.view.doOnPreDraw
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wkq.bao.core.database.entity.MediaSeriesType
import com.wkq.bao.feature.app.adapter.ContinueWatchingAdapter
import com.wkq.bao.feature.app.adapter.PosterCardAdapter
import com.wkq.bao.feature.app.databinding.ActivityHomeBinding
import com.wkq.bao.feature.app.utils.MediaArtwork
import com.wkq.bao.feature.app.utils.TvFocusHelper
import kotlinx.coroutines.launch

/** 首页内容页，导航由 HomeActivity 统一管理。 */
class HomeFragment : Fragment() {
    private var _binding: ActivityHomeBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val viewModel: HomeViewModel by viewModels { HomeViewModel.Factory(requireContext()) }
    private lateinit var continueAdapter: ContinueWatchingAdapter
    private lateinit var posterAdapter: PosterCardAdapter
    private val categoryAdapters = mutableListOf<PosterCardAdapter>()
    private var previewJob: kotlinx.coroutines.Job? = null

    /** 主视觉标题被滚出导航下方后，不再更新用户看不到的预览。 */
    private fun canPreviewHero(): Boolean {
        val title = binding.tvHeroTitle
        val visible = android.graphics.Rect()
        if (binding.layoutHeroContent.alpha < 1f || !title.getGlobalVisibleRect(visible) || visible.height() < title.height) return false
        val rootLocation = IntArray(2)
        binding.root.getLocationOnScreen(rootLocation)
        return visible.top >= rootLocation[1] + (68 * resources.displayMetrics.density).toInt()
    }

    private fun enablePreview(adapter: PosterCardAdapter) {
        adapter.onItemFocusChanged = { series ->
            previewJob?.cancel()
            if (series != null && TvFocusHelper.isTelevision(binding.root) && canPreviewHero()) {
                previewJob = viewLifecycleOwner.lifecycleScope.launch {
                    kotlinx.coroutines.delay(250)
                    if (canPreviewHero()) viewModel.previewSeries(series.id)
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ActivityHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.topNavBar.visibility = View.GONE
        setupViews()
        binding.root.findViewById<androidx.core.widget.NestedScrollView>(R.id.home_scroll)?.setOnScrollChangeListener(
            androidx.core.widget.NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
                val density = resources.displayMetrics.density
                // 内容随滚动自然离屏，保持可见性与遥控焦点一致。
                binding.layoutHeroContent.alpha = 1f
                binding.ivHeroBackdrop.alpha = (1f - scrollY / (360f * density)).coerceIn(0.18f, 1f)
                if (scrollY > 0) previewJob?.cancel()
            }
        )
        observeData()
    }

    private fun setupViews() {
        if (TvFocusHelper.isTelevision(binding.root)) {
            val res = com.wkq.bao.feature.res.R.dimen.ui_focus_padding
            val focusPadding = resources.getDimensionPixelSize(res)
            // 按当前限定符和字体缩放测量卡片，避免电视首次测量为零或大字被固定行高裁切。
            fun sizeRow(row: androidx.recyclerview.widget.RecyclerView, layout: Int) {
                val sample = layoutInflater.inflate(layout, row, false)
                sample.findViewById<android.widget.TextView>(R.id.tv_title)?.text = "M\nM"
                sample.findViewById<android.widget.TextView>(R.id.tv_subtitle)?.text = if (layout == R.layout.item_continue_watching) "M\nM" else "M"
                sample.measure(
                    View.MeasureSpec.makeMeasureSpec(sample.layoutParams.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                row.layoutParams = row.layoutParams.apply { height = sample.measuredHeight + focusPadding * 2 }
            }
            sizeRow(binding.rvContinueWatching, R.layout.item_continue_watching)
            (listOf(binding.rvCartoons) + listOf(binding.homeSections.rvMovies,
                binding.homeSections.rvSeries, binding.homeSections.rvAnimation)).forEach {
                sizeRow(it, R.layout.item_poster_card)
            }
        }
        listOf(binding.btnHeroPlay, binding.btnHeroDetail).forEach { button ->
            button.backgroundTintList = null
            (button as? com.google.android.material.button.MaterialButton)?.iconTint =
                androidx.appcompat.content.res.AppCompatResources.getColorStateList(requireContext(), if (button == binding.btnHeroPlay) com.wkq.bao.feature.res.R.color.home_primary_text else com.wkq.bao.feature.res.R.color.tv_button_text)
            TvFocusHelper.applyFocusScale(button)
        }
        continueAdapter = ContinueWatchingAdapter { item ->
            PlayerActivity.start(requireContext(), item.seriesId, item.seasonId, item.episodeId, continueAdapter.currentList.firstOrNull { it.history.episodeId == item.episodeId }?.seriesTitle.orEmpty())
        }
        binding.rvContinueWatching.adapter = continueAdapter
        posterAdapter = PosterCardAdapter { series ->
            startActivity(Intent(requireContext(), DetailActivity::class.java).putExtra("seriesId", series.id))
        }
        binding.rvCartoons.adapter = posterAdapter
        enablePreview(posterAdapter)
        listOf(binding.homeSections.rvMovies, binding.homeSections.rvSeries, binding.homeSections.rvAnimation).forEach { row ->
            val adapter = PosterCardAdapter { series -> openDetail(series.id) }
            categoryAdapters += adapter
            row.adapter = adapter
        }
        listOf(
            binding.rvContinueWatching,
            binding.rvCartoons,
            binding.homeSections.rvMovies,
            binding.homeSections.rvSeries,
            binding.homeSections.rvAnimation
        ).forEach { rv ->
            rv.itemAnimator = null
            rv.setHasFixedSize(true)
        }
        binding.tvClock.text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        TvFocusHelper.requestInitialFocus(binding.root, binding.btnHeroPlay)
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.offlineSeriesIds.collect { ids ->
                    posterAdapter.updateOfflineIds(ids)
                    categoryAdapters.forEach { it.updateOfflineIds(ids) }
                } }
                launch {
                    viewModel.uiState.collect(::renderState)
                }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is HomeEvent.Play -> PlayerActivity.start(
                                requireContext(), event.seriesId, event.episode.seasonId, event.episode.id, event.episode.title
                            )
                            is HomeEvent.OpenDetail -> openDetail(event.seriesId)
                        }
                    }
                }
            }
        }
    }

    private fun renderState(state: HomeUiState) {
        if (state.loading) {
            renderLoadingState()
            return
        }
        binding.root.doOnPreDraw {
            context?.let { com.wkq.bao.core.base.diagnostics.AppDiagnostics.finishTiming(it, "home_first_frame") }
        }
        continueAdapter.submitList(state.continueWatching)
        posterAdapter.submitList(state.cartoons)
        val hasContinueWatching = state.continueWatching.isNotEmpty()
        if (TvFocusHelper.isTelevision(binding.root)) {
            val height = resources.getDimensionPixelSize(com.wkq.bao.feature.res.R.dimen.home_backdrop_height)
            binding.ivHeroBackdrop.updateLayoutParams { this.height = height }
            binding.vHeroScrim.updateLayoutParams { this.height = height }
        }
        binding.tvLabelContinue.visibility = if (hasContinueWatching) View.VISIBLE else View.GONE
        binding.rvContinueWatching.visibility = if (hasContinueWatching) View.VISIBLE else View.GONE
        val hasCartoons = state.cartoons.isNotEmpty()
        binding.tvLabelCartoons.visibility = if (hasCartoons) View.VISIBLE else View.GONE
        binding.rvCartoons.visibility = if (hasCartoons) View.VISIBLE else View.GONE
        val categories = listOf(state.movies, state.series, state.animation)
        val containers = listOf(binding.homeSections.sectionMovies, binding.homeSections.sectionSeries, binding.homeSections.sectionAnimation)
        categories.forEachIndexed { index, items ->
            categoryAdapters[index].submitList(items)
            containers[index].visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        }
        val featured = state.featured
        if (featured == null) {
            binding.tvHeroDesc.maxLines = 4
            binding.tvHeroDesc.visibility = View.VISIBLE
            binding.ivHeroBackdrop.visibility = View.GONE
            binding.vHeroScrim.visibility = View.GONE
            binding.tvHeroTitle.setText(com.wkq.bao.feature.res.R.string.home_welcome_title)
            binding.tvHeroSeasonTag.text = ""
            binding.tvHeroSeasonTag.visibility = View.GONE
            binding.tvHeroLastWatch?.visibility = View.GONE
            binding.tvHeroDesc.maxLines = 3
            binding.tvHeroDesc.setText(com.wkq.bao.feature.res.R.string.home_welcome_message)
            binding.btnHeroPlay.setText(com.wkq.bao.feature.res.R.string.btn_add_local_media)
            binding.btnHeroPlay.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
            (binding.btnHeroPlay as? com.google.android.material.button.MaterialButton)?.icon = null
            binding.btnHeroPlay.isEnabled = true
            binding.btnHeroPlay.visibility = View.VISIBLE
            binding.btnHeroDetail.setText(com.wkq.bao.feature.res.R.string.home_connect_server)
            binding.btnHeroDetail.visibility = View.VISIBLE
            binding.btnHeroPlay.setOnClickListener { navigator().openLocalMedia() }
            binding.btnHeroDetail.setOnClickListener { navigator().showPage(MainPageNavigator.NAS) }
            TvFocusHelper.requestInitialFocus(binding.root, binding.btnHeroPlay)
            return
        }
        binding.ivHeroBackdrop.visibility = View.VISIBLE
        binding.tvHeroDesc.maxLines = 2
        binding.vHeroScrim.visibility = View.VISIBLE
        binding.btnHeroPlay.setText(if (state.featuredProgress?.history?.let {
                it.seriesId == featured.id && !it.completed
            } == true) com.wkq.bao.feature.res.R.string.btn_continue_play
            else com.wkq.bao.feature.res.R.string.btn_play_now)
        (binding.btnHeroPlay as? com.google.android.material.button.MaterialButton)?.setIconResource(com.wkq.bao.feature.res.R.drawable.ic_tv_play)
        binding.btnHeroPlay.isEnabled = true
        binding.btnHeroPlay.visibility = View.VISIBLE
        binding.btnHeroDetail.visibility = View.VISIBLE
        binding.btnHeroDetail.setText(com.wkq.bao.feature.res.R.string.btn_view_detail)
        binding.tvHeroSeasonTag.visibility = View.VISIBLE
        binding.tvHeroTitle.text = com.wkq.bao.feature.app.utils.MediaLabels.displayTitle(featured.title)
        binding.tvHeroSeasonTag.text = com.wkq.bao.feature.app.utils.MediaLabels.metadata(requireContext(), featured.year, featured.genre, featured.type, featured.totalSeasons)
        binding.tvHeroLastWatch?.apply {
            val progress = state.featuredProgress?.takeIf { it.history.seriesId == featured.id }
            visibility = if (progress == null || MediaSeriesType.isMovie(featured.type)) View.GONE else View.VISIBLE
            text = progress?.let {
                getString(
                    if (it.history.durationMs == 0L) com.wkq.bao.feature.res.R.string.home_next_watch else com.wkq.bao.feature.res.R.string.home_last_watch,
                    it.episodeTitle.ifBlank { getString(com.wkq.bao.feature.res.R.string.episode_default_title) }
                )
            }.orEmpty()
        }
        binding.tvHeroDesc.text = featured.description.ifBlank {
            getString(com.wkq.bao.feature.res.R.string.media_description_unavailable)
        }
        binding.tvHeroDesc.visibility = if (featured.description.isBlank()) View.GONE else View.VISIBLE
        if (TvFocusHelper.isTelevision(binding.root)) binding.tvHeroDesc.maxLines = 1
        binding.ivHeroBackdrop.scaleType = if (featured.backdropUri.isNotBlank()) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        val heroArt = featured.backdropUri.takeIf(String::isNotBlank)
            ?: featured.posterUri.takeIf(String::isNotBlank)
        MediaArtwork.load(
            binding.ivHeroBackdrop,
            heroArt,
            com.wkq.bao.feature.res.R.drawable.bg_home_artwork_placeholder
        )
        binding.ivHeroBackdrop.contentDescription = null
        binding.btnHeroPlay.setOnClickListener { viewModel.playFeatured() }
        binding.btnHeroDetail.setOnClickListener { openDetail(featured.id) }
        TvFocusHelper.requestInitialFocus(binding.root, binding.btnHeroPlay)
    }

    private fun renderLoadingState() {
        categoryAdapters.forEach { it.submitList(emptyList()) }
        listOf(binding.homeSections.sectionMovies, binding.homeSections.sectionSeries, binding.homeSections.sectionAnimation)
            .forEach { it.visibility = View.GONE }
        continueAdapter.submitList(emptyList())
        posterAdapter.submitList(emptyList())
        binding.tvLabelContinue.visibility = View.GONE
        binding.rvContinueWatching.visibility = View.GONE
        binding.tvLabelCartoons.visibility = View.GONE
        binding.rvCartoons.visibility = View.GONE
        binding.tvHeroTitle.setText(com.wkq.bao.feature.res.R.string.splash_loading)
        binding.tvHeroSeasonTag.visibility = View.GONE
        binding.tvHeroLastWatch?.visibility = View.GONE
        binding.tvHeroDesc.text = ""
        binding.btnHeroPlay.visibility = View.GONE
        binding.btnHeroDetail.visibility = View.GONE
        binding.ivHeroBackdrop.visibility = View.VISIBLE
        binding.vHeroScrim.visibility = View.VISIBLE
        binding.ivHeroBackdrop.scaleType = ImageView.ScaleType.CENTER_CROP
        MediaArtwork.load(
            binding.ivHeroBackdrop,
            null,
            com.wkq.bao.feature.res.R.drawable.bg_home_artwork_placeholder
        )
    }

    private fun openDetail(seriesId: Long) {
        startActivity(Intent(requireContext(), DetailActivity::class.java).putExtra("seriesId", seriesId))
    }

    private fun navigator(): MainPageNavigator = requireActivity() as MainPageNavigator

    override fun onDestroyView() {
        previewJob?.cancel()
        posterAdapter.onItemFocusChanged = null
        categoryAdapters.forEach { it.onItemFocusChanged = null }
        categoryAdapters.clear()
        binding.rvContinueWatching.adapter = null
        binding.rvCartoons.adapter = null
        listOf(binding.homeSections.rvMovies, binding.homeSections.rvSeries, binding.homeSections.rvAnimation)
            .forEach { it.adapter = null }
        _binding = null
        super.onDestroyView()
    }
}
