package com.wkq.bao.feature.app

import android.content.Context
import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.lxj.xpopup.core.CenterPopupView
import com.wkq.bao.core.database.entity.NasSourceEntity
import com.wkq.bao.feature.app.databinding.PopupNasEditorBinding
import com.wkq.bao.feature.res.R as ResR

/** 可恢复的表单内容，不包含密码等敏感凭据。 */
internal data class NasEditorDraft(
    val sourceId: Long?,
    val type: String,
    val name: String,
    val host: String,
    val port: Int,
    val shareName: String,
    val rootPath: String,
    val username: String
)

/** 仅在当前弹框内存活的提交内容，密码不进入 ViewModel 草稿。 */
internal data class NasEditorSubmission(
    val draft: NasEditorDraft,
    val password: String
)

internal class NasEditorPopup(
    context: Context,
    private val source: NasSourceEntity?,
    private val initialDraft: NasEditorDraft?,
    private val onDraftChanged: (NasEditorDraft) -> Unit,
    private val onDiscardDraft: () -> Unit,
    private val onTest: (NasEditorSubmission) -> Boolean,
    private val onSave: (NasEditorSubmission) -> Boolean,
    private val onDismissed: (NasEditorPopup) -> Unit
) : CenterPopupView(context) {

    private lateinit var binding: PopupNasEditorBinding

    override val implLayoutId: Int
        get() = R.layout.popup_nas_editor

    override fun onCreate() {
        super.onCreate()
        binding = PopupNasEditorBinding.bind(checkNotNull(contentView))
        bindSource()
        bindActions()
    }

    private fun bindSource() = with(binding) {
        val draft = initialDraft?.takeIf { it.sourceId == source?.id }
        tvEditorTitle.setText(if (source == null) ResR.string.btn_add_nas else ResR.string.btn_edit_nas)
        etNasName.setText(draft?.name ?: source?.name.orEmpty())
        groupNasProtocol.check(if ((draft?.type ?: source?.type).equals("WEBDAV", true)) R.id.btn_protocol_webdav else R.id.btn_protocol_smb)
        etNasHost.setText(draft?.host ?: source?.host.orEmpty())
        etNasPort.setText((draft?.port ?: source?.port ?: SMB_PORT).toString())
        etNasShare.setText(draft?.shareName ?: source?.shareName.orEmpty())
        etNasRoot.setText(draft?.rootPath ?: source?.rootPath.orEmpty())
        etNasUsername.setText(draft?.username ?: source?.username.orEmpty())
        inputNasPassword.helperText = context.getString(
            if (source == null) ResR.string.nas_editor_new_password_hint else ResR.string.nas_editor_password_hint
        )
        updateProtocolUi()
    }

    private fun bindActions() = with(binding) {
        btnEditorCancel.setOnClickListener {
            onDiscardDraft()
            dismiss()
        }
        btnEditorTest.setOnClickListener {
            validatedSubmission()?.let { submission ->
                if (onTest(submission)) showConnectionTestInProgress()
            }
        }
        btnEditorSave.setOnClickListener { saveIfValid() }
        groupNasProtocol.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val nextPort = if (checkedId == R.id.btn_protocol_webdav) WEBDAV_PORT else SMB_PORT
                if (etNasPort.text.isNullOrBlank() || etNasPort.text.toString() in setOf(SMB_PORT.toString(), WEBDAV_PORT.toString())) {
                    etNasPort.setText(nextPort.toString())
                }
                inputNasHost.error = null
                inputNasPort.error = null
                inputNasShare.error = null
                updateProtocolUi()
                onDraftChanged(currentDraft())
            }
        }
        etNasPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveIfValid()
                true
            } else {
                false
            }
        }
        listOf(etNasName, etNasHost, etNasPort, etNasShare, etNasRoot, etNasUsername).forEach { field ->
            field.doAfterTextChanged { onDraftChanged(currentDraft()) }
        }
        etNasHost.doAfterTextChanged { inputNasHost.error = null }
        etNasShare.doAfterTextChanged { inputNasShare.error = null }
    }

    private fun saveIfValid() {
        val submission = validatedSubmission() ?: return
        if (onSave(submission)) dismiss()
    }

    private fun validatedSubmission(): NasEditorSubmission? = with(binding) {
        val draft = currentDraft()
        when {
            draft.host.isEmpty() -> {
                inputNasHost.error = context.getString(
                    ResR.string.nas_field_required,
                    context.getString(ResR.string.nas_host)
                )
                etNasHost.requestFocus()
                null
            }
            draft.port !in 1..65535 -> {
                inputNasPort.error = context.getString(ResR.string.nas_field_required, context.getString(ResR.string.nas_port))
                etNasPort.requestFocus()
                null
            }
            draft.type == "SMB" && draft.shareName.isEmpty() -> {
                inputNasShare.error = context.getString(
                    ResR.string.nas_field_required,
                    context.getString(ResR.string.nas_share)
                )
                etNasShare.requestFocus()
                null
            }
            else -> NasEditorSubmission(
                draft = draft,
                password = etNasPassword.text?.toString().orEmpty()
            )
        }
    }

    private fun currentDraft(): NasEditorDraft = with(binding) {
        NasEditorDraft(
            sourceId = source?.id,
            type = if (groupNasProtocol.checkedButtonId == R.id.btn_protocol_webdav) "WEBDAV" else "SMB",
            name = etNasName.text?.toString()?.trim().orEmpty(),
            host = etNasHost.text?.toString()?.trim().orEmpty(),
            port = etNasPort.text?.toString()?.toIntOrNull() ?: 0,
            shareName = etNasShare.text?.toString()?.trim()?.trim('/').orEmpty(),
            rootPath = etNasRoot.text?.toString()?.trim().orEmpty(),
            username = etNasUsername.text?.toString()?.trim().orEmpty()
        )
    }

    private fun updateProtocolUi() = with(binding) {
        val webDav = groupNasProtocol.checkedButtonId == R.id.btn_protocol_webdav
        tvEditorProtocol.setText(if (webDav) ResR.string.nas_editor_webdav_hint else ResR.string.nas_editor_protocol_hint)
        inputNasShare.hint = context.getString(if (webDav) ResR.string.nas_webdav_path else ResR.string.nas_share)
        inputNasHost.helperText = context.getString(
            if (webDav) ResR.string.nas_editor_webdav_host_hint else ResR.string.nas_editor_host_hint
        )
        inputNasShare.helperText = context.getString(
            if (webDav) ResR.string.nas_editor_webdav_path_hint else ResR.string.nas_editor_share_hint
        )
        inputNasShare.isHintEnabled = true
    }

    fun showConnectionTestInProgress() = with(binding) {
        tvEditorTestResult.isVisible = true
        tvEditorTestResult.setText(ResR.string.nas_editor_testing)
        tvEditorTestResult.setTextColor(context.getColor(ResR.color.tv_text_secondary))
    }

    fun showConnectionTestResult(result: Result<String>) = with(binding) {
        tvEditorTestResult.isVisible = true
        tvEditorTestResult.setText(
            if (result.isSuccess) ResR.string.nas_editor_test_succeeded else ResR.string.nas_editor_test_failed
        )
        tvEditorTestResult.setTextColor(
            context.getColor(if (result.isSuccess) ResR.color.nas_popup_action else ResR.color.nas_popup_error)
        )
    }

    override fun onDismiss() {
        onDismissed(this)
        super.onDismiss()
    }

    private companion object {
        const val SMB_PORT = 445
        const val WEBDAV_PORT = 5006
    }
}
