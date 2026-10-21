package com.wkq.bao.feature.app.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import android.text.format.Formatter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.DiffUtil
import com.wkq.bao.core.media.repository.CachedSeries
import com.wkq.bao.feature.app.databinding.ItemCachedSeriesBinding
import com.wkq.bao.feature.app.utils.MediaArtwork
import com.wkq.bao.feature.app.utils.TvFocusHelper
import com.wkq.bao.feature.res.R as ResR

class CachedSeriesAdapter(private val onClick: (CachedSeries) -> Unit) : ListAdapter<CachedSeries, CachedSeriesAdapter.Holder>(object : DiffUtil.ItemCallback<CachedSeries>() {
    override fun areItemsTheSame(a: CachedSeries, b: CachedSeries) = a.seriesId == b.seriesId
    override fun areContentsTheSame(a: CachedSeries, b: CachedSeries) = a == b
}) {
    override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Holder(ItemCachedSeriesBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))
    override fun onViewRecycled(holder: Holder) { TvFocusHelper.resetRecycledFocus(holder.itemView); super.onViewRecycled(holder) }
    inner class Holder(private val binding: ItemCachedSeriesBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            TvFocusHelper.applyFocusScale(binding.root, 1.03f)
            binding.root.setOnClickListener { bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { onClick(getItem(it)) } }
        }
        fun bind(item: CachedSeries) {
            binding.tvCachedTitle.text = item.title
            val bytes = Formatter.formatFileSize(binding.root.context, item.totalBytes)
            binding.tvCachedSummary.text = if (item.type == "MOVIE") binding.root.context.getString(ResR.string.cache_movie_summary, bytes)
                else binding.root.context.getString(ResR.string.cache_series_summary, item.seasonCount, item.episodeCount, bytes)
            MediaArtwork.load(binding.ivCachedPoster, item.posterUri, ResR.drawable.bg_media_placeholder_poster)
        }
    }
}
