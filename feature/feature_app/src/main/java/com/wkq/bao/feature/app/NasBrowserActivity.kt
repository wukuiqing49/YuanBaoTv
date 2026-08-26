package com.wkq.bao.feature.app

import android.content.Intent
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
            context.startActivity(Intent(context, NasBrowserActivity::class.java))
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
            require(storageManager.isStorageTargetAvailable(uri)) {
                getString(com.wkq.bao.feature.res.R.string.storage_location_invalid)
            }
            require(storageManager.isExternalStorageTarget(uri)) {
                getString(com.wkq.bao.feature.res.R.string.storage_external_target_required)
            }
            storageManager.saveStorageRoot(uri, storageManager.resolveLocalLocation(uri))
            checkNotNull(storageManager.getAvailableExternalStorageTarget())
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
        binding.btnNasEdit.setOnClickListener { showNasEditor(activeSource) }
        binding.btnRefreshFiles.setOnClickListener { activeSource?.let(viewModel::refreshFiles) }
        binding.tvNasFilesEmpty.setOnClickListener { activeSource?.let(viewModel::refreshFiles) }
        binding.btnFileUp.setOnClickListener { activeSource?.let(viewModel::goUp) }
        binding.btnSelectAll.setOnClickListener { viewModel.toggleSelectAll() }
        binding.btnDownloadSelected.setOnClickListener { downloadSelection() }
        listOf(
            binding.btnBack,
            binding.btnNasSource,
            binding.btnNasEdit,
            binding.btnRefreshFiles,
            binding.btnFileUp,
            binding.btnSelectAll,
            binding.btnDownloadSelected
        ).forEach(TvFocusHelper::applyFocusScale)
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
                        state.activeSource?.let(viewModel::openRoot)
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
        binding.btnRefreshFiles.isEnabled = source != null
    }

    private fun renderBrowserState(state: NasBrowserUiState) {
        fileAdapter.render(state.entries, state.selectedPaths)
        binding.progressNasFiles.visibility = if (state.loading || state.downloadInProgress) View.VISIBLE else View.GONE
        binding.rvNasFiles.visibility = if (!state.loading && state.entries.isNotEmpty()) View.VISIBLE else View.GONE
        binding.tvNasFilesEmpty.visibility = if (!state.loading && state.entries.isEmpty()) View.VISIBLE else View.GONE
        binding.tvNasFilesEmpty.setText(
            if (activeSource == null) com.wkq.bao.feature.res.R.string.nas_configuration_required
            else if (state.loadFailed) com.wkq.bao.feature.res.R.string.nas_file_load_failed_retry
            else com.wkq.bao.feature.res.R.string.nas_file_empty
        )
        binding.tvNasFilesEmpty.isEnabled = activeSource != null && state.loadFailed
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
        binding.tvSelectedCount.text = resources.getQuantityString(
            com.wkq.bao.feature.res.R.plurals.nas_file_selected_count,
            state.selectedPaths.size,
            state.selectedPaths.size
        )
        binding.layoutDownload.isVisible = state.selectedPaths.isNotEmpty()
        binding.btnDownloadSelected.isEnabled = state.selectedPaths.isNotEmpty() && !state.downloadInProgress
    }

    private fun downloadSelection() {
        val source = activeSource ?: return showNasEditor(null)
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

    private fun navigateUp() {
        val source = activeSource
        val browser = viewModel.browserState.value
        if (source != null && browser.currentPath != browser.rootPath) {
            viewModel.goUp(source)
        } else {
            finish()
        }
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
                    HomeActivity.open(this, MainPageNavigator.DOWNLOADS)
                    finish()
                }
            }
            NasSettingsEvent.FileActionFailed -> Toast.makeText(
                this,
                com.wkq.bao.feature.res.R.string.nas_file_action_failed,
                Toast.LENGTH_LONG
            ).show()
            is NasSettingsEvent.SourceRemoved -> Unit
        }
    }
}
