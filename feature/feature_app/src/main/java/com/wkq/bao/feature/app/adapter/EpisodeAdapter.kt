package com.wkq.bao.feature.app.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wkq.bao.core.database.entity.EpisodeEntity
import com.wkq.bao.feature.app.databinding.ItemEpisodeCardBinding
import com.wkq.bao.feature.app.utils.TvFocusHelper

class EpisodeAdapter(
    private var items: List<EpisodeEntity> = emptyList(),
    private val onItemClick: (EpisodeEntity) -> Unit
) : RecyclerView.Adapter<EpisodeAdapter.ViewHolder>() {

    fun submitList(newList: List<EpisodeEntity>) {
        items = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemEpisodeCardBinding.inflate(
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

    inner class ViewHolder(private val binding: ItemEpisodeCardBinding) :
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

        fun bind(item: EpisodeEntity) {
            binding.tvEpisodeNum.text = "第 %02d 集".format(item.episodeNumber)
            binding.tvEpisodeTitle.text = item.title.ifEmpty { "单集正片" }
            binding.tvBadge.text = "NAS 在线"
            binding.tvBadge.setBackgroundResource(com.wkq.bao.feature.res.R.drawable.bg_badge_nas)

            if (item.thumbnailUri.isNotEmpty()) {
                coil.Coil.imageLoader(binding.root.context).enqueue(
                    coil.request.ImageRequest.Builder(binding.root.context)
                        .data(item.thumbnailUri)
                        .target(binding.ivThumbnail)
                        .crossfade(true)
                        .build()
                )
            }
        }
    }
}
