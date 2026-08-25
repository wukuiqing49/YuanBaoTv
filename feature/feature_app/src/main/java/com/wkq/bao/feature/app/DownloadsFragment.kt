package com.wkq.bao.feature.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wkq.bao.core.database.entity.ScanSessionStatus
import com.wkq.bao.core.media.storage.MediaStorageLocation
import com.wkq.bao.core.media.storage.TvStorageManager
import com.wkq.bao.feature.app.adapter.DownloadTaskAdapter
import com.wkq.bao.feature.app.adapter.PosterCardAdapter
import com.wkq.bao.feature.app.databinding.ActivityDownloadsBinding
import com.wkq.bao.feature.app.utils.TvFocusHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DownloadsFragment : Fragment() {
    private var _binding: ActivityDownloadsBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val viewModel by viewModels<DownloadsViewModel> { DownloadsViewModel.Factory(requireContext()) }
    private val storageManager by lazy { TvStorageManager(requireContext()) }
    private lateinit var downloadedAdapter: PosterCardAdapter
    private lateinit var downloadAdapter: DownloadTaskAdapter
    private var keepScreenRequested = false

    private val openStorageLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            requireContext().contentResolver.takePersistableUriPermission(uri, flags)
            if (!storageManager.isStorageTargetAvailable(uri)) {
                requireContext().contentResolver.releasePersistableUriPermission(uri, flags)
                throw IllegalArgumentException(getString(com.wkq.bao.feature.res.R.string.storage_location_invalid))
            }
            storageManager.saveStorageRoot(uri, storageManager.resolveLocalLocation(uri))
            renderStorage()
            uri
        }.onFailure { Toast.makeText(requireContext(), it.message ?: getString(com.wkq.bao.feature.res.R.string.storage_permission_failed), Toast.LENGTH_LONG).show() }
            .onSuccess { selectedUri ->
                Toast.makeText(requireContext(), com.wkq.bao.feature.res.R.string.storage_authorized, Toast.LENGTH_SHORT).show()
                viewModel.startLocalScan(selectedUri)
                Toast.makeText(requireContext(), com.wkq.bao.feature.res.R.string.local_scan_queued, Toast.LENGTH_SHORT).show()
            }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ActivityDownloadsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.cardStorageInfo.isFocusable = true
        binding.cardStorageInfo.isClickable = true
        binding.btnSelectStorage.backgroundTintList = null
        listOf(binding.cardStorageInfo, binding.cardDownloadTask, binding.btnSelectStorage).forEach(TvFocusHelper::applyFocusScale)
        binding.btnSelectStorage.setText(com.wkq.bao.feature.res.R.string.storage_select_target)
        binding.btnSelectStorage.setOnClickListener {
            if (viewModel.uiState.value.scanSession?.let { ScanSessionStatus.isActive(it.status) || it.status in setOf(ScanSessionStatus.FAILED, ScanSessionStatus.CANCELLED) } == true) {
                viewModel.handleLocalScanAction()
            } else {
                selectStorageLocation()
            }
        }
        binding.cardStorageInfo.setOnClickListener {
            if (!ScanSessionStatus.isActive(viewModel.uiState.value.scanSession?.status.orEmpty())) {
                selectStorageLocation()
            }
        }
        downloadAdapter = DownloadTaskAdapter(
            onTogglePause = { viewModel.togglePauseResume(it.id) },
            onCancel = { viewModel.cancel(it.id) }
        )
        binding.rvDownloadTasks.adapter = downloadAdapter
        downloadedAdapter = PosterCardAdapter { series -> startActivity(Intent(requireContext(), DetailActivity::class.java).putExtra("seriesId", series.id)) }
        binding.rvDownloaded.adapter = downloadedAdapter
        renderStorage()
        TvFocusHelper.requestInitialFocus(binding.root, binding.btnSelectStorage)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest(::renderState)
            }
        }
    }

    private fun renderState(state: DownloadsUiState) {
        downloadAdapter.submitList(state.tasks)
        downloadedAdapter.submitList(state.downloadedSeries)
        // 保留队列区域的高度，避免空态与下方已下载媒体区域发生约束重叠。
        val isTaskListEmpty = state.tasks.isEmpty()
        binding.rvDownloadTasks.visibility = if (isTaskListEmpty) View.GONE else View.VISIBLE
        binding.cardDownloadTask.visibility = if (isTaskListEmpty) View.VISIBLE else View.GONE
        updateDownloadedSectionAnchor(isTaskListEmpty)
        renderEmptyTask(isTaskListEmpty)
        keepScreenRequested = state.keepScreenOn
        renderStorage(state)
        updateKeepScreenOn(isResumed && keepScreenRequested)
    }

    private fun renderStorage(state: DownloadsUiState = viewModel.uiState.value) {
        val target = storageManager.getStorageTarget()
        val stat = storageManager.getStorageInfo(target)
        binding.tvStorageLabel.setText(
            when {
                target == null -> com.wkq.bao.feature.res.R.string.storage_target_required
                !target.isAvailable -> com.wkq.bao.feature.res.R.string.storage_target_unavailable
                target.location == MediaStorageLocation.INTERNAL_STORAGE -> com.wkq.bao.feature.res.R.string.storage_internal
                target.location == MediaStorageLocation.TF_CARD -> com.wkq.bao.feature.res.R.string.storage_tf_card
                target.location == MediaStorageLocation.USB_DRIVE -> com.wkq.bao.feature.res.R.string.storage_usb_drive
                else -> com.wkq.bao.feature.res.R.string.storage_target_required
            }
        )
        val capacity = getString(
            com.wkq.bao.feature.res.R.string.storage_space_format,
            stat.formattedFree,
            stat.formattedTotal
        )
        binding.tvStorageCapacity.text = when (state.scanSession?.status) {
            ScanSessionStatus.QUEUED, ScanSessionStatus.RUNNING, ScanSessionStatus.RETRYING ->
                "$capacity · ${getString(com.wkq.bao.feature.res.R.string.scan_progress, state.scanSession.importedCount)}"
            ScanSessionStatus.SUCCEEDED ->
                "$capacity · ${getString(com.wkq.bao.feature.res.R.string.scan_complete, state.scanSession.importedCount)}"
            ScanSessionStatus.FAILED -> "$capacity · ${getString(com.wkq.bao.feature.res.R.string.local_scan_failed)}"
            ScanSessionStatus.CANCELLED -> "$capacity · ${getString(com.wkq.bao.feature.res.R.string.scan_cancelled)}"
            else -> capacity
        }
        binding.btnSelectStorage.text = when {
            ScanSessionStatus.isActive(state.scanSession?.status.orEmpty()) ->
                getString(com.wkq.bao.feature.res.R.string.scan_action_cancel, state.scanSession?.importedCount ?: 0)
            state.scanSession?.status in setOf(ScanSessionStatus.FAILED, ScanSessionStatus.CANCELLED) ->
                getString(com.wkq.bao.feature.res.R.string.scan_action_retry)
            else -> getString(com.wkq.bao.feature.res.R.string.storage_select_target)
        }
        binding.pbStorage.progress = if (stat.totalBytes > 0L) (((stat.totalBytes - stat.freeBytes) * 100L) / stat.totalBytes).toInt() else 0
        viewModel.observeStorage(target?.uri)
    }

    private fun selectStorageLocation() {
        openStorageLauncher.launch(null)
    }

    private fun renderEmptyTask(isEmpty: Boolean) {
        if (isEmpty) {
            binding.tvTaskTitle.setText(com.wkq.bao.feature.res.R.string.downloads_empty_title)
            binding.tvTaskSpeed.setText(com.wkq.bao.feature.res.R.string.downloads_empty_message)
            binding.tvTaskSpeed.setTextColor(requireContext().getColor(com.wkq.bao.feature.res.R.color.tv_text_secondary))
            binding.pbTask.progress = 0
            binding.pbTask.visibility = View.GONE
            binding.btnTaskPause.isEnabled = false
            binding.btnTaskCancel.isEnabled = false
        }
    }

    /** 下载任务与空态互斥显示，已下载区始终锚定到当前可见区域。 */
    private fun updateDownloadedSectionAnchor(isTaskListEmpty: Boolean) {
        val params = binding.tvLabelDownloaded.layoutParams as ConstraintLayout.LayoutParams
        val anchorId = if (isTaskListEmpty) binding.cardDownloadTask.id else binding.rvDownloadTasks.id
        if (params.topToBottom != anchorId) {
            params.topToBottom = anchorId
            binding.tvLabelDownloaded.layoutParams = params
        }
    }

    override fun onResume() {
        super.onResume()
        updateKeepScreenOn(keepScreenRequested)
    }

    override fun onPause() {
        updateKeepScreenOn(false)
        super.onPause()
    }

    override fun onDestroyView() {
        updateKeepScreenOn(false)
        _binding = null
        super.onDestroyView()
    }

    /** 仅在下载管理页可见且存在活动下载时保持屏幕常亮。 */
    private fun updateKeepScreenOn(keepScreenOn: Boolean) {
        activity?.window?.let { window ->
            if (keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
