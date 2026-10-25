package com.wkq.bao.feature.app

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.GridLayoutManager
import com.lxj.xpopup.core.CenterPopupView
import com.wkq.bao.core.database.entity.EpisodeWithSource
import com.wkq.bao.feature.app.databinding.ItemCacheChoiceBinding
import com.wkq.bao.feature.app.databinding.PopupCacheSelectionBinding
import com.wkq.bao.feature.app.utils.TvFocusHelper
import com.wkq.bao.feature.res.R as ResR

internal class CacheSelectionPopup(
    context: Context,
    private val episodes: List<EpisodeWithSource>,
    initialSelection: Set<Long>,
    private val onConfirm: (List<Long>) -> Unit
) : CenterPopupView(context) {

    private val selected = initialSelection.toMutableSet()
    private lateinit var binding: PopupCacheSelectionBinding

    override val implLayoutId: Int
        get() = R.layout.popup_cache_selection

    fun selection(): LongArray = selected.toLongArray()

    override fun onCreate() {
        super.onCreate()
        binding = PopupCacheSelectionBinding.bind(checkNotNull(contentView))
        val available = episodes.filter { it.localUri.isNullOrBlank() && !it.nasUri.isNullOrBlank() }
            .map { it.episode.id }.toSet()
        selected.retainAll(available)

        binding.rvCacheChoices.layoutManager = GridLayoutManager(context, 3)
        binding.rvCacheChoices.adapter = object : RecyclerView.Adapter<ChoiceHolder>() {
            override fun getItemCount() = episodes.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChoiceHolder {
                val itemBinding = ItemCacheChoiceBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
                return ChoiceHolder(itemBinding)
            }

            override fun onBindViewHolder(holder: ChoiceHolder, position: Int) {
                val item = episodes[position]
                val epNum = holder.itemView.context.getString(ResR.string.episode_format, item.episode.episodeNumber)
                val epName = item.episode.title.ifBlank { "" }
                holder.binding.tvChoiceTitle.text = if (epName.isNotBlank()) "$epNum · $epName" else epNum

                val isLocal = !item.localUri.isNullOrBlank()
                val statusText = if (isLocal) {
                    holder.itemView.context.getString(ResR.string.cache_already_local)
                } else if (item.nasUri.isNullOrBlank()) {
                    holder.itemView.context.getString(ResR.string.badge_offline)
                } else {
                    holder.itemView.context.getString(ResR.string.badge_nas_online)
                }
                holder.binding.tvChoiceStatus.text = statusText

                val canSelect = item.episode.id in available
                holder.binding.root.isEnabled = canSelect
                holder.binding.root.isFocusable = canSelect
                holder.binding.tvChoiceTitle.isEnabled = canSelect
                holder.binding.tvChoiceStatus.isEnabled = canSelect
                holder.binding.cbCache.isEnabled = canSelect
                holder.binding.cbCache.isChecked = item.episode.id in selected
                holder.binding.root.isSelected = item.episode.id in selected

                holder.binding.root.setOnClickListener {
                    if (canSelect) {
                        if (!selected.add(item.episode.id)) selected.remove(item.episode.id)
                        notifyItemChanged(position)
                        renderSelection()
                    }
                }
            }
        }

        binding.btnSelectAll.setOnClickListener {
            if (selected.containsAll(available)) selected.clear() else selected.addAll(available)
            binding.rvCacheChoices.adapter?.notifyDataSetChanged()
            renderSelection()
        }

        binding.btnCacheConfirm.setOnClickListener {
            if (selected.isNotEmpty()) {
                onConfirm(selected.toList())
                dismiss()
            }
        }
        binding.btnCacheCancel.setOnClickListener { dismiss() }

        listOf(binding.btnSelectAll, binding.btnCacheConfirm, binding.btnCacheCancel).forEach {
            it.backgroundTintList = null
            TvFocusHelper.applyFocusScale(it, 1.04f)
        }

        renderSelection()
        binding.root.post { if (TvFocusHelper.isTelevision(binding.root)) binding.btnSelectAll.requestFocus() }
    }

    private fun renderSelection() {
        binding.btnCacheConfirm.text = context.getString(ResR.string.cache_selected_count, selected.size)
        binding.btnCacheConfirm.isEnabled = selected.isNotEmpty()
    }

    private class ChoiceHolder(val binding: ItemCacheChoiceBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            TvFocusHelper.applyFocusScale(binding.root, 1.05f)
        }
    }
}
