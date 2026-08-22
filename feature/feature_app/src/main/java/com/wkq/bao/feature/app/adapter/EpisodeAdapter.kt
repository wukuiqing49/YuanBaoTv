package com.wkq.bao.feature.app.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wkq.bao.core.database.entity.EpisodeEntity
import com.wkq.bao.core.database.entity.EpisodeWithSource
import com.wkq.bao.core.media.storage.MediaStorageLocation
import com.wkq.bao.feature.app.databinding.ItemEpisodeCardBinding
import com.wkq.bao.feature.app.utils.TvFocusHelper

class EpisodeAdapter(
    private var items: List<EpisodeWithSource> = emptyList(),
    private val onItemClick: (EpisodeEntity) -> Unit
) : RecyclerView.Adapter<EpisodeAdapter.ViewHolder>() {

    fun submitList(newList: List<EpisodeWithSource>) {
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
                    onItemClick(items[pos].episode)
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

            if (item.episode.thumbnailUri.isNotEmpty()) {
                coil.Coil.imageLoader(binding.root.context).enqueue(
                    coil.request.ImageRequest.Builder(binding.root.context)
                        .data(item.episode.thumbnailUri)
                        .target(binding.ivThumbnail)
                        .crossfade(true)
                        .build()
                )
            }
        }
    }
}
