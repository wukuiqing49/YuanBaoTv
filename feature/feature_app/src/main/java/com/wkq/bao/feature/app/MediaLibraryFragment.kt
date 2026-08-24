package com.wkq.bao.feature.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.wkq.bao.core.database.entity.MediaSeriesType
import com.wkq.bao.feature.app.adapter.PosterCardAdapter
import com.wkq.bao.feature.app.databinding.ActivityMediaLibraryBinding
import com.wkq.bao.feature.app.utils.TvFocusHelper
import kotlinx.coroutines.launch

class MediaLibraryFragment : Fragment() {
    private var _binding: ActivityMediaLibraryBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val viewModel: MediaLibraryViewModel by viewModels { MediaLibraryViewModel.Factory(requireContext()) }
    private lateinit var posterAdapter: PosterCardAdapter

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
        binding.rvMediaGrid.layoutManager = GridLayoutManager(requireContext(), calculateSpanCount(resources.displayMetrics.widthPixels))
        binding.rvMediaGrid.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            val width = right - left
            if (width > 0 && width != oldRight - oldLeft) {
                (binding.rvMediaGrid.layoutManager as? GridLayoutManager)?.spanCount = calculateSpanCount(width)
            }
        }
        binding.rvMediaGrid.adapter = posterAdapter
        binding.btnFilterAll.setOnClickListener { viewModel.selectType(null) }
        binding.btnFilterCartoon.setOnClickListener { viewModel.selectType(MediaSeriesType.CARTOON) }
        binding.btnFilterTv.setOnClickListener { viewModel.selectType(MediaSeriesType.TV) }
        binding.btnFilterMovie.setOnClickListener { viewModel.selectType(MediaSeriesType.MOVIE) }
        TvFocusHelper.requestInitialFocus(binding.root, binding.btnFilterAll)
        observeData()
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::renderState)
            }
        }
    }

    private fun renderState(state: MediaLibraryUiState) {
        posterAdapter.submitList(state.series)
        val isEmpty = state.series.isEmpty()
        binding.rvMediaGrid.visibility = if (isEmpty) View.GONE else View.VISIBLE
        binding.tvEmptyLibrary?.visibility = if (isEmpty) View.VISIBLE else View.GONE
        val selectedType = state.selectedType
        listOf(
            binding.btnFilterAll to null,
            binding.btnFilterCartoon to MediaSeriesType.CARTOON,
            binding.btnFilterTv to MediaSeriesType.TV,
            binding.btnFilterMovie to MediaSeriesType.MOVIE
        ).forEach { (button, type) ->
            button.updateSelection(type == selectedType)
        }
    }

    private fun calculateSpanCount(widthPx: Int): Int {
        val minimumCardWidth = 116f * resources.displayMetrics.density
        return (widthPx / minimumCardWidth).toInt().coerceIn(2, 6)
    }

    private fun Button.updateSelection(selected: Boolean) {
        isSelected = selected
        setTextColor(context.getColor(if (selected) com.wkq.bao.feature.res.R.color.tv_text_primary else com.wkq.bao.feature.res.R.color.tv_text_secondary))
        setBackgroundResource(
            if (selected) com.wkq.bao.feature.res.R.drawable.bg_nav_link_active
            else com.wkq.bao.feature.res.R.drawable.bg_tv_button_focus
        )
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
