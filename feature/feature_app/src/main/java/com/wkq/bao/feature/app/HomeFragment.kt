package com.wkq.bao.feature.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.feature.app.adapter.ContinueWatchingAdapter
import com.wkq.bao.feature.app.adapter.PosterCardAdapter
import com.wkq.bao.feature.app.databinding.ActivityHomeBinding
import com.wkq.bao.feature.app.utils.TvFocusHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** 首页内容页，导航由 HomeActivity 统一管理。 */
class HomeFragment : Fragment() {
    private var _binding: ActivityHomeBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val database by lazy { AppDatabase.getInstance(requireContext()) }
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
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            database.watchHistoryDao().getContinueWatchingList().collectLatest(continueAdapter::submitList)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            database.mediaDao().getAllSeries().collectLatest { allSeries ->
                val cartoons = allSeries.filter { it.type == "CARTOON" }
                posterAdapter.submitList(cartoons)
                val featured = cartoons.firstOrNull()
                if (featured == null) {
                    binding.tvHeroTitle.setText(com.wkq.bao.feature.res.R.string.library_empty_title)
                    binding.tvHeroSeasonTag.text = ""
                    binding.tvHeroDesc.setText(com.wkq.bao.feature.res.R.string.library_empty_message)
                    binding.btnHeroPlay.setOnClickListener { navigator().showPage(MainPageNavigator.NAS) }
                    binding.btnHeroDetail.setOnClickListener { navigator().showPage(MainPageNavigator.NAS) }
                    return@collectLatest
                }
                binding.tvHeroTitle.text = featured.title
                binding.tvHeroSeasonTag.text = "${featured.totalSeasons} - ${featured.genre.ifBlank { featured.type }}"
                binding.tvHeroDesc.text = featured.description
                if (featured.backdropUri.isNotBlank()) {
                    coil.Coil.imageLoader(requireContext()).enqueue(
                        coil.request.ImageRequest.Builder(requireContext()).data(featured.backdropUri).target(binding.ivHeroBackdrop).crossfade(true).build()
                    )
                }
                binding.btnHeroPlay.setOnClickListener {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val season = database.mediaDao().getSeasonsSync(featured.id).firstOrNull()
                        val episode = season?.let { database.mediaDao().getEpisodesSync(featured.id, it.id).firstOrNull() }
                        if (episode == null) startActivity(Intent(requireContext(), DetailActivity::class.java).putExtra("seriesId", featured.id))
                        else PlayerActivity.start(requireContext(), featured.id, episode.seasonId, episode.id, episode.title)
                    }
                }
                binding.btnHeroDetail.setOnClickListener {
                    startActivity(Intent(requireContext(), DetailActivity::class.java).putExtra("seriesId", featured.id))
                }
            }
        }
    }

    private fun navigator(): MainPageNavigator = requireActivity() as MainPageNavigator

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
