package com.wkq.bao.feature.app

import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.lxj.xpopup.XPopup
import com.wkq.base.activity.BaseActivity
import com.wkq.bao.core.database.entity.NasSourceEntity
import com.wkq.bao.core.database.entity.ScanSessionEntity
import com.wkq.bao.core.database.entity.ScanSessionStatus
import com.wkq.bao.core.media.storage.TvStorageManager
import com.wkq.bao.core.nas.security.NasCredentialVault
import com.wkq.bao.feature.app.adapter.NasFileAdapter
import com.wkq.bao.feature.app.databinding.ActivityNasBrowserBinding
import com.wkq.bao.feature.app.utils.TvFocusHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** NAS 的唯一媒体浏览入口：只浏览和下载视频及其同名弹幕、字幕附件。 */
class NasBrowserActivity : BaseActivity<ActivityNasBrowserBinding>() {
    companion object {
        fun start(context: android.content.Context) {
            context.startActivity(
                Intent(context, NasBrowserActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    private val viewModel by viewModels<NasSettingsViewModel> { NasSettingsViewModel.Factory(applicationContext) }
    private val storageManager by lazy { TvStorageManager(this) }
    private lateinit var fileAdapter: NasFileAdapter
    private var activeSource: NasSourceEntity? = null
    private var sources: List<NasSourceEntity> = emptyList()
    private var activeEditorPopup: NasEditorPopup? = null
    private var waitingForDownloadTarget = false

    private val openDownloadTargetLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        waitingForDownloadTarget = false
        if (uri == null) return@registerForActivityResult
        runCatching {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, flags)
            require(storageManager.isStorageTargetWritable(uri)) {
                getString(com.wkq.bao.feature.res.R.string.storage_location_invalid)
            }
            storageManager.saveStorageRoot(uri, storageManager.resolveLocalLocation(uri))
            checkNotNull(storageManager.getAvailableStorageTarget())
        }.onSuccess { target ->
            activeSource?.let { viewModel.enqueueSelected(it, target) }
        }.onFailure { error ->
            Toast.makeText(
                this,
                error.message ?: getString(com.wkq.bao.feature.res.R.string.storage_permission_failed),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun initView() {
        configureInsets()
        fileAdapter = NasFileAdapter(
            onToggleSelection = viewModel::toggleSelection,
            onOpenDirectory = { entry -> activeSource?.let { viewModel.openDirectory(it, entry) } }
        )
        binding.rvNasFiles.adapter = fileAdapter
        binding.btnBack.setOnClickListener { navigateUp() }
        binding.btnNasSource.setOnClickListener { showSourcePicker() }
        binding.btnNasScan.setOnClickListener { viewModel.handleScanAction() }
        binding.btnNasLibrary.setOnClickListener {
            HomeActivity.open(this, MainPageNavigator.LIBRARY)
            finish()
        }
        binding.btnNasMore.setOnClickListener { showSourceActions() }
        binding.btnNasEdit.setOnClickListener { showNasEditor(activeSource) }
        binding.btnRefreshFiles.setOnClickListener { activeSource?.let(viewModel::refreshFiles) }
        binding.tvNasFilesEmpty.setOnClickListener { activeSource?.let(viewModel::refreshFiles) ?: showNasEditor(null) }
        binding.btnFileUp.setOnClickListener { activeSource?.let(viewModel::goUp) }
        binding.btnSelectAll.setOnClickListener { viewModel.toggleSelectAll() }
        binding.btnDownloadSelected.setOnClickListener { downloadSelection() }
        listOf(
            binding.btnBack,
            binding.btnNasSource,
            binding.btnNasScan,
            binding.btnNasLibrary,
            binding.tvNasFilesEmpty,
            binding.btnNasEdit,
            binding.btnNasMore,
            binding.btnRefreshFiles,
            binding.btnFileUp,
            binding.btnSelectAll,
            binding.btnDownloadSelected
        ).forEach { control ->
            // 使用自定义深色状态背景，避免 Material 主色染成浅底浅字。
            control.backgroundTintList = null
            TvFocusHelper.applyFocusScale(control)
        }
        TvFocusHelper.requestInitialFocus(binding.root, binding.btnNasSource)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = navigateUp()
        })
    }

    private fun configureInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val initialLeft = binding.root.paddingLeft
        val initialTop = binding.root.paddingTop
        val initialRight = binding.root.paddingRight
        val initialBottom = binding.root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.updatePadding(
                left = initialLeft + bars.left,
                top = initialTop + bars.top,
                right = initialRight + bars.right,
                bottom = initialBottom + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    override fun initData() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collectLatest { state ->
                        sources = state.sources
                        activeSource = state.activeSource
                        renderSource(state.activeSource)
                        renderScan(state.activeSource, state.scanSession)
                        if (state.activeSource != null) viewModel.openRoot(state.activeSource)
                        else renderBrowserState(NasBrowserUiState())
                    }
                }
                launch { viewModel.browserState.collectLatest(::renderBrowserState) }
                launch { viewModel.events.collectLatest(::handleEvent) }
            }
        }
    }

