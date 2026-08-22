package com.wkq.bao.feature.app.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wkq.bao.core.database.entity.WatchHistoryEntity
import com.wkq.bao.feature.app.databinding.ItemContinueWatchingBinding
import com.wkq.bao.feature.app.utils.TvFocusHelper

class ContinueWatchingAdapter(
    private var items: List<WatchHistoryEntity> = emptyList(),
    private val onItemClick: (WatchHistoryEntity) -> Unit
) : RecyclerView.Adapter<ContinueWatchingAdapter.ViewHolder>() {

    fun submitList(newList: List<WatchHistoryEntity>) {
        items = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemContinueWatchingBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemContinueWatchingBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            TvFocusHelper.applyFocusScale(binding.root)
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onItemClick(items[pos])
                }
            }
        }

        fun bind(item: WatchHistoryEntity) {
            val remainSec = ((item.durationMs - item.positionMs).coerceAtLeast(0) / 1000).toInt()
            val remainMin = (remainSec / 60).coerceAtLeast(1)
            binding.tvTitle.text = "汪汪队立大功 E0${item.episodeId} • 剩 ${remainMin}分"
            val progress = if (item.durationMs > 0) {
                ((item.positionMs.toFloat() / item.durationMs) * 100).toInt().coerceIn(0, 100)
            } else 45
            binding.pbProgress.progress = progress
        }
    }
}
