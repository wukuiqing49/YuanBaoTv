package com.wkq.bao.feature.app.adapter

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wkq.bao.core.database.entity.DownloadTaskEntity
import com.wkq.bao.core.database.entity.DownloadTaskErrorCode
import com.wkq.bao.core.database.entity.DownloadTaskStatus
import com.wkq.bao.core.media.storage.MediaStorageLocation
import com.wkq.bao.feature.app.databinding.ItemDownloadTaskBinding
import com.wkq.bao.feature.app.utils.TvFocusHelper

/** 下载队列仅负责展示和分发用户动作，状态更新由页面统一写入数据库。 */
class DownloadTaskAdapter(
    private val onTogglePause: (DownloadTaskEntity) -> Unit,
    private val onCancel: (DownloadTaskEntity) -> Unit
) : ListAdapter<DownloadTaskEntity, DownloadTaskAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        ItemDownloadTaskBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(private val binding: ItemDownloadTaskBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            listOf(binding.btnTaskPause, binding.btnTaskCancel).forEach(TvFocusHelper::applyFocusScale)
            binding.btnTaskPause.setOnClickListener {
                bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { onTogglePause(getItem(it)) }
            }
            binding.btnTaskCancel.setOnClickListener {
                bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { onCancel(getItem(it)) }
            }
        }

        fun bind(task: DownloadTaskEntity) = with(binding) {
            val context = root.context
            tvTaskTitle.text = Uri.parse(task.sourceUri).lastPathSegment
                ?.takeIf(String::isNotBlank)
                ?: context.getString(com.wkq.bao.feature.res.R.string.download_task_number, task.id)
            tvTaskStatus.setText(statusLabel(task.status))
            val target = when (MediaStorageLocation.fromStored(task.targetStorageType)) {
                MediaStorageLocation.INTERNAL_STORAGE -> com.wkq.bao.feature.res.R.string.storage_internal
                MediaStorageLocation.TF_CARD -> com.wkq.bao.feature.res.R.string.storage_tf_card
                MediaStorageLocation.USB_DRIVE -> com.wkq.bao.feature.res.R.string.storage_usb_drive
                else -> com.wkq.bao.feature.res.R.string.storage_external
            }
            tvTaskDetail.text = if (task.status == DownloadTaskStatus.FAILED) {
                context.getString(
                    com.wkq.bao.feature.res.R.string.download_failure_with_target,
                    context.getString(target),
                    context.getString(failureReason(task.errorCode))
                )
            } else {
                context.getString(
                    com.wkq.bao.feature.res.R.string.download_progress_with_target,
                    context.getString(target),
                    context.getString(
                        com.wkq.bao.feature.res.R.string.download_byte_progress_format,
                        task.downloadedBytes / 1024 / 1024,
                        task.totalBytes / 1024 / 1024
                    )
                )
            }
            pbTask.progress = if (task.totalBytes > 0L) ((task.downloadedBytes * 100L) / task.totalBytes).toInt() else 0
            val isCompleted = task.status == DownloadTaskStatus.SUCCESS
            btnTaskPause.visibility = if (isCompleted) View.GONE else View.VISIBLE
            btnTaskCancel.visibility = if (isCompleted) View.GONE else View.VISIBLE
            btnTaskPause.setText(
                if (task.status == DownloadTaskStatus.FAILED) {
                    com.wkq.bao.feature.res.R.string.btn_retry
                } else if (task.status == DownloadTaskStatus.PAUSED) {
                    com.wkq.bao.feature.res.R.string.btn_resume
                } else {
                    com.wkq.bao.feature.res.R.string.btn_pause
                }
            )
        }
    }

    private fun statusLabel(status: String): Int = when (status) {
        DownloadTaskStatus.DOWNLOADING -> com.wkq.bao.feature.res.R.string.download_status_downloading
        DownloadTaskStatus.WAITING -> com.wkq.bao.feature.res.R.string.download_status_waiting
        DownloadTaskStatus.PAUSED -> com.wkq.bao.feature.res.R.string.download_status_paused
        DownloadTaskStatus.SUCCESS -> com.wkq.bao.feature.res.R.string.download_status_success
        else -> com.wkq.bao.feature.res.R.string.download_status_failed
    }

    private fun failureReason(errorCode: String): Int = when (errorCode) {
        DownloadTaskErrorCode.NETWORK -> com.wkq.bao.feature.res.R.string.download_error_network
        DownloadTaskErrorCode.STORAGE_UNAVAILABLE -> com.wkq.bao.feature.res.R.string.download_error_storage_unavailable
        DownloadTaskErrorCode.STORAGE_CAPACITY -> com.wkq.bao.feature.res.R.string.download_error_storage_capacity
        DownloadTaskErrorCode.STORAGE_ACCESS -> com.wkq.bao.feature.res.R.string.download_error_storage_access
        DownloadTaskErrorCode.SOURCE_UNAVAILABLE -> com.wkq.bao.feature.res.R.string.download_error_source_unavailable
        DownloadTaskErrorCode.SOURCE_CHANGED -> com.wkq.bao.feature.res.R.string.download_error_source_changed
        DownloadTaskErrorCode.UNSUPPORTED_SOURCE -> com.wkq.bao.feature.res.R.string.download_error_unsupported_source
        DownloadTaskErrorCode.TARGET_EXISTS -> com.wkq.bao.feature.res.R.string.download_error_target_exists
        DownloadTaskErrorCode.DATA_MISSING -> com.wkq.bao.feature.res.R.string.download_error_data_missing
        else -> com.wkq.bao.feature.res.R.string.download_error_unknown
    }

    private companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<DownloadTaskEntity>() {
            override fun areItemsTheSame(oldItem: DownloadTaskEntity, newItem: DownloadTaskEntity): Boolean = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: DownloadTaskEntity, newItem: DownloadTaskEntity): Boolean = oldItem == newItem
        }
    }
}
