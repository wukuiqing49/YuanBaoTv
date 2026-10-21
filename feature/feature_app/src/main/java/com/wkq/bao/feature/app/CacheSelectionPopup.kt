package com.wkq.bao.feature.app

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.CheckedTextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.lxj.xpopup.core.CenterPopupView
import com.wkq.bao.core.database.entity.EpisodeWithSource
import com.wkq.bao.feature.app.databinding.PopupCacheSelectionBinding
import com.wkq.bao.feature.app.utils.TvFocusHelper
import com.wkq.bao.feature.res.R as ResR

internal class CacheSelectionPopup(context: Context, private val episodes: List<EpisodeWithSource>,
    initialSelection: Set<Long>, private val onConfirm: (List<Long>) -> Unit
) : CenterPopupView(context) {
    private val selected = initialSelection.toMutableSet()
    private lateinit var binding: PopupCacheSelectionBinding
    override val implLayoutId get() = R.layout.popup_cache_selection
    fun selection(): LongArray = selected.toLongArray()
    override fun onCreate() {
        super.onCreate()
        binding = PopupCacheSelectionBinding.bind(checkNotNull(contentView))
        val available = episodes.filter { it.localUri.isNullOrBlank() && !it.nasUri.isNullOrBlank() }.map { it.episode.id }.toSet()
        selected.retainAll(available)
        binding.rvCacheChoices.layoutManager = LinearLayoutManager(context)
        binding.rvCacheChoices.layoutParams.height = (resources.displayMetrics.heightPixels * .30f).toInt()
        binding.rvCacheChoices.adapter = object : RecyclerView.Adapter<ChoiceHolder>() {
            override fun getItemCount() = episodes.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ChoiceHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_cache_choice, parent, false) as CheckedTextView)
            override fun onBindViewHolder(holder: ChoiceHolder, position: Int) {
                val item = episodes[position]
                holder.text.text = context.getString(ResR.string.cache_episode_choice, item.episode.episodeNumber,
                    item.episode.title.ifBlank { context.getString(ResR.string.episode_default_title) },
                    context.getString(if (!item.localUri.isNullOrBlank()) ResR.string.cache_already_local else if (item.nasUri.isNullOrBlank()) ResR.string.badge_offline else ResR.string.badge_nas_online))
                holder.text.isEnabled = item.episode.id in available
                holder.text.isChecked = item.episode.id in selected
                holder.text.setOnClickListener {
                    if (!selected.add(item.episode.id)) selected.remove(item.episode.id)
                    holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(::notifyItemChanged)
                    renderSelection()
                }
            }
        }
        binding.btnSelectAll.setOnClickListener {
            if (selected.containsAll(available)) selected.clear() else selected.addAll(available)
            binding.rvCacheChoices.adapter?.notifyDataSetChanged()
            renderSelection()
        }
        binding.btnCacheConfirm.setOnClickListener { if (selected.isNotEmpty()) { onConfirm(selected.toList()); dismiss() } }
        binding.btnCacheCancel.setOnClickListener { dismiss() }
        listOf(binding.btnSelectAll, binding.btnCacheConfirm, binding.btnCacheCancel).forEach {
            it.backgroundTintList = null
            TvFocusHelper.applyFocusScale(it, 1.03f)
        }
        renderSelection()
        binding.root.post { if (TvFocusHelper.isTelevision(binding.root)) binding.btnSelectAll.requestFocus() }
    }
    private fun renderSelection() {
        binding.btnCacheConfirm.text = context.getString(ResR.string.cache_selected_count, selected.size)
        binding.btnCacheConfirm.isEnabled = selected.isNotEmpty()
    }
    private class ChoiceHolder(val text: CheckedTextView) : RecyclerView.ViewHolder(text)
}
