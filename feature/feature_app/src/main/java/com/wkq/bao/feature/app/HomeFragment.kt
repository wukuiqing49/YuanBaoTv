package com.wkq.bao.feature.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wkq.bao.core.database.entity.MediaSeriesType
import com.wkq.bao.feature.app.adapter.ContinueWatchingAdapter
import com.wkq.bao.feature.app.adapter.PosterCardAdapter
import com.wkq.bao.feature.app.databinding.ActivityHomeBinding
import com.wkq.bao.feature.app.utils.TvFocusHelper
import kotlinx.coroutines.launch

/** 首页内容页，导航由 HomeActivity 统一管理。 */
class HomeFragment : Fragment() {
    private var _binding: ActivityHomeBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val viewModel: HomeViewModel by viewModels { HomeViewModel.Factory(requireContext()) }
    private lateinit var continueAdapter: ContinueWatchingAdapter
    private lateinit var posterAdapter: PosterCardAdapter
    private var renderedFeatured: com.wkq.bao.core.database.entity.MediaSeriesEntity? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ActivityHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.topNavBar.visibility = View.GONE
        setupViews()
        observeData()
    }

    private fun setupViews() {
        listOf(binding.btnHeroPlay, binding.btnHeroDetail).forEach(TvFocusHelper::applyFocusScale)
        continueAdapter = ContinueWatchingAdapter { item ->
            PlayerActivity.start(requireContext(), item.seriesId, item.seasonId, item.episodeId, getString(com.wkq.bao.feature.res.R.string.btn_continue_play))
        }
        binding.rvContinueWatching.adapter = continueAdapter
        posterAdapter = PosterCardAdapter { series ->
            startActivity(Intent(requireContext(), DetailActivity::class.java).putExtra("seriesId", series.id))
        }
        binding.rvCartoons.adapter = posterAdapter
        binding.tvClock.text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        TvFocusHelper.requestInitialFocus(binding.root, binding.btnHeroPlay)
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
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
        continueAdapter.submitList(state.continueWatching)
        posterAdapter.submitList(state.cartoons)
        val featured = state.featured
        if (featured == null) {
            renderedFeatured = null
            binding.tvHeroTitle.setText(com.wkq.bao.feature.res.R.string.library_empty_title)
            binding.tvHeroSeasonTag.text = ""
            binding.tvHeroDesc.setText(com.wkq.bao.feature.res.R.string.library_empty_message)
            binding.ivHeroBackdrop.setImageResource(com.wkq.bao.feature.res.R.drawable.bg_glass_card)
            binding.btnHeroPlay.setOnClickListener { navigator().showPage(MainPageNavigator.NAS) }
            binding.btnHeroDetail.setOnClickListener { navigator().showPage(MainPageNavigator.NAS) }
            return
        }
        if (renderedFeatured == featured) return
        renderedFeatured = featured
        binding.tvHeroTitle.text = featured.title
        binding.tvHeroSeasonTag.text = if (MediaSeriesType.isMovie(featured.type)) {
            getString(com.wkq.bao.feature.res.R.string.nav_movie)
        } else {
            getString(com.wkq.bao.feature.res.R.string.label_seasons_count, featured.totalSeasons)
        }
        binding.tvHeroDesc.text = featured.description
        if (featured.backdropUri.isNotBlank()) {
            coil.Coil.imageLoader(requireContext()).enqueue(
                coil.request.ImageRequest.Builder(requireContext()).data(featured.backdropUri).target(binding.ivHeroBackdrop).crossfade(true).build()
            )
        } else {
            binding.ivHeroBackdrop.setImageResource(com.wkq.bao.feature.res.R.drawable.bg_glass_card)
        }
        binding.btnHeroPlay.setOnClickListener { viewModel.playFeatured() }
        binding.btnHeroDetail.setOnClickListener { openDetail(featured.id) }
    }

    private fun openDetail(seriesId: Long) {
        startActivity(Intent(requireContext(), DetailActivity::class.java).putExtra("seriesId", seriesId))
    }

    private fun navigator(): MainPageNavigator = requireActivity() as MainPageNavigator

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
