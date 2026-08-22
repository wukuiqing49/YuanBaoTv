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
import androidx.lifecycle.lifecycleScope
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.database.entity.NasSourceEntity
import com.wkq.bao.core.media.scanner.NasScanner
import com.wkq.bao.core.media.smb.SmbClientManager
import com.wkq.bao.feature.app.databinding.ActivityNasSettingsBinding
import com.wkq.bao.feature.app.utils.TvFocusHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class NasSettingsFragment : Fragment() {
    private var _binding: ActivityNasSettingsBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private var activeSource: NasSourceEntity? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ActivityNasSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listOf(binding.cardActiveNas, binding.cardAddNas, binding.btnScan, binding.btnTestConn).forEach(TvFocusHelper::applyFocusScale)
        binding.cardActiveNas.setOnClickListener { showNasEditor(activeSource) }
        binding.cardAddNas.setOnClickListener { showNasEditor(null) }
        binding.btnScan.setOnClickListener { scanActiveSource() }
        binding.btnTestConn.setOnClickListener { testActiveSource() }
        viewLifecycleOwner.lifecycleScope.launch {
            database.nasDao().getAllSources().collectLatest { sources ->
                activeSource = sources.firstOrNull { it.enabled } ?: sources.firstOrNull()
                activeSource?.let { source ->
                    binding.tvNasName.text = source.name
                    binding.tvNasHost.text = "${source.type} - ${source.host}:${source.port}\n${source.shareName}/${source.rootPath.trim('/')}"
                    binding.tvNasStatus.setText(if (source.enabled) com.wkq.bao.feature.res.R.string.status_connected else com.wkq.bao.feature.res.R.string.status_disconnected)
                } ?: run {
                    binding.tvNasName.setText(com.wkq.bao.feature.res.R.string.nas_configuration_required)
                    binding.tvNasHost.text = ""
                    binding.tvNasStatus.setText(com.wkq.bao.feature.res.R.string.status_disconnected)
                }
            }
        }
    }

    private fun testActiveSource() {
        val source = activeSource ?: return showMissingSource()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = SmbClientManager.testConnection(source)
            Toast.makeText(requireContext(), result.getOrElse { it.message ?: getString(com.wkq.bao.feature.res.R.string.status_disconnected) }, Toast.LENGTH_LONG).show()
        }
    }

    private fun scanActiveSource() {
        val source = activeSource ?: return showMissingSource()
        viewLifecycleOwner.lifecycleScope.launch {
            binding.btnScan.isEnabled = false
            val result = NasScanner(database).scanAndImport(source)
            binding.btnScan.isEnabled = true
            Toast.makeText(requireContext(), result.fold({ "已同步 $it 个媒体文件" }, { it.message ?: "NAS 扫描失败" }), Toast.LENGTH_LONG).show()
        }
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
        val password = field(com.wkq.bao.feature.res.R.string.nas_password, source?.passwordEncrypted.orEmpty(), true)
        AlertDialog.Builder(requireContext())
            .setTitle(if (source == null) com.wkq.bao.feature.res.R.string.btn_add_nas else com.wkq.bao.feature.res.R.string.btn_edit_nas)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(com.wkq.bao.feature.res.R.string.btn_save) { _, _ ->
                val normalizedHost = host.text.toString().trim()
                val normalizedShare = share.text.toString().trim().trim('/')
                if (normalizedHost.isEmpty() || normalizedShare.isEmpty()) return@setPositiveButton
                viewLifecycleOwner.lifecycleScope.launch {
                    database.nasDao().insertSource(NasSourceEntity(
                        id = source?.id ?: 0L,
                        name = name.text.toString().trim().ifEmpty { normalizedHost },
                        host = normalizedHost,
                        shareName = normalizedShare,
                        rootPath = root.text.toString().trim(),
                        username = username.text.toString().trim(),
                        passwordEncrypted = password.text.toString(),
                        enabled = true,
                        createdAt = source?.createdAt ?: System.currentTimeMillis(),
                        lastScanAt = source?.lastScanAt ?: 0L
                    ))
                }
            }.show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
