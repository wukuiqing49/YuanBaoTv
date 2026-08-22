package com.wkq.bao.feature.app.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wkq.bao.core.database.entity.MediaSeriesEntity
import com.wkq.bao.feature.app.databinding.ItemPosterCardBinding
import com.wkq.bao.feature.app.utils.TvFocusHelper

class PosterCardAdapter(
    private var items: List<MediaSeriesEntity> = emptyList(),
    private val onItemClick: (MediaSeriesEntity) -> Unit
) : RecyclerView.Adapter<PosterCardAdapter.ViewHolder>() {

    fun submitList(newList: List<MediaSeriesEntity>) {
        items = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPosterCardBinding.inflate(
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

    inner class ViewHolder(private val binding: ItemPosterCardBinding) :
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

        fun bind(item: MediaSeriesEntity) {
            binding.tvTitle.text = item.title
            binding.tvSubtitle.text = "${item.totalSeasons} 季 • ${item.genre.ifEmpty { item.type }}"
            if (item.posterUri.isNotEmpty()) {
                coil.Coil.imageLoader(binding.ivPoster.context).enqueue(
                    coil.request.ImageRequest.Builder(binding.ivPoster.context)
                        .data(item.posterUri)
                        .target(binding.ivPoster)
                        .crossfade(true)
                        .build()
                )
            }
        }
    }
}
