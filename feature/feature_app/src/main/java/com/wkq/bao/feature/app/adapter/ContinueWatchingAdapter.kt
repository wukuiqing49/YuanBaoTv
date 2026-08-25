package com.wkq.bao.feature.app.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wkq.bao.core.database.entity.ContinueWatchingItem
import com.wkq.bao.core.database.entity.WatchHistoryEntity
import com.wkq.bao.feature.app.databinding.ItemContinueWatchingBinding
import com.wkq.bao.feature.app.utils.MediaArtwork
import com.wkq.bao.feature.app.utils.TvFocusHelper

class ContinueWatchingAdapter(
    private val onItemClick: (WatchHistoryEntity) -> Unit
) : ListAdapter<ContinueWatchingItem, ContinueWatchingAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemContinueWatchingBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemContinueWatchingBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            TvFocusHelper.applyFocusScale(binding.root)
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onItemClick(getItem(pos).history)
                }
            }
        }

        fun bind(item: ContinueWatchingItem) {
            val history = item.history
            val remainSec = ((history.durationMs - history.positionMs).coerceAtLeast(0) / 1000).toInt()
            val remainMin = (remainSec / 60).coerceAtLeast(1)
            val episodeTitle = item.episodeTitle.ifBlank {
                binding.root.context.getString(com.wkq.bao.feature.res.R.string.episode_default_title)
            }
            binding.tvTitle.text = binding.root.context.getString(
                com.wkq.bao.feature.res.R.string.continue_watching_item,
                item.seriesTitle,
                episodeTitle,
                remainMin
            )
            (binding.ivBackdrop ?: binding.ivThumbnail)?.let { artwork ->
                MediaArtwork.load(
                    artwork,
                    item.backdropUri,
                    com.wkq.bao.feature.res.R.drawable.bg_media_placeholder_landscape
                )
                artwork.contentDescription = item.seriesTitle
            }
            val progress = if (history.durationMs > 0) {
                ((history.positionMs.toFloat() / history.durationMs) * 100).toInt().coerceIn(0, 100)
            } else 0
            binding.pbProgress.progress = progress
        }
    }

    private companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ContinueWatchingItem>() {
            override fun areItemsTheSame(oldItem: ContinueWatchingItem, newItem: ContinueWatchingItem): Boolean = oldItem.history.episodeId == newItem.history.episodeId
            override fun areContentsTheSame(oldItem: ContinueWatchingItem, newItem: ContinueWatchingItem): Boolean = oldItem == newItem
        }
    }
}