    private fun renderSource(source: NasSourceEntity?) {
        binding.btnNasSource.text = source?.name
            ?: getString(com.wkq.bao.feature.res.R.string.btn_add_nas)
        binding.tvNasAddress.visibility = View.GONE
        binding.btnNasEdit.setText(
            if (source == null) com.wkq.bao.feature.res.R.string.btn_add_nas
            else com.wkq.bao.feature.res.R.string.btn_edit
        )
        binding.btnNasEdit.isVisible = source != null
        binding.btnNasScan.isVisible = source != null
        binding.btnRefreshFiles.isEnabled = source != null
    }

    private fun renderScan(source: NasSourceEntity?, session: ScanSessionEntity?) {
        binding.btnNasScan.isVisible = source != null
        binding.btnNasScan.isEnabled = source != null
        binding.tvNasScanStatus.text = when {
            source == null -> getString(com.wkq.bao.feature.res.R.string.nas_setup_steps)
            ScanSessionStatus.isActive(session?.status.orEmpty()) -> getString(com.wkq.bao.feature.res.R.string.scan_progress, session?.importedCount ?: 0) + "\n" + getString(com.wkq.bao.feature.res.R.string.nas_scan_background_hint)
            session?.status == ScanSessionStatus.SUCCEEDED -> getString(com.wkq.bao.feature.res.R.string.scan_complete, session.importedCount)
            session?.status == ScanSessionStatus.FAILED -> getString(com.wkq.bao.feature.res.R.string.nas_scan_failed_guidance)
            session?.status == ScanSessionStatus.CANCELLED -> getString(com.wkq.bao.feature.res.R.string.scan_cancelled)
            else -> getString(com.wkq.bao.feature.res.R.string.nas_scan_ready_hint)
        }
        binding.btnNasScan.text = when {
            source == null -> getString(com.wkq.bao.feature.res.R.string.btn_scan_now)
            ScanSessionStatus.isActive(session?.status.orEmpty()) -> getString(
                com.wkq.bao.feature.res.R.string.scan_action_cancel,
                session?.importedCount ?: 0
            )
            session?.status in setOf(ScanSessionStatus.FAILED, ScanSessionStatus.CANCELLED) ->
                getString(com.wkq.bao.feature.res.R.string.scan_action_retry)
            session?.status == ScanSessionStatus.SUCCEEDED -> getString(
                com.wkq.bao.feature.res.R.string.scan_action_again,
                session.importedCount
            )
            else -> getString(com.wkq.bao.feature.res.R.string.btn_scan_now)
        }
    }

    private fun renderBrowserState(input: NasBrowserUiState) {
        val state = if (activeSource == null) NasBrowserUiState() else input
        fileAdapter.render(state.entries, state.selectedPaths)
        binding.progressNasFiles.visibility = if (state.loading || state.downloadInProgress) View.VISIBLE else View.GONE
        // 保留列表的布局边界，让居中的加载/空态覆盖层仍有可用宽高。
        binding.rvNasFiles.visibility = if (!state.loading && state.entries.isNotEmpty()) View.VISIBLE else View.INVISIBLE
        binding.tvNasFilesEmpty.visibility = if (!state.loading && state.entries.isEmpty()) View.VISIBLE else View.GONE
        binding.tvNasFilesEmpty.setText(
            if (activeSource == null) com.wkq.bao.feature.res.R.string.nas_configuration_required
            else if (state.loadFailed) com.wkq.bao.feature.res.R.string.nas_file_load_failed_retry
            else com.wkq.bao.feature.res.R.string.nas_file_empty
        )
        binding.tvNasFilesEmpty.isEnabled = activeSource == null || state.loadFailed
        binding.tvFilePath.text = "/${state.currentPath.trim('/')}"
        binding.btnFileUp.isEnabled = state.currentPath != state.rootPath && !state.loading
        binding.btnSelectAll.isEnabled = state.entries.isNotEmpty() && !state.loading
        binding.btnSelectAll.setText(
            if (state.entries.isNotEmpty() && state.selectedPaths.containsAll(state.entries.map { it.path })) {
                com.wkq.bao.feature.res.R.string.nas_file_clear_selection
            } else {
                com.wkq.bao.feature.res.R.string.nas_file_select_all
            }
        )
        val selectedEntries = state.entries.filter { state.selectedPaths.contains(it.path) }
        val totalBytes = selectedEntries.sumOf { it.size }
        val sizeFormatted = android.text.format.Formatter.formatFileSize(this, totalBytes)
        val countText = resources.getQuantityString(
            com.wkq.bao.feature.res.R.plurals.nas_file_selected_count,
            state.selectedPaths.size,
            state.selectedPaths.size
        )
        binding.tvSelectedCount.text = getString(com.wkq.bao.feature.res.R.string.nas_selection_size_format, countText, sizeFormatted)
        binding.layoutDownload.isVisible = state.selectedPaths.isNotEmpty()
        binding.btnDownloadSelected.isEnabled = state.selectedPaths.isNotEmpty() && !state.downloadInProgress
    }

