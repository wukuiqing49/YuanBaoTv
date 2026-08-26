package com.wkq.bao.feature.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.lxj.xpopup.XPopup
import com.wkq.bao.core.base.diagnostics.AppDiagnostics
import com.wkq.bao.core.database.entity.NasSourceEntity
import com.wkq.bao.core.media.download.NasSourceRemovalResult
import com.wkq.bao.core.database.entity.ScanSessionStatus
import com.wkq.bao.core.nas.diagnostics.NasFailureClassifier
import com.wkq.bao.core.nas.security.NasCredentialVault
import com.wkq.bao.feature.app.databinding.ActivityNasSettingsBinding
import com.wkq.bao.feature.app.adapter.NasFileAdapter
import com.wkq.bao.feature.app.utils.TvFocusHelper
import com.wkq.bao.core.media.storage.TvStorageManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class NasSettingsFragment : Fragment() {
    private var _binding: ActivityNasSettingsBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val viewModel by viewModels<NasSettingsViewModel> { NasSettingsViewModel.Factory(requireContext()) }
    private var activeSource: NasSourceEntity? = null
    private var sources: List<NasSourceEntity> = emptyList()
    private var activeEditorPopup: NasEditorPopup? = null
    private lateinit var fileAdapter: NasFileAdapter
    private val storageManager by lazy { TvStorageManager(requireContext()) }
    private var waitingForDownloadTarget = false

    private val openDownloadTargetLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        waitingForDownloadTarget = false
        if (uri == null) return@registerForActivityResult
        runCatching {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            requireContext().contentResolver.takePersistableUriPermission(uri, flags)
            if (!storageManager.isStorageTargetAvailable(uri)) error(
                getString(com.wkq.bao.feature.res.R.string.storage_location_invalid)
            )
            require(storageManager.isExternalStorageTarget(uri)) {
                getString(com.wkq.bao.feature.res.R.string.storage_external_target_required)
            }
            storageManager.saveStorageRoot(uri, storageManager.resolveLocalLocation(uri))
            checkNotNull(storageManager.getAvailableExternalStorageTarget())
        }.onSuccess { target ->
            activeSource?.let { viewModel.enqueueSelected(it, target) }
        }.onFailure { error ->
            Toast.makeText(
                requireContext(),
                error.message ?: getString(com.wkq.bao.feature.res.R.string.storage_permission_failed),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ActivityNasSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnScan.backgroundTintList = null
        binding.btnTestConn.backgroundTintList = null
        listOf(
            binding.cardActiveNas, binding.cardAddNas, binding.btnScan, binding.btnTestConn,
            binding.btnRefreshFiles, binding.btnFileUp, binding.btnSelectAll, binding.btnDownloadSelected
        ).forEach(TvFocusHelper::applyFocusScale)
        fileAdapter = NasFileAdapter(viewModel::toggleSelection) { entry ->
            activeSource?.let { viewModel.openDirectory(it, entry) }
        }
        binding.rvNasFiles.adapter = fileAdapter
        binding.cardActiveNas.setOnClickListener { showActiveSourceActions() }
        binding.cardAddNas.setOnClickListener { showNasEditor(null) }
        binding.btnScan.setOnClickListener { scanActiveSource() }
        binding.btnTestConn.setOnClickListener { testActiveSource() }
        binding.btnRefreshFiles.setOnClickListener { activeSource?.let(viewModel::refreshFiles) ?: showMissingSource() }
        binding.btnFileUp.setOnClickListener { activeSource?.let(viewModel::goUp) }
        binding.btnSelectAll.setOnClickListener { viewModel.toggleSelectAll() }
        binding.btnDownloadSelected.setOnClickListener { selectDownloadTarget() }
        TvFocusHelper.requestInitialFocus(binding.root, binding.cardActiveNas)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collectLatest { state ->
                        sources = state.sources
                        activeSource = state.activeSource
                        renderActiveSource(state)
                        state.activeSource?.let(viewModel::openRoot)
                    }
                }
                launch { viewModel.browserState.collectLatest(::renderBrowserState) }
                launch {
                    viewModel.events.collectLatest(::handleEvent)
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val source = activeSource
                val browser = viewModel.browserState.value
                if (source != null && browser.currentPath != browser.rootPath) {
                    viewModel.goUp(source)
                } else {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                    binding.root.post { if (_binding != null) isEnabled = true }
                }
            }
        })
    }

    private fun renderBrowserState(state: NasBrowserUiState) {
        fileAdapter.render(state.entries, state.selectedPaths)
        binding.progressNasFiles.visibility = if (state.loading || state.downloadInProgress) View.VISIBLE else View.GONE
        binding.rvNasFiles.visibility = if (!state.loading && state.entries.isNotEmpty()) View.VISIBLE else View.GONE
        binding.tvNasFilesEmpty.visibility = if (!state.loading && state.entries.isEmpty()) View.VISIBLE else View.GONE
        binding.tvNasFilesEmpty.setText(
            if (state.loadFailed) com.wkq.bao.feature.res.R.string.nas_file_load_failed
            else com.wkq.bao.feature.res.R.string.nas_file_empty
        )
        binding.tvFilePath.text = "/${state.currentPath.trim('/')}"
        binding.btnFileUp.isEnabled = state.currentPath != state.rootPath && !state.loading
        binding.btnSelectAll.isEnabled = state.entries.isNotEmpty() && !state.loading
        binding.btnSelectAll.setText(
            if (state.entries.isNotEmpty() && state.selectedPaths.containsAll(state.entries.map { it.path })) {
                com.wkq.bao.feature.res.R.string.nas_file_clear_selection
            } else com.wkq.bao.feature.res.R.string.nas_file_select_all
        )
        binding.tvSelectedCount.text = resources.getQuantityString(
            com.wkq.bao.feature.res.R.plurals.nas_file_selected_count,
            state.selectedPaths.size,
            state.selectedPaths.size
        )
        binding.btnDownloadSelected.isEnabled = state.selectedPaths.isNotEmpty() && !state.downloadInProgress
    }

    private fun selectDownloadTarget() {
        val source = activeSource ?: return showMissingSource()
        if (viewModel.browserState.value.selectedPaths.isEmpty() || waitingForDownloadTarget) return
        storageManager.getAvailableExternalStorageTarget()?.let { target ->
            viewModel.enqueueSelected(source, target)
            return
        }
        waitingForDownloadTarget = true
        openDownloadTargetLauncher.launch(
            storageManager.getStorageTarget()?.takeIf(storageManager::isExternalStorageTarget)?.uri
        )
    }

    private fun renderActiveSource(state: NasSettingsUiState = viewModel.uiState.value) {
        activeSource?.let { source ->
            binding.tvNasName.text = source.name
            binding.tvNasHost.text = "${source.type} - ${source.host}:${source.port}\n${source.shareName}/${source.rootPath.trim('/')}"
            binding.tvNasStatus.setText(if (source.enabled) com.wkq.bao.feature.res.R.string.status_enabled else com.wkq.bao.feature.res.R.string.status_disabled)
        } ?: run {
            binding.tvNasName.setText(com.wkq.bao.feature.res.R.string.nas_configuration_required)
            binding.tvNasHost.text = ""
            binding.tvNasStatus.setText(com.wkq.bao.feature.res.R.string.status_disconnected)
        }
        binding.btnScan.text = when {
            ScanSessionStatus.isActive(state.scanSession?.status.orEmpty()) ->
                getString(com.wkq.bao.feature.res.R.string.scan_action_cancel, state.scanSession?.importedCount ?: 0)
            state.scanSession?.status in setOf(ScanSessionStatus.FAILED, ScanSessionStatus.CANCELLED) ->
                getString(com.wkq.bao.feature.res.R.string.scan_action_retry)
            state.scanSession?.status == ScanSessionStatus.SUCCEEDED ->
                getString(com.wkq.bao.feature.res.R.string.scan_action_again, state.scanSession.importedCount)
            else -> getString(com.wkq.bao.feature.res.R.string.btn_scan_now)
        }
    }

    private fun showActiveSourceActions() {
        val source = activeSource
        if (source == null) {
            showNasListPopup(
                null,
                arrayOf(
                    getString(com.wkq.bao.feature.res.R.string.btn_export_diagnostics),
                    getString(com.wkq.bao.feature.res.R.string.btn_local_data_info)
                )
            ) { which ->
                    if (which == 0) shareDiagnostics() else showLocalDataInfo()
            }
            return
        }
        val actions = listOf(
            getString(com.wkq.bao.feature.res.R.string.btn_edit),
            getString(com.wkq.bao.feature.res.R.string.btn_switch_nas),
            getString(if (source.enabled) com.wkq.bao.feature.res.R.string.btn_disable else com.wkq.bao.feature.res.R.string.btn_enable),
            getString(com.wkq.bao.feature.res.R.string.btn_export_diagnostics),
            getString(com.wkq.bao.feature.res.R.string.btn_local_data_info),
            getString(com.wkq.bao.feature.res.R.string.btn_remove_nas)
        )
        showNasListPopup(source.name, actions.toTypedArray()) { which ->
            when (which) {
                0 -> showNasEditor(source)
                1 -> showSourcePicker()
                2 -> toggleSource(source)
                3 -> shareDiagnostics()
                4 -> showLocalDataInfo()
                5 -> confirmRemoveSource(source)
            }
        }
    }

    private fun confirmRemoveSource(source: NasSourceEntity) {
        nasPopupBuilder()
            .asConfirm(
                getString(com.wkq.bao.feature.res.R.string.title_remove_nas),
                getString(com.wkq.bao.feature.res.R.string.remove_nas_message),
                getString(android.R.string.cancel),
                getString(com.wkq.bao.feature.res.R.string.btn_remove_nas),
                { viewModel.remove(source.id) },
                null,
                false
            )
            .show()
    }

    private fun handleEvent(event: NasSettingsEvent) {
        when (event) {
            is NasSettingsEvent.SourceRemoved -> when (event.result) {
                NasSourceRemovalResult.Removed -> {
                    AppDiagnostics.record(requireContext(), "nas", "source_removed")
                    Toast.makeText(requireContext(), com.wkq.bao.feature.res.R.string.nas_removed, Toast.LENGTH_SHORT).show()
                }
                NasSourceRemovalResult.NotFound -> Unit
                NasSourceRemovalResult.StorageUnavailable -> {
                    Toast.makeText(requireContext(), com.wkq.bao.feature.res.R.string.nas_remove_storage_unavailable, Toast.LENGTH_LONG).show()
                }
                NasSourceRemovalResult.DownloadStillStopping -> {
                    Toast.makeText(requireContext(), com.wkq.bao.feature.res.R.string.nas_remove_download_stopping, Toast.LENGTH_LONG).show()
                }
                NasSourceRemovalResult.CleanupFailed -> {
                    Toast.makeText(requireContext(), com.wkq.bao.feature.res.R.string.nas_remove_cleanup_failed, Toast.LENGTH_LONG).show()
                }
            }
            is NasSettingsEvent.ConnectionTested -> {
                val diagnosticEvent = if (event.result.isSuccess) {
                    "connection_test_succeeded"
                } else {
                    "connection_test_failed_${NasFailureClassifier.code(event.result.exceptionOrNull())}"
                }
                AppDiagnostics.record(requireContext(), "nas", diagnosticEvent)
                activeEditorPopup?.let { popup ->
                    popup.showConnectionTestResult(event.result)
                    return
                }
                Toast.makeText(
                    requireContext(),
                    if (event.result.isSuccess) {
                        com.wkq.bao.feature.res.R.string.nas_editor_test_succeeded
                    } else {
                        com.wkq.bao.feature.res.R.string.nas_editor_test_failed
                    },
                    Toast.LENGTH_LONG
                ).show()
            }
            is NasSettingsEvent.FilesQueued -> Toast.makeText(
                requireContext(),
                if (event.count > 0) resources.getQuantityString(
                    com.wkq.bao.feature.res.R.plurals.nas_file_queued,
                    event.count,
                    event.count
                )
                else getString(com.wkq.bao.feature.res.R.string.download_no_items_queued),
                Toast.LENGTH_SHORT
            ).show()
            NasSettingsEvent.FileActionFailed -> Toast.makeText(
                requireContext(),
                com.wkq.bao.feature.res.R.string.nas_file_action_failed,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showSourcePicker() {
        if (sources.isEmpty()) return showMissingSource()
        showNasListPopup(
            getString(com.wkq.bao.feature.res.R.string.btn_switch_nas),
            sources.map { it.name }.toTypedArray()
        ) { which -> viewModel.selectSource(sources[which].id) }
    }

    private fun toggleSource(source: NasSourceEntity) {
        viewModel.toggleEnabled(source)
        AppDiagnostics.record(requireContext(), "nas", if (source.enabled) "source_disabled" else "source_enabled")
    }

    private fun shareDiagnostics() {
        runCatching {
            val chooser = android.content.Intent.createChooser(
                AppDiagnostics.createShareIntent(requireContext()),
                getString(com.wkq.bao.feature.res.R.string.title_export_diagnostics)
            )
            startActivity(chooser)
        }.onFailure {
            Toast.makeText(requireContext(), com.wkq.bao.feature.res.R.string.diagnostics_export_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun showLocalDataInfo() {
        nasPopupBuilder()
            .asConfirm(
                getString(com.wkq.bao.feature.res.R.string.title_local_data_info),
                getString(com.wkq.bao.feature.res.R.string.local_data_info_message),
                getString(com.wkq.bao.feature.res.R.string.btn_clear_diagnostics),
                getString(android.R.string.ok),
                { },
                {
                    AppDiagnostics.clear(requireContext())
                    Toast.makeText(requireContext(), com.wkq.bao.feature.res.R.string.diagnostics_cleared, Toast.LENGTH_SHORT).show()
                },
                false
            )
            .show()
    }

    private fun testActiveSource() {
        val source = activeSource ?: return showMissingSource()
        AppDiagnostics.record(requireContext(), "nas", "connection_test_requested_saved")
        viewModel.testConnection(source)
    }

    private fun scanActiveSource() {
        val source = activeSource ?: return showMissingSource()
        viewModel.handleScanAction()
    }

    private fun showMissingSource() {
        Toast.makeText(requireContext(), com.wkq.bao.feature.res.R.string.nas_configuration_required, Toast.LENGTH_SHORT).show()
        binding.cardAddNas.requestFocus()
    }

    private fun showNasEditor(source: NasSourceEntity?) {
        AppDiagnostics.record(requireContext(), "nas", if (source == null) "editor_opened_add" else "editor_opened_edit")
        val display = resources.displayMetrics
        val margin = (16 * display.density).roundToInt()
        val maxWidth = minOf((560 * display.density).roundToInt(), display.widthPixels - margin * 2)
        val maxHeight = minOf((680 * display.density).roundToInt(), (display.heightPixels * 0.82f).roundToInt())
        val popup = NasEditorPopup(
            context = requireContext(),
            source = source,
            initialDraft = viewModel.editorDraftFor(source?.id),
            onDraftChanged = viewModel::updateEditorDraft,
            onDiscardDraft = { viewModel.clearEditorDraft(source?.id) },
            onTest = { submission -> testNasEditor(source, submission) },
            onSave = { submission -> saveNasEditor(source, submission) },
            onDismissed = { dismissedPopup ->
                if (activeEditorPopup === dismissedPopup) activeEditorPopup = null
            }
        )
        activeEditorPopup = popup
        nasPopupBuilder()
            .dismissOnTouchOutside(false)
            .autoOpenSoftInput(false)
            .autoFocusEditText(false)
            .moveUpToKeyboard(true)
            .popupWidth(maxWidth)
            .popupHeight(maxHeight)
            .maxWidth(maxWidth)
            .maxHeight(maxHeight)
            .asCustom(popup)
            .show()
    }

    private fun testNasEditor(source: NasSourceEntity?, submission: NasEditorSubmission): Boolean {
        val candidate = buildNasSource(source, submission) ?: return false
        AppDiagnostics.record(requireContext(), "nas", "connection_test_requested_editor")
        viewModel.testConnection(candidate)
        return true
    }

    private fun saveNasEditor(source: NasSourceEntity?, submission: NasEditorSubmission): Boolean {
        val nasSource = buildNasSource(source, submission) ?: return false
        AppDiagnostics.record(requireContext(), "nas", if (source == null) "source_save_requested_add" else "source_save_requested_update")
        viewModel.clearEditorDraft(source?.id)
        viewModel.save(nasSource)
        AppDiagnostics.record(requireContext(), "nas", if (source == null) "source_added" else "source_updated")
        return true
    }

    private fun buildNasSource(source: NasSourceEntity?, submission: NasEditorSubmission): NasSourceEntity? {
        val encryptedPassword = runCatching {
            submission.password.takeIf { it.isNotEmpty() }
                ?.let(NasCredentialVault::encrypt)
                ?: source?.passwordEncrypted.orEmpty()
        }.getOrElse {
            Toast.makeText(requireContext(), com.wkq.bao.feature.res.R.string.nas_password_protection_failed, Toast.LENGTH_LONG).show()
            return null
        }
        return NasSourceEntity(
            id = source?.id ?: 0L,
            name = submission.draft.name.ifEmpty { submission.draft.host },
            type = submission.draft.type,
            host = submission.draft.host,
            port = submission.draft.port,
            shareName = submission.draft.shareName,
            rootPath = submission.draft.rootPath,
            username = submission.draft.username,
            passwordEncrypted = encryptedPassword,
            enabled = source?.enabled ?: true,
            createdAt = source?.createdAt ?: System.currentTimeMillis(),
            lastScanAt = source?.lastScanAt ?: 0L
        )
    }

    private fun showNasListPopup(title: String?, actions: Array<String>, onSelected: (Int) -> Unit) {
        nasPopupBuilder()
            .asCenterList(title, actions) { position, _ -> onSelected(position) }
            .show()
    }

    private fun nasPopupBuilder(): XPopup.Builder = XPopup.Builder(requireContext())
        .customHostLifecycle(viewLifecycleOwner.lifecycle)
        .isDarkTheme(true)

    override fun onDestroyView() {
        activeEditorPopup = null
        _binding = null
        super.onDestroyView()
    }
}
