package com.wkq.bao.feature.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.feature.app.adapter.PosterCardAdapter
import com.wkq.bao.feature.app.databinding.ActivityMediaLibraryBinding
import com.wkq.bao.feature.app.utils.TvFocusHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MediaLibraryFragment : Fragment() {
    private var _binding: ActivityMediaLibraryBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private lateinit var posterAdapter: PosterCardAdapter
    private var currentType: String? = null
    private var mediaCollectionJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ActivityMediaLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listOf(binding.btnFilterAll, binding.btnFilterCartoon, binding.btnFilterTv, binding.btnFilterMovie).forEach(TvFocusHelper::applyFocusScale)
        posterAdapter = PosterCardAdapter { series ->
            startActivity(Intent(requireContext(), DetailActivity::class.java).putExtra("seriesId", series.id))
        }
        binding.rvMediaGrid.adapter = posterAdapter
        binding.btnFilterAll.setOnClickListener { filterByType(null) }
        binding.btnFilterCartoon.setOnClickListener { filterByType("CARTOON") }
        binding.btnFilterTv.setOnClickListener { filterByType("TV") }
        binding.btnFilterMovie.setOnClickListener { filterByType("MOVIE") }
        loadMedia()
    }

    private fun filterByType(type: String?) {
        currentType = type
        loadMedia()
    }

    private fun loadMedia() {
        mediaCollectionJob?.cancel()
        mediaCollectionJob = viewLifecycleOwner.lifecycleScope.launch {
            val flow = currentType?.let(database.mediaDao()::getSeriesByType) ?: database.mediaDao().getAllSeries()
            flow.collectLatest(posterAdapter::submitList)
        }
    }

    override fun onDestroyView() {
        mediaCollectionJob?.cancel()
        _binding = null
        super.onDestroyView()
    }
}