    private fun downloadSelection() {
        val source = activeSource ?: return showNasEditor(null)
        if (viewModel.browserState.value.selectedPaths.isEmpty() || waitingForDownloadTarget) return

        val targets = storageManager.getStorageTargets().filter { it.isAvailable && storageManager.isStorageTargetWritable(it.uri) }
        val devices = storageManager.getConnectedDevices()

        if (targets.isEmpty() && devices.isEmpty()) {
            requestStorageTargetAuth()
            return
        }

        val targetOptions = mutableListOf<com.wkq.bao.core.media.storage.TvStorageManager.StorageTarget>()
        val labels = mutableListOf<String>()

        targets.forEach { target ->
            targetOptions.add(target)
            val typeName = when (target.location) {
                com.wkq.bao.core.media.storage.MediaStorageLocation.INTERNAL_STORAGE -> getString(com.wkq.bao.feature.res.R.string.storage_internal)
                com.wkq.bao.core.media.storage.MediaStorageLocation.TF_CARD -> getString(com.wkq.bao.feature.res.R.string.storage_tf_card)
                com.wkq.bao.core.media.storage.MediaStorageLocation.USB_DRIVE -> getString(com.wkq.bao.feature.res.R.string.storage_usb_drive)
                else -> getString(com.wkq.bao.feature.res.R.string.storage_external)
            }
            val info = storageManager.getStorageInfo(target)
            val spaceText = if (info.freeBytes > 0) " · " + getString(com.wkq.bao.feature.res.R.string.storage_available_format, info.formattedFree) else ""
            val label = "$typeName$spaceText"
            labels.add(if (storageManager.getStorageRoot() == target.uri.toString()) getString(com.wkq.bao.feature.res.R.string.storage_target_current, label) else label)
        }

        devices.forEach { device ->
            labels.add(getString(com.wkq.bao.feature.res.R.string.storage_saved_target_format, device.name, getString(com.wkq.bao.feature.res.R.string.storage_target_online)))
        }

        labels.add(getString(com.wkq.bao.feature.res.R.string.nas_sync_add_new_target))

        XPopup.Builder(this)
            .isDarkTheme(true)
            .maxWidth((460 * resources.displayMetrics.density).toInt())
            .asCenterList(getString(com.wkq.bao.feature.res.R.string.nas_sync_target_title), labels.toTypedArray()) { index, _ ->
                when {
                    index < targetOptions.size -> {
                        val selectedTarget = targetOptions[index]
                        storageManager.saveStorageRoot(selectedTarget.uri, selectedTarget.location)
                        viewModel.enqueueSelected(source, selectedTarget)
                    }
                    index < targetOptions.size + devices.size -> {
                        val selectedDevice = devices[index - targetOptions.size]
                        requestStorageTargetAuth(selectedDevice.initialUri)
                    }
                    else -> requestStorageTargetAuth(null)
                }
            }.show()
    }

