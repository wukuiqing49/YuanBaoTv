package com.wkq.bao.feature.app.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wkq.bao.core.database.entity.MediaSeriesEntity
import com.wkq.bao.core.database.entity.MediaSeriesType
import com.wkq.bao.feature.app.databinding.ItemPosterCardBinding
import com.wkq.bao.feature.app.utils.TvFocusHelper

class PosterCardAdapter(
    private val onItemClick: (MediaSeriesEntity) -> Unit
) : ListAdapter<MediaSeriesEntity, PosterCardAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPosterCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemPosterCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            TvFocusHelper.applyFocusScale(binding.root)
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onItemClick(getItem(pos))
            }
        }

        fun bind(item: MediaSeriesEntity) {
            val context = binding.root.context
            val genre = item.genre.ifEmpty { item.type }
            binding.tvTitle.text = item.title
            binding.tvSubtitle.text = if (MediaSeriesType.isMovie(item.type)) {
                context.getString(com.wkq.bao.feature.res.R.string.poster_movie_subtitle, genre)
            } else {
                context.getString(com.wkq.bao.feature.res.R.string.poster_series_subtitle, item.totalSeasons, genre)
            }
            if (item.posterUri.isNotEmpty()) {
                coil.Coil.imageLoader(context).enqueue(
                    coil.request.ImageRequest.Builder(context)
                        .data(item.posterUri)
                        .target(binding.ivPoster)
                        .crossfade(true)
                        .build()
                )
            } else {
                binding.ivPoster.setImageResource(com.wkq.bao.feature.res.R.drawable.bg_glass_card)
            }
        }
    }

    private companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<MediaSeriesEntity>() {
            override fun areItemsTheSame(oldItem: MediaSeriesEntity, newItem: MediaSeriesEntity): Boolean = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: MediaSeriesEntity, newItem: MediaSeriesEntity): Boolean = oldItem == newItem
        }
    }
}
