package com.wkq.bao.feature.app

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lxj.xpopup.core.BottomPopupView
import com.wkq.bao.core.database.entity.EpisodeEntity
import com.wkq.bao.feature.app.databinding.ItemPlayerEpisodeGridBinding
import com.wkq.bao.feature.app.databinding.PopupPlayerEpisodePickerBinding
import com.wkq.bao.feature.app.utils.TvFocusHelper
import com.wkq.bao.feature.res.R as ResR

class PlayerEpisodePickerPopup(
    context: Context,
    private val episodes: List<EpisodeEntity>,
    private val currentEpisodeId: Long,
    private val onEpisodeSelected: (Long) -> Unit
) : BottomPopupView(context) {

    private lateinit var binding: PopupPlayerEpisodePickerBinding

    override val implLayoutId: Int
        get() = R.layout.popup_player_episode_picker

    override fun onCreate() {
        super.onCreate()
        binding = PopupPlayerEpisodePickerBinding.bind(popupImplView)
        (popupImplView as? ViewGroup)?.clipChildren = false
        (popupImplView as? ViewGroup)?.clipToPadding = false
        (popupImplView.parent as? ViewGroup)?.clipChildren = false
        (popupImplView.parent as? ViewGroup)?.clipToPadding = false

        val titleText = context.getString(ResR.string.player_select_episode) + " · " +
            context.getString(ResR.string.label_episodes_count, episodes.size)
        binding.tvPickerTitle.text = titleText

        val layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        binding.rvEpisodesGrid.layoutManager = layoutManager

        val currentIndex = episodes.indexOfFirst { it.id == currentEpisodeId }.coerceAtLeast(0)

        val adapter = EpisodeGridAdapter(episodes, currentEpisodeId) { selectedEp ->
            dismiss()
            if (selectedEp.id != currentEpisodeId) {
                onEpisodeSelected(selectedEp.id)
            }
        }
        binding.rvEpisodesGrid.adapter = adapter

        if (currentIndex in episodes.indices) {
            layoutManager.scrollToPosition(currentIndex)
            binding.rvEpisodesGrid.post {
                val holder = binding.rvEpisodesGrid.findViewHolderForAdapterPosition(currentIndex)
                holder?.itemView?.requestFocus()
            }
        }
    }

    private class EpisodeGridAdapter(
        private val list: List<EpisodeEntity>,
        private val currentId: Long,
        private val onClick: (EpisodeEntity) -> Unit
    ) : RecyclerView.Adapter<EpisodeGridAdapter.Holder>() {

        override fun getItemCount(): Int = list.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemPlayerEpisodeGridBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(list[position], currentId, onClick)
        }

        class Holder(private val binding: ItemPlayerEpisodeGridBinding) :
            RecyclerView.ViewHolder(binding.root) {

            init {
                TvFocusHelper.applyFocusScale(binding.root, 1.05f)
            }

            fun bind(ep: EpisodeEntity, currentId: Long, onClick: (EpisodeEntity) -> Unit) {
                binding.tvEpNumber.text = "%02d".format(ep.episodeNumber)
                binding.tvEpTitle.text = ep.title.ifBlank {
                    binding.root.context.getString(ResR.string.episode_format, ep.episodeNumber)
                }

                val isCurrent = ep.id == currentId
                binding.tvEpBadge.isVisible = isCurrent
                binding.root.isSelected = isCurrent

                binding.root.setOnClickListener { onClick(ep) }
            }
        }
    }
}
