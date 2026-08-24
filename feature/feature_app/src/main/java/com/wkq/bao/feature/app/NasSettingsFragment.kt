package com.wkq.bao.feature.app

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wkq.bao.core.base.diagnostics.AppDiagnostics
import com.wkq.bao.core.database.entity.NasSourceEntity
import com.wkq.bao.core.media.download.NasSourceRemovalResult
import com.wkq.bao.core.database.entity.ScanSessionStatus
import com.wkq.bao.core.nas.security.NasCredentialVault
import com.wkq.bao.feature.app.databinding.ActivityNasSettingsBinding
import com.wkq.bao.feature.app.utils.TvFocusHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class NasSettingsFragment : Fragment() {
    private var _binding: ActivityNasSettingsBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val viewModel by viewModels<NasSettingsViewModel> { NasSettingsViewModel.Factory(requireContext()) }
    private var activeSource: NasSourceEntity? = null
    private var sources: List<NasSourceEntity> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ActivityNasSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listOf(binding.cardActiveNas, binding.cardAddNas, binding.btnScan, binding.btnTestConn).forEach(TvFocusHelper::applyFocusScale)
        binding.cardActiveNas.setOnClickListener { showActiveSourceActions() }
        binding.cardAddNas.setOnClickListener { showNasEditor(null) }
        binding.btnScan.setOnClickListener { scanActiveSource() }
        binding.btnTestConn.setOnClickListener { testActiveSource() }
        TvFocusHelper.requestInitialFocus(binding.root, binding.cardActiveNas)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collectLatest { state ->
                        sources = state.sources
                        activeSource = state.activeSource
                        renderActiveSource(state)
                    }
                }
                launch {
                    viewModel.events.collectLatest(::handleEvent)
                }
            }
        }
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
            AlertDialog.Builder(requireContext())
                .setItems(
                    arrayOf(
                        getString(com.wkq.bao.feature.res.R.string.btn_export_diagnostics),
                        getString(com.wkq.bao.feature.res.R.string.btn_local_data_info)
                    )
                ) { _, which ->
                    if (which == 0) shareDiagnostics() else showLocalDataInfo()
                }
                .show()
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
        AlertDialog.Builder(requireContext())
            .setTitle(source.name)
            .setItems(actions.toTypedArray()) { _, which ->
                when (which) {
                    0 -> showNasEditor(source)
                    1 -> showSourcePicker()
                    2 -> toggleSource(source)
                    3 -> shareDiagnostics()
                    4 -> showLocalDataInfo()
                    5 -> confirmRemoveSource(source)
                }
            }
            .show()
    }

    private fun confirmRemoveSource(source: NasSourceEntity) {
        AlertDialog.Builder(requireContext())
            .setTitle(com.wkq.bao.feature.res.R.string.title_remove_nas)
            .setMessage(com.wkq.bao.feature.res.R.string.remove_nas_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(com.wkq.bao.feature.res.R.string.btn_remove_nas) { _, _ ->
                viewModel.remove(source.id)
            }
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
                AppDiagnostics.record(requireContext(), "nas", if (event.result.isSuccess) "connection_test_succeeded" else "connection_test_failed")
                Toast.makeText(requireContext(), event.result.getOrElse { it.message ?: getString(com.wkq.bao.feature.res.R.string.status_disconnected) }, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showSourcePicker() {
        if (sources.isEmpty()) return showMissingSource()
        AlertDialog.Builder(requireContext())
            .setTitle(com.wkq.bao.feature.res.R.string.btn_switch_nas)
            .setItems(sources.map { it.name }.toTypedArray()) { _, which ->
                viewModel.selectSource(sources[which].id)
            }
            .show()
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
        AlertDialog.Builder(requireContext())
            .setTitle(com.wkq.bao.feature.res.R.string.title_local_data_info)
            .setMessage(com.wkq.bao.feature.res.R.string.local_data_info_message)
            .setNegativeButton(com.wkq.bao.feature.res.R.string.btn_clear_diagnostics) { _, _ ->
                AppDiagnostics.clear(requireContext())
                Toast.makeText(requireContext(), com.wkq.bao.feature.res.R.string.diagnostics_cleared, Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun testActiveSource() {
        val source = activeSource ?: return showMissingSource()
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
        val container = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 8); gravity = Gravity.CENTER_VERTICAL }
        fun field(label: Int, value: String, password: Boolean = false) = EditText(requireContext()).apply {
            hint = getString(label); setText(value); isSingleLine = true
            inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
            container.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val name = field(com.wkq.bao.feature.res.R.string.nas_name, source?.name.orEmpty())
        val host = field(com.wkq.bao.feature.res.R.string.nas_host, source?.host.orEmpty())
        val share = field(com.wkq.bao.feature.res.R.string.nas_share, source?.shareName.orEmpty())
        val root = field(com.wkq.bao.feature.res.R.string.nas_root_path, source?.rootPath.orEmpty())
        val username = field(com.wkq.bao.feature.res.R.string.nas_username, source?.username.orEmpty())
        val password = field(com.wkq.bao.feature.res.R.string.nas_password, "", true)
        AlertDialog.Builder(requireContext())
            .setTitle(if (source == null) com.wkq.bao.feature.res.R.string.btn_add_nas else com.wkq.bao.feature.res.R.string.btn_edit_nas)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(com.wkq.bao.feature.res.R.string.btn_save) { _, _ ->
                val normalizedHost = host.text.toString().trim()
                val normalizedShare = share.text.toString().trim().trim('/')
                if (normalizedHost.isEmpty() || normalizedShare.isEmpty()) return@setPositiveButton
                val encryptedPassword = runCatching {
                    password.text.toString().takeIf { it.isNotEmpty() }
                        ?.let(NasCredentialVault::encrypt)
                        ?: source?.passwordEncrypted.orEmpty()
                }.getOrElse {
                    Toast.makeText(requireContext(), com.wkq.bao.feature.res.R.string.nas_password_protection_failed, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                viewModel.save(NasSourceEntity(
                        id = source?.id ?: 0L,
                        name = name.text.toString().trim().ifEmpty { normalizedHost },
                        host = normalizedHost,
                        shareName = normalizedShare,
                        rootPath = root.text.toString().trim(),
                        username = username.text.toString().trim(),
                        passwordEncrypted = encryptedPassword,
                        enabled = source?.enabled ?: true,
                        createdAt = source?.createdAt ?: System.currentTimeMillis(),
                        lastScanAt = source?.lastScanAt ?: 0L
                    ))
                AppDiagnostics.record(requireContext(), "nas", if (source == null) "source_added" else "source_updated")
            }.show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
