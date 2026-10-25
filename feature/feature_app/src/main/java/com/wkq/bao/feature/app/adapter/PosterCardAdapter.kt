package com.wkq.bao.feature.app.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.constraintlayout.widget.ConstraintLayout
import com.wkq.bao.core.database.entity.MediaSeriesEntity
import com.wkq.bao.core.database.entity.MediaSeriesType
import com.wkq.bao.feature.app.databinding.ItemPosterCardBinding
import com.wkq.bao.feature.app.utils.MediaArtwork
import com.wkq.bao.feature.app.utils.MediaLabels
import com.wkq.bao.feature.app.utils.TvFocusHelper

class PosterCardAdapter(
    private val onItemClick: (MediaSeriesEntity) -> Unit
) : ListAdapter<MediaSeriesEntity, PosterCardAdapter.ViewHolder>(DIFF_CALLBACK) {
    var onItemFocusChanged: ((MediaSeriesEntity?) -> Unit)? = null
    private var offlineIds: Set<Long> = emptySet()
    fun updateOfflineIds(ids: Set<Long>) {
        val changed = (offlineIds - ids) + (ids - offlineIds)
        offlineIds = ids
        currentList.forEachIndexed { index, item -> if (item.id in changed) notifyItemChanged(index) }
    }

    init {
        setHasStableIds(true)
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    override fun getItemId(position: Int): Long = getItem(position).id

    override fun onViewRecycled(holder: ViewHolder) {
        TvFocusHelper.resetRecycledFocus(holder.itemView)
        super.onViewRecycled(holder)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPosterCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        // 网格占满各自单元格并保持 2:3；首页横向列表保留固定卡片宽度。
        if ((parent as? RecyclerView)?.layoutManager is GridLayoutManager) {
            binding.root.layoutParams = binding.root.layoutParams.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            binding.ivPoster.layoutParams = (binding.ivPoster.layoutParams as ConstraintLayout.LayoutParams).apply {
                dimensionRatio = if (TvFocusHelper.isTelevision(binding.root)) "16:9" else "2:3"
            }
        }
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemPosterCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            TvFocusHelper.applyFocusScale(binding.root, onFocusChanged = { focused ->
                val position = bindingAdapterPosition
                onItemFocusChanged?.invoke(if (focused && position != RecyclerView.NO_POSITION) getItem(position) else null)
            })
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onItemClick(getItem(pos))
            }
        }

        fun bind(item: MediaSeriesEntity) {
            val context = binding.root.context
            binding.tvTitle.text = MediaLabels.displayTitle(item.title)
            binding.tvArtworkFallback?.text = MediaLabels.displayTitle(item.title)
            binding.tvSubtitle.text = if (item.id in offlineIds) {
                context.getString(com.wkq.bao.feature.res.R.string.media_storage_offline)
            } else {
                MediaLabels.metadata(context, item.year, item.genre, item.type, item.totalSeasons)
            }
            MediaArtwork.loadPoster(
                binding.ivPoster,
                if (TvFocusHelper.isTelevision(binding.root)) item.backdropUri.ifBlank { item.posterUri }
                else item.posterUri.ifBlank { item.backdropUri }
            ) { unavailable ->
                binding.tvArtworkFallback?.visibility = if (unavailable) android.view.View.VISIBLE else android.view.View.GONE
            }
            binding.ivPoster.contentDescription = item.title
        }
    }

    private companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<MediaSeriesEntity>() {
            override fun areItemsTheSame(oldItem: MediaSeriesEntity, newItem: MediaSeriesEntity): Boolean = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: MediaSeriesEntity, newItem: MediaSeriesEntity): Boolean = oldItem == newItem
        }
    }
}
