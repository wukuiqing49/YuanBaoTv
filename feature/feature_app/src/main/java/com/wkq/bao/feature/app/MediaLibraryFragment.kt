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
        listOf(
            binding.btnFilterAll,
            binding.btnFilterCartoon,
            binding.btnFilterTv,
            binding.btnFilterMovie,
            binding.btnEmptyLibraryAction
        ).forEach { button ->
            button.backgroundTintList = null
            TvFocusHelper.applyFocusScale(button)
        }
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
        val isEmpty = !state.loading && state.series.isEmpty()
        binding.rvMediaGrid.visibility = if (state.loading || isEmpty) View.GONE else View.VISIBLE
        binding.layoutEmptyLibrary.visibility = if (state.loading || isEmpty) View.VISIBLE else View.GONE
        binding.progressLibrary.visibility = if (state.loading) View.VISIBLE else View.GONE
        binding.ivEmptyLibrary.visibility = if (state.loading) View.GONE else View.VISIBLE
        binding.tvEmptyLibraryTitle.setText(
            if (state.loading) com.wkq.bao.feature.res.R.string.splash_loading
            else com.wkq.bao.feature.res.R.string.library_empty_title
        )
        binding.tvEmptyLibrary.visibility = if (state.loading) View.GONE else View.VISIBLE
        binding.btnEmptyLibraryAction.visibility = if (state.loading) View.GONE else View.VISIBLE
        binding.tvEmptyLibrary.setText(
            if (state.selectedType == null) {
                com.wkq.bao.feature.res.R.string.library_empty_message
            } else {
                com.wkq.bao.feature.res.R.string.library_filter_empty_message
            }
        )
        binding.btnEmptyLibraryAction.setText(
            if (state.selectedType == null) {
                com.wkq.bao.feature.res.R.string.btn_add_nas
            } else {
                com.wkq.bao.feature.res.R.string.nav_all_media
            }
        )
        binding.btnEmptyLibraryAction.setOnClickListener {
            if (state.selectedType == null) {
                (requireActivity() as MainPageNavigator).showPage(MainPageNavigator.NAS)
            } else {
                viewModel.selectType(null)
            }
        }
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
        val minimumCardWidthDp = if (resources.configuration.smallestScreenWidthDp >= 600) 176f else 116f
        val minimumCardWidth = minimumCardWidthDp * resources.displayMetrics.density
        return (widthPx / minimumCardWidth).toInt().coerceIn(2, 6)
    }

    private fun Button.updateSelection(selected: Boolean) {
        isSelected = selected
        setTextColor(context.getColor(if (selected) com.wkq.bao.feature.res.R.color.tv_text_primary else com.wkq.bao.feature.res.R.color.tv_text_secondary))
        setBackgroundResource(
            if (selected) com.wkq.bao.feature.res.R.drawable.bg_nav_link_active
            else com.wkq.bao.feature.res.R.drawable.bg_tv_button_focus
        )
        backgroundTintList = null
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
