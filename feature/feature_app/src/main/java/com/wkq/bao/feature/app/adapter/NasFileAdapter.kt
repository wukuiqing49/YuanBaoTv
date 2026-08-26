package com.wkq.bao.feature.app.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wkq.bao.core.nas.browser.NasFileEntry
import com.wkq.bao.feature.app.databinding.ItemNasFileBinding
import com.wkq.bao.feature.app.utils.TvFocusHelper

class NasFileAdapter(
    private val onToggleSelection: (NasFileEntry) -> Unit,
    private val onOpenDirectory: (NasFileEntry) -> Unit
) : ListAdapter<NasFileEntry, NasFileAdapter.ViewHolder>(DIFF_CALLBACK) {
    private var selectedPaths: Set<String> = emptySet()

    fun render(entries: List<NasFileEntry>, selection: Set<String>) {
        selectedPaths = selection
        if (currentList == entries) {
            notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
        } else {
            submitList(entries) { notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        ItemNasFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_SELECTION)) holder.bindSelection(getItem(position))
        else super.onBindViewHolder(holder, position, payloads)
    }

    inner class ViewHolder(private val binding: ItemNasFileBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            TvFocusHelper.applyFocusScale(binding.root, 1.02f)
            TvFocusHelper.applyFocusScale(binding.btnOpenFolder)
            binding.root.setOnClickListener {
                bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                    ?.let { position ->
                        getItem(position).let { entry ->
                            if (entry.isDirectory) onOpenDirectory(entry) else onToggleSelection(entry)
                        }
                    }
            }
            binding.cbSelected.setOnClickListener {
                bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                    ?.let { position -> onToggleSelection(getItem(position)) }
            }
            binding.btnOpenFolder.setOnClickListener {
                bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                    ?.let { position -> onOpenDirectory(getItem(position)) }
            }
        }

        fun bind(entry: NasFileEntry) = with(binding) {
            tvFileName.text = entry.name
            ivFileType.setImageResource(
                if (entry.isDirectory) com.wkq.bao.feature.res.R.drawable.ic_tv_nas
                else com.wkq.bao.feature.res.R.drawable.ic_tv_download
            )
            tvFileInfo.text = if (entry.isDirectory) {
                root.context.getString(com.wkq.bao.feature.res.R.string.nas_file_type_folder)
            } else {
                root.context.getString(com.wkq.bao.feature.res.R.string.nas_file_size, formatSize(entry.size))
            }
            btnOpenFolder.visibility = if (entry.isDirectory) View.VISIBLE else View.GONE
            bindSelection(entry)
        }

        fun bindSelection(entry: NasFileEntry) {
            binding.cbSelected.isChecked = entry.path in selectedPaths
            binding.root.isSelected = binding.cbSelected.isChecked
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private companion object {
        const val PAYLOAD_SELECTION = "selection"
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<NasFileEntry>() {
            override fun areItemsTheSame(oldItem: NasFileEntry, newItem: NasFileEntry) = oldItem.path == newItem.path
            override fun areContentsTheSame(oldItem: NasFileEntry, newItem: NasFileEntry) = oldItem == newItem
        }
    }
}
