package com.wkq.bao.feature.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
        listOf(binding.btnHeroPlay, binding.btnHeroDetail).forEach { button ->
            button.backgroundTintList = null
            TvFocusHelper.applyFocusScale(button)
        }
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
        if (state.loading) {
            renderLoadingState()
            return
        }
        continueAdapter.submitList(state.continueWatching)
        posterAdapter.submitList(state.cartoons)
        val hasContinueWatching = state.continueWatching.isNotEmpty()
        binding.tvLabelContinue.visibility = if (hasContinueWatching) View.VISIBLE else View.GONE
        binding.rvContinueWatching.visibility = if (hasContinueWatching) View.VISIBLE else View.GONE
        val hasCartoons = state.cartoons.isNotEmpty()
        binding.tvLabelCartoons.visibility = if (hasCartoons) View.VISIBLE else View.GONE
        binding.rvCartoons.visibility = if (hasCartoons) View.VISIBLE else View.GONE
        val featured = state.featured
        if (featured == null) {
            binding.ivHeroBackdrop.visibility = View.GONE
            binding.vHeroScrim.visibility = View.GONE
            binding.tvHeroTitle.setText(com.wkq.bao.feature.res.R.string.library_empty_title)
            binding.tvHeroSeasonTag.text = ""
            binding.tvHeroSeasonTag.visibility = View.GONE
            binding.tvHeroLastWatch?.visibility = View.GONE
            binding.tvHeroDesc.setText(com.wkq.bao.feature.res.R.string.library_empty_message)
            binding.btnHeroPlay.setText(com.wkq.bao.feature.res.R.string.btn_add_nas)
            binding.btnHeroPlay.isEnabled = true
            binding.btnHeroPlay.visibility = View.VISIBLE
            binding.btnHeroDetail.visibility = View.GONE
            binding.btnHeroPlay.setOnClickListener { navigator().showPage(MainPageNavigator.NAS) }
            return
        }
        binding.ivHeroBackdrop.visibility = View.VISIBLE
        binding.vHeroScrim.visibility = View.VISIBLE
        binding.btnHeroPlay.setText(com.wkq.bao.feature.res.R.string.btn_continue_play)
        binding.btnHeroPlay.isEnabled = true
        binding.btnHeroPlay.visibility = View.VISIBLE
        binding.btnHeroDetail.visibility = View.VISIBLE
        binding.tvHeroSeasonTag.visibility = View.VISIBLE
        binding.tvHeroTitle.text = featured.title
        binding.tvHeroSeasonTag.text = if (MediaSeriesType.isMovie(featured.type)) {
            getString(com.wkq.bao.feature.res.R.string.nav_movie)
        } else {
            getString(com.wkq.bao.feature.res.R.string.label_seasons_count, featured.totalSeasons)
        }
        binding.tvHeroLastWatch?.apply {
            val progress = state.featuredProgress?.takeIf { it.history.seriesId == featured.id }
            visibility = if (progress == null) View.GONE else View.VISIBLE
            text = progress?.let {
                getString(
                    com.wkq.bao.feature.res.R.string.home_last_watch,
                    it.episodeTitle.ifBlank { getString(com.wkq.bao.feature.res.R.string.episode_default_title) }
                )
            }.orEmpty()
        }
        binding.tvHeroDesc.text = featured.description.ifBlank {
            getString(com.wkq.bao.feature.res.R.string.media_description_unavailable)
        }
        binding.ivHeroBackdrop.scaleType = ImageView.ScaleType.CENTER_CROP
        MediaArtwork.load(
            binding.ivHeroBackdrop,
            featured.backdropUri.ifBlank { featured.posterUri },
            com.wkq.bao.feature.res.R.drawable.bg_media_placeholder_landscape
        )
        binding.ivHeroBackdrop.contentDescription = null
        binding.btnHeroPlay.setOnClickListener { viewModel.playFeatured() }
        binding.btnHeroDetail.setOnClickListener { openDetail(featured.id) }
    }

    private fun renderLoadingState() {
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
        binding.ivHeroBackdrop.scaleType = ImageView.ScaleType.FIT_XY
        MediaArtwork.load(
            binding.ivHeroBackdrop,
            null,
            com.wkq.bao.feature.res.R.drawable.bg_media_placeholder_landscape
        )
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
