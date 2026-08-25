package com.wkq.bao.feature.app.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wkq.bao.core.database.entity.EpisodeEntity
import com.wkq.bao.core.database.entity.EpisodeWithSource
import com.wkq.bao.core.media.storage.MediaStorageLocation
import com.wkq.bao.feature.app.databinding.ItemEpisodeCardBinding
import com.wkq.bao.feature.app.utils.MediaArtwork
import com.wkq.bao.feature.app.utils.TvFocusHelper

class EpisodeAdapter(
    private val onItemClick: (EpisodeEntity) -> Unit
) : ListAdapter<EpisodeWithSource, EpisodeAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemEpisodeCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemEpisodeCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            TvFocusHelper.applyFocusScale(binding.root)
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onItemClick(getItem(pos).episode)
                }
            }
        }

        fun bind(item: EpisodeWithSource) {
            binding.tvEpisodeNum.text = binding.root.context.getString(
                com.wkq.bao.feature.res.R.string.episode_format,
                item.episode.episodeNumber
            )
            binding.tvEpisodeTitle.text = item.episode.title.ifEmpty {
                binding.root.context.getString(com.wkq.bao.feature.res.R.string.episode_default_title)
            }
            val source = MediaStorageLocation.fromStored(item.localStorageType)
            val (labelRes, backgroundRes) = when {
                !item.localUri.isNullOrBlank() && source == MediaStorageLocation.TF_CARD ->
                    com.wkq.bao.feature.res.R.string.badge_tf_card to com.wkq.bao.feature.res.R.drawable.bg_badge_local
                !item.localUri.isNullOrBlank() && source == MediaStorageLocation.USB_DRIVE ->
                    com.wkq.bao.feature.res.R.string.badge_usb_drive to com.wkq.bao.feature.res.R.drawable.bg_badge_local
                !item.localUri.isNullOrBlank() ->
                    com.wkq.bao.feature.res.R.string.badge_downloaded to com.wkq.bao.feature.res.R.drawable.bg_badge_local
                !item.nasUri.isNullOrBlank() ->
                    com.wkq.bao.feature.res.R.string.badge_nas_stream to com.wkq.bao.feature.res.R.drawable.bg_badge_nas
                else -> com.wkq.bao.feature.res.R.string.badge_offline to com.wkq.bao.feature.res.R.drawable.bg_badge_local
            }
            binding.tvBadge.setText(labelRes)
            binding.tvBadge.setBackgroundResource(backgroundRes)

            MediaArtwork.load(
                binding.ivThumbnail,
                item.episode.thumbnailUri.ifBlank { item.seriesBackdropUri.orEmpty() },
                com.wkq.bao.feature.res.R.drawable.bg_media_placeholder_landscape
            )
            binding.ivThumbnail.contentDescription = binding.tvEpisodeTitle.text
        }
    }

    private companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<EpisodeWithSource>() {
            override fun areItemsTheSame(oldItem: EpisodeWithSource, newItem: EpisodeWithSource): Boolean = oldItem.episode.id == newItem.episode.id
            override fun areContentsTheSame(oldItem: EpisodeWithSource, newItem: EpisodeWithSource): Boolean = oldItem == newItem
        }
    }
}