    private fun requestStorageTargetAuth(initialUri: android.net.Uri? = null) {
        val physicalDevices = storageManager.getTvPhysicalStorageDevices().filter { !it.isReadOnly }
        val handler = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).resolveActivity(packageManager)
        if (handler == null || handler.packageName == "com.android.tv.frameworkpackagestubs") {
            if (physicalDevices.isEmpty()) {
                Toast.makeText(this, com.wkq.bao.feature.res.R.string.storage_picker_missing, Toast.LENGTH_LONG).show()
                return
            }
            val options = physicalDevices.map { getString(com.wkq.bao.feature.res.R.string.storage_device_space_format, if (it.location == com.wkq.bao.core.media.storage.MediaStorageLocation.INTERNAL_STORAGE) getString(com.wkq.bao.feature.res.R.string.storage_internal) else it.name, it.formattedFree) }.toTypedArray()
            XPopup.Builder(this).isDarkTheme(true)
                .asCenterList(getString(com.wkq.bao.feature.res.R.string.nas_sync_target_title), options) { index, _ ->
                    val selected = physicalDevices.getOrNull(index) ?: return@asCenterList
                    storageManager.saveStorageRoot(selected.uri, selected.location)
                    activeSource?.let { src ->
                        storageManager.getAvailableStorageTarget()?.let { target ->
                            viewModel.enqueueSelected(src, target)
                        }
                    }
                }.show()
            return
        }
        try {
            waitingForDownloadTarget = true
            openDownloadTargetLauncher.launch(
                initialUri ?: storageManager.getStorageTarget()?.takeIf { it.isAvailable }?.uri
            )
        } catch (_: Exception) {
            waitingForDownloadTarget = false
            if (physicalDevices.isNotEmpty()) {
                val options = physicalDevices.map { getString(com.wkq.bao.feature.res.R.string.storage_device_space_format, if (it.location == com.wkq.bao.core.media.storage.MediaStorageLocation.INTERNAL_STORAGE) getString(com.wkq.bao.feature.res.R.string.storage_internal) else it.name, it.formattedFree) }.toTypedArray()
                XPopup.Builder(this).isDarkTheme(true)
                    .asCenterList(getString(com.wkq.bao.feature.res.R.string.nas_sync_target_title), options) { index, _ ->
                        val selected = physicalDevices.getOrNull(index) ?: return@asCenterList
                        storageManager.saveStorageRoot(selected.uri, selected.location)
                        activeSource?.let { src ->
                            storageManager.getAvailableStorageTarget()?.let { target ->
                                viewModel.enqueueSelected(src, target)
                            }
                        }
                    }.show()
            } else {
                Toast.makeText(this, com.wkq.bao.feature.res.R.string.storage_picker_missing, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun navigateUp() {
        val source = activeSource
        val browser = viewModel.browserState.value
        if (source != null && browser.currentPath != browser.rootPath) {
            viewModel.goUp(source)
        } else {
            finish()
        }
    }

    private fun showSourceActions() {
        val source = activeSource
        val actions = mutableListOf<Pair<Int, () -> Unit>>()
        if (source != null) {
            actions += com.wkq.bao.feature.res.R.string.btn_test_conn to { viewModel.testConnection(source); Unit }
            actions += (if (source.enabled) com.wkq.bao.feature.res.R.string.btn_disable else com.wkq.bao.feature.res.R.string.btn_enable) to { viewModel.toggleEnabled(source); Unit }
        }
        actions += com.wkq.bao.feature.res.R.string.btn_export_diagnostics to {
            runCatching {
                startActivity(Intent.createChooser(com.wkq.bao.core.base.diagnostics.AppDiagnostics.createShareIntent(this), getString(com.wkq.bao.feature.res.R.string.title_export_diagnostics)))
            }.onFailure { Toast.makeText(this, com.wkq.bao.feature.res.R.string.diagnostics_export_failed, Toast.LENGTH_LONG).show() }
            Unit
        }
        actions += com.wkq.bao.feature.res.R.string.btn_local_data_info to {
            XPopup.Builder(this).isDarkTheme(true).asConfirm(
                getString(com.wkq.bao.feature.res.R.string.title_local_data_info),
                getString(com.wkq.bao.feature.res.R.string.local_data_info_message),
                getString(com.wkq.bao.feature.res.R.string.btn_clear_diagnostics), getString(android.R.string.ok),
                {}, { com.wkq.bao.core.base.diagnostics.AppDiagnostics.clear(this) }, false
            ).show()
            Unit
        }
        if (source != null) actions += com.wkq.bao.feature.res.R.string.btn_remove_nas to {
            XPopup.Builder(this).isDarkTheme(true).asConfirm(
                getString(com.wkq.bao.feature.res.R.string.title_remove_nas),
                getString(com.wkq.bao.feature.res.R.string.remove_nas_message),
                getString(android.R.string.cancel), getString(com.wkq.bao.feature.res.R.string.btn_remove_nas),
                { viewModel.remove(source.id) }, null, false
            ).show()
            Unit
        }
        XPopup.Builder(this).isDarkTheme(true)
            .asCenterList(getString(com.wkq.bao.feature.res.R.string.nas_source_actions), actions.map { getString(it.first) }.toTypedArray()) { index, _ -> actions[index].second() }.show()
    }

    private fun showSourcePicker() {
        val names = sources.map(NasSourceEntity::name) + getString(com.wkq.bao.feature.res.R.string.btn_add_nas)
        XPopup.Builder(this)
            .isDarkTheme(true)
            .asCenterList(getString(com.wkq.bao.feature.res.R.string.btn_switch_nas), names.toTypedArray()) { position, _ ->
                if (position == sources.size) showNasEditor(null) else viewModel.selectSource(sources[position].id)
            }
            .show()
    }

    private fun showNasEditor(source: NasSourceEntity?) {
        val display = resources.displayMetrics
        val margin = (16 * display.density).roundToInt()
        val maxWidth = minOf((560 * display.density).roundToInt(), display.widthPixels - margin * 2)
        val maxHeight = minOf((680 * display.density).roundToInt(), (display.heightPixels * 0.82f).roundToInt())
        val popup = NasEditorPopup(
            context = this,
            source = source,
            initialDraft = viewModel.editorDraftFor(source?.id),
            onDraftChanged = viewModel::updateEditorDraft,
            onDiscardDraft = { viewModel.clearEditorDraft(source?.id) },
            onTest = { submission ->
                buildNasSource(source, submission)?.let(viewModel::testConnection) != null
            },
            onSave = { submission ->
                buildNasSource(source, submission)?.let(viewModel::save) != null
            },
            onDismissed = { dismissed -> if (activeEditorPopup === dismissed) activeEditorPopup = null }
        )
        activeEditorPopup = popup
        XPopup.Builder(this)
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

    private fun buildNasSource(source: NasSourceEntity?, submission: NasEditorSubmission): NasSourceEntity? {
        val encryptedPassword = runCatching {
            submission.password.takeIf(String::isNotEmpty)
                ?.let(NasCredentialVault::encrypt)
                ?: source?.passwordEncrypted.orEmpty()
        }.getOrElse {
            Toast.makeText(this, com.wkq.bao.feature.res.R.string.nas_password_protection_failed, Toast.LENGTH_LONG).show()
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

    private fun handleEvent(event: NasSettingsEvent) {
        when (event) {
            is NasSettingsEvent.ConnectionTested -> {
                activeEditorPopup?.showConnectionTestResult(event.result)
                    ?: Toast.makeText(
                        this,
                        if (event.result.isSuccess) com.wkq.bao.feature.res.R.string.nas_editor_test_succeeded
                        else com.wkq.bao.feature.res.R.string.nas_editor_test_failed,
                        Toast.LENGTH_LONG
                    ).show()
            }
            is NasSettingsEvent.FilesQueued -> {
                Toast.makeText(
                    this,
                    if (event.count > 0) resources.getQuantityString(
                        com.wkq.bao.feature.res.R.plurals.nas_file_queued,
                        event.count,
                        event.count
                    ) else getString(com.wkq.bao.feature.res.R.string.download_no_items_queued),
                    Toast.LENGTH_SHORT
                ).show()
                if (event.count > 0) {
                    HomeActivity.openDownloads(this)
                    finish()
                }
            }
            NasSettingsEvent.FileActionFailed -> Toast.makeText(
                this,
                com.wkq.bao.feature.res.R.string.nas_file_action_failed,
                Toast.LENGTH_LONG
            ).show()
            is NasSettingsEvent.SourceRemoved -> {
                val message = when (event.result) {
                    com.wkq.bao.core.media.download.NasSourceRemovalResult.Removed -> com.wkq.bao.feature.res.R.string.nas_removed
                    com.wkq.bao.core.media.download.NasSourceRemovalResult.NotFound -> com.wkq.bao.feature.res.R.string.nas_removed
                    com.wkq.bao.core.media.download.NasSourceRemovalResult.StorageUnavailable -> com.wkq.bao.feature.res.R.string.nas_remove_storage_unavailable
                    com.wkq.bao.core.media.download.NasSourceRemovalResult.DownloadStillStopping -> com.wkq.bao.feature.res.R.string.nas_remove_download_stopping
                    com.wkq.bao.core.media.download.NasSourceRemovalResult.CleanupFailed -> com.wkq.bao.feature.res.R.string.nas_remove_cleanup_failed
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }
}
