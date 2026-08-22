package com.wkq.bao.feature.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.database.entity.DownloadTaskEntity
import com.wkq.bao.core.database.entity.DownloadTaskStatus
import com.wkq.bao.core.media.download.DownloadForegroundService
import com.wkq.bao.core.media.storage.MediaStorageLocation
import com.wkq.bao.core.media.storage.TvStorageManager
import com.wkq.bao.feature.app.adapter.PosterCardAdapter
import com.wkq.bao.feature.app.databinding.ActivityDownloadsBinding
import com.wkq.bao.feature.app.utils.TvFocusHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DownloadsFragment : Fragment() {
    private var _binding: ActivityDownloadsBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val storageManager by lazy { TvStorageManager(requireContext()) }
    private lateinit var downloadedAdapter: PosterCardAdapter
    private var displayedTask: DownloadTaskEntity? = null
    private var pendingStorageLocation: MediaStorageLocation? = null

    private val openStorageLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            pendingStorageLocation = null
            return@registerForActivityResult
        }
        runCatching {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            requireContext().contentResolver.takePersistableUriPermission(uri, flags)
            if (!storageManager.isStorageTargetAvailable(uri)) {
                requireContext().contentResolver.releasePersistableUriPermission(uri, flags)
                throw IllegalArgumentException(getString(com.wkq.bao.feature.res.R.string.storage_removable_required))
            }
            storageManager.saveStorageRoot(uri, pendingStorageLocation ?: storageManager.resolveLocalLocation(uri))
            pendingStorageLocation = null
            renderStorage()
        }.onFailure { Toast.makeText(requireContext(), it.message ?: getString(com.wkq.bao.feature.res.R.string.storage_permission_failed), Toast.LENGTH_LONG).show() }
            .onSuccess { Toast.makeText(requireContext(), com.wkq.bao.feature.res.R.string.storage_authorized, Toast.LENGTH_SHORT).show() }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ActivityDownloadsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listOf(binding.cardDownloadTask, binding.btnSelectStorage, binding.btnTaskPause, binding.btnTaskCancel).forEach(TvFocusHelper::applyFocusScale)
        binding.btnSelectStorage.setText(com.wkq.bao.feature.res.R.string.storage_select_target)
        binding.btnSelectStorage.setOnClickListener { selectStorageLocation() }
        binding.btnTaskPause.setOnClickListener { togglePauseResume() }
        binding.btnTaskCancel.setOnClickListener { cancelTask() }
        downloadedAdapter = PosterCardAdapter { series -> startActivity(Intent(requireContext(), DetailActivity::class.java).putExtra("seriesId", series.id)) }
        binding.rvDownloaded.adapter = downloadedAdapter
        renderStorage()
        viewLifecycleOwner.lifecycleScope.launch {
            database.downloadDao().getAllTasks().collectLatest { tasks ->
                renderTask(tasks.firstOrNull { it.status in setOf(DownloadTaskStatus.DOWNLOADING, DownloadTaskStatus.WAITING, DownloadTaskStatus.PAUSED, DownloadTaskStatus.FAILED) })
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            database.mediaDao().getDownloadedSeries().collectLatest(downloadedAdapter::submitList)
        }
    }

    private fun renderStorage() {
        val target = storageManager.getStorageTarget()
        val stat = storageManager.getStorageInfo(target)
        binding.tvStorageLabel.setText(
            when {
                target == null -> com.wkq.bao.feature.res.R.string.storage_target_required
                !target.isAvailable -> com.wkq.bao.feature.res.R.string.storage_target_unavailable
                target.location == MediaStorageLocation.TF_CARD -> com.wkq.bao.feature.res.R.string.storage_tf_card
                target.location == MediaStorageLocation.USB_DRIVE -> com.wkq.bao.feature.res.R.string.storage_usb_drive
                else -> com.wkq.bao.feature.res.R.string.storage_target_required
            }
        )
        binding.tvStorageCapacity.text = getString(
            com.wkq.bao.feature.res.R.string.storage_space_format,
            stat.formattedFree,
            stat.formattedTotal
        )
        binding.pbStorage.progress = if (stat.totalBytes > 0L) (((stat.totalBytes - stat.freeBytes) * 100L) / stat.totalBytes).toInt() else 0
    }

    private fun selectStorageLocation() {
        val locations = arrayOf(
            getString(com.wkq.bao.feature.res.R.string.storage_tf_card),
            getString(com.wkq.bao.feature.res.R.string.storage_usb_drive)
        )
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(com.wkq.bao.feature.res.R.string.storage_choose_type)
            .setItems(locations) { _, selected ->
                pendingStorageLocation = if (selected == 0) MediaStorageLocation.TF_CARD else MediaStorageLocation.USB_DRIVE
                openStorageLauncher.launch(null)
            }
            .show()
    }

    private fun renderTask(task: DownloadTaskEntity?) {
        displayedTask = task
        if (task == null) {
            binding.tvTaskTitle.setText(com.wkq.bao.feature.res.R.string.section_downloaded_gallery)
            binding.tvTaskSpeed.text = ""
            binding.pbTask.progress = 0
            binding.btnTaskPause.isEnabled = false
            binding.btnTaskCancel.isEnabled = false
            return
        }
        binding.btnTaskPause.isEnabled = true
        binding.btnTaskCancel.isEnabled = true
        binding.tvTaskTitle.text = getString(com.wkq.bao.feature.res.R.string.download_task_format, task.episodeId)
        val targetLabel = when (MediaStorageLocation.fromStored(task.targetStorageType)) {
            MediaStorageLocation.TF_CARD -> getString(com.wkq.bao.feature.res.R.string.storage_tf_card)
            MediaStorageLocation.USB_DRIVE -> getString(com.wkq.bao.feature.res.R.string.storage_usb_drive)
            else -> getString(com.wkq.bao.feature.res.R.string.badge_nas_stream)
        }
        val progress = getString(
            com.wkq.bao.feature.res.R.string.download_progress_format,
            task.status,
            task.downloadedBytes / 1024 / 1024,
            task.totalBytes / 1024 / 1024
        )
        binding.tvTaskSpeed.text = getString(
            com.wkq.bao.feature.res.R.string.download_progress_with_target,
            targetLabel,
            progress
        )
        binding.pbTask.progress = if (task.totalBytes > 0L) ((task.downloadedBytes * 100L) / task.totalBytes).toInt() else 0
        binding.btnTaskPause.setText(if (task.status == DownloadTaskStatus.PAUSED || task.status == DownloadTaskStatus.FAILED) com.wkq.bao.feature.res.R.string.btn_resume else com.wkq.bao.feature.res.R.string.btn_pause)
    }

    private fun togglePauseResume() {
        val task = displayedTask ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val resume = task.status == DownloadTaskStatus.PAUSED || task.status == DownloadTaskStatus.FAILED
            database.downloadDao().updateTask(task.copy(status = if (resume) DownloadTaskStatus.WAITING else DownloadTaskStatus.PAUSED))
            if (resume) DownloadForegroundService.enqueueDownload(requireContext())
        }
    }

    private fun cancelTask() {
        displayedTask?.let { task -> viewLifecycleOwner.lifecycleScope.launch { database.downloadDao().updateTask(task.copy(status = DownloadTaskStatus.CANCELLED)) } }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
