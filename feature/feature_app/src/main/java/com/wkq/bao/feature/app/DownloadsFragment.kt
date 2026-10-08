package com.wkq.bao.feature.app

import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.Context
import android.provider.DocumentsContract
import com.lxj.xpopup.XPopup
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.content.ContextCompat
import com.wkq.bao.core.database.entity.ScanSessionStatus
import com.wkq.bao.core.media.storage.MediaStorageLocation
import com.wkq.bao.core.media.storage.TvStorageManager
import com.wkq.bao.core.media.storage.UsbStorageReceiver
import com.wkq.bao.feature.app.adapter.DownloadTaskAdapter
import com.wkq.bao.feature.app.databinding.ActivityDownloadsBinding
import com.wkq.bao.feature.app.utils.TvFocusHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

class DownloadsFragment : Fragment() {
    private var _binding: ActivityDownloadsBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val viewModel by viewModels<DownloadsViewModel> { DownloadsViewModel.Factory(requireContext()) }
    private val storageManager by lazy { TvStorageManager(requireContext()) }
    private lateinit var downloadAdapter: DownloadTaskAdapter
    private var keepScreenRequested = false
    private var storageRenderJob: Job? = null
    private var targetPickerJob: Job? = null
    private val storageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (_binding != null) renderStorage()
        }
    }

    private val openStorageLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val selectedUri = uri ?: return@registerForActivityResult
        runCatching {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            requireContext().contentResolver.takePersistableUriPermission(selectedUri, flags)
            if (!storageManager.isStorageTargetAvailable(selectedUri)) {
                throw IllegalArgumentException(getString(com.wkq.bao.feature.res.R.string.storage_location_invalid))
            }
            storageManager.saveStorageRoot(selectedUri, storageManager.resolveLocalLocation(selectedUri))
            renderStorage()
        }.onFailure { Toast.makeText(requireContext(), it.message ?: getString(com.wkq.bao.feature.res.R.string.storage_permission_failed), Toast.LENGTH_LONG).show() }
            .onSuccess {
            Toast.makeText(requireContext(), com.wkq.bao.feature.res.R.string.storage_authorized, Toast.LENGTH_SHORT).show()
            viewModel.startLocalScan(selectedUri)
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
        binding.btnImportNas?.apply {
            backgroundTintList = null
            setOnClickListener {
                (requireActivity() as MainPageNavigator).showPage(MainPageNavigator.NAS)
            }
        }
        listOf(binding.cardStorageInfo, binding.btnSelectStorage)
            .forEach(TvFocusHelper::applyFocusScale)
        binding.btnImportNas?.let(TvFocusHelper::applyFocusScale)
        binding.btnSelectStorage.setText(com.wkq.bao.feature.res.R.string.storage_select_target)
        binding.btnSelectStorage.setOnClickListener { selectStorageLocation() }
        binding.cardStorageInfo.setOnClickListener {
            val target = storageManager.getAvailableStorageTarget()
            if (target == null) {
                selectStorageLocation()
            } else {
                viewModel.handleLocalScanAction()
            }
        }
        downloadAdapter = DownloadTaskAdapter(
            onTogglePause = { viewModel.togglePauseResume(it.id) },
            onCancel = { viewModel.cancel(it.id) }
        )
        binding.rvDownloadTasks.adapter = downloadAdapter
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
        val isTaskListEmpty = state.tasks.isEmpty()
        binding.rvDownloadTasks.visibility = if (isTaskListEmpty) View.GONE else View.VISIBLE
        binding.layoutDownloadTasksEmpty?.visibility = if (isTaskListEmpty) View.VISIBLE else View.GONE
        keepScreenRequested = state.keepScreenOn
        renderStorage(state)
        updateKeepScreenOn(isResumed && keepScreenRequested)
    }

    private fun renderStorage(state: DownloadsUiState = viewModel.uiState.value) {
        val currentBinding = _binding ?: return
        storageRenderJob?.cancel()
        storageRenderJob = viewLifecycleOwner.lifecycleScope.launch {
            val (target, stat) = withContext(Dispatchers.IO) {
                val target = storageManager.getStorageTarget()
                target to storageManager.getStorageInfo(target)
            }
            if (_binding !== currentBinding) return@launch
            binding.tvStorageLabel.setText(
                when {
                    target == null -> com.wkq.bao.feature.res.R.string.storage_target_required
                    target.status == TvStorageManager.TargetStatus.PERMISSION_REQUIRED -> com.wkq.bao.feature.res.R.string.storage_permission_required
                    target.status == TvStorageManager.TargetStatus.OFFLINE -> com.wkq.bao.feature.res.R.string.storage_target_offline
                    target.status == TvStorageManager.TargetStatus.READ_ONLY -> com.wkq.bao.feature.res.R.string.storage_read_only
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
            binding.btnSelectStorage.setText(com.wkq.bao.feature.res.R.string.storage_select_target)
            binding.pbStorage.progress = if (stat.totalBytes > 0L) (((stat.totalBytes - stat.freeBytes) * 100L) / stat.totalBytes).toInt() else 0
            viewModel.observeStorage(target?.uri)
        }
    }

    private fun selectStorageLocation() {
        if (targetPickerJob?.isActive == true) return
        targetPickerJob = viewLifecycleOwner.lifecycleScope.launch {
            val targets = withContext(Dispatchers.IO) { storageManager.getStorageTargets() }
            if (targets.isEmpty()) {
                openStorageLauncher.launch(null)
                return@launch
            }
            val currentUri = storageManager.getStorageRoot()
            val labels = targets.map { target ->
                val documentId = DocumentsContract.getTreeDocumentId(target.uri)
                val label = getString(com.wkq.bao.feature.res.R.string.storage_saved_target_format,
                    documentId, getString(when (target.status) {
                        TvStorageManager.TargetStatus.AVAILABLE -> com.wkq.bao.feature.res.R.string.storage_target_online
                        TvStorageManager.TargetStatus.READ_ONLY -> com.wkq.bao.feature.res.R.string.storage_read_only
                        TvStorageManager.TargetStatus.OFFLINE -> com.wkq.bao.feature.res.R.string.storage_target_offline
                        TvStorageManager.TargetStatus.PERMISSION_REQUIRED -> com.wkq.bao.feature.res.R.string.storage_permission_required
                        else -> com.wkq.bao.feature.res.R.string.storage_target_unavailable
                    }))
                if (target.uri.toString() == currentUri) getString(com.wkq.bao.feature.res.R.string.storage_target_current, label)
                else label
            } + getString(com.wkq.bao.feature.res.R.string.storage_add_target)
            XPopup.Builder(requireContext()).isDarkTheme(true)
                .asCenterList(getString(com.wkq.bao.feature.res.R.string.storage_select_target), labels.toTypedArray()) { index, _ ->
                    if (_binding != null) {
                        if (index == targets.size) openStorageLauncher.launch(null)
                        else {
                            manageStorageTarget(targets[index])
                        }
                    }
                }.show()
        }
    }

    override fun onResume() {
        super.onResume()
        renderStorage()
        updateKeepScreenOn(keepScreenRequested)
    }

    private fun manageStorageTarget(target: TvStorageManager.StorageTarget) {
        val actions = listOf(
            com.wkq.bao.feature.res.R.string.storage_use_target,
            com.wkq.bao.feature.res.R.string.storage_reauthorize,
            com.wkq.bao.feature.res.R.string.storage_remove_target
        ).map(::getString).toTypedArray()
        XPopup.Builder(requireContext()).isDarkTheme(true)
            .asCenterList(DocumentsContract.getTreeDocumentId(target.uri), actions) { index, _ ->
                if (_binding != null) when (index) {
                    0 -> { storageManager.saveStorageRoot(target.uri, target.location); renderStorage() }
                    1 -> openStorageLauncher.launch(target.uri)
                    2 -> XPopup.Builder(requireContext()).isDarkTheme(true).asConfirm(
                        getString(com.wkq.bao.feature.res.R.string.storage_remove_target),
                        getString(com.wkq.bao.feature.res.R.string.storage_remove_confirmation),
                        {
                            storageManager.removeStorageRoot(target.uri)
                            if (_binding != null) renderStorage()
                        }
                    ).show()
                }
            }.show()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(requireContext(), storageReceiver,
            IntentFilter(UsbStorageReceiver.ACTION_USB_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        requireContext().unregisterReceiver(storageReceiver)
        super.onStop()
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
