package com.wkq.bao.feature.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wkq.bao.core.database.entity.NasSourceEntity
import com.wkq.bao.core.database.entity.ScanSessionEntity
import com.wkq.bao.core.database.entity.ScanSessionStatus
import com.wkq.bao.core.media.download.NasSourceRemovalResult
import com.wkq.bao.core.media.repository.NasSettingsRepository
import com.wkq.bao.core.media.repository.RoomNasSettingsRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class NasSettingsUiState(
    val sources: List<NasSourceEntity> = emptyList(),
    val activeSource: NasSourceEntity? = null,
    val scanSession: ScanSessionEntity? = null
)

sealed interface NasSettingsEvent {
    data class ConnectionTested(val result: Result<String>) : NasSettingsEvent
    data class SourceRemoved(val result: NasSourceRemovalResult) : NasSettingsEvent
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NasSettingsViewModel(private val repository: NasSettingsRepository) : ViewModel() {
    private val selectedSourceId = MutableStateFlow<Long?>(null)
    private val editorDraft = MutableStateFlow<NasEditorDraft?>(null)
    private val scanSession = selectedSourceId.flatMapLatest { sourceId ->
        sourceId?.let(repository::observeScan) ?: flowOf(null)
    }
    val events = MutableSharedFlow<NasSettingsEvent>(extraBufferCapacity = 1)

    val uiState: StateFlow<NasSettingsUiState> = combine(
        repository.sources,
        selectedSourceId,
        scanSession
    ) { sources, selectedId, session ->
        val active = sources.firstOrNull { it.id == selectedId }
            ?: sources.firstOrNull { it.enabled }
            ?: sources.firstOrNull()
        if (active?.id != selectedId) selectedSourceId.value = active?.id
        NasSettingsUiState(sources, active, session.takeIf { it?.sourceKey == active?.id?.toString() })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), NasSettingsUiState())

    fun selectSource(sourceId: Long) {
        selectedSourceId.value = sourceId
    }

    internal fun editorDraftFor(sourceId: Long?): NasEditorDraft? =
        editorDraft.value?.takeIf { it.sourceId == sourceId }

    internal fun updateEditorDraft(draft: NasEditorDraft) {
        editorDraft.value = draft
    }

    internal fun clearEditorDraft(sourceId: Long?) {
        if (editorDraft.value?.sourceId == sourceId) editorDraft.value = null
    }

    fun save(source: NasSourceEntity) = viewModelScope.launch { repository.save(source) }

    fun toggleEnabled(source: NasSourceEntity) = viewModelScope.launch {
        repository.setEnabled(source, !source.enabled)
    }

    fun testConnection(source: NasSourceEntity) = viewModelScope.launch {
        events.emit(NasSettingsEvent.ConnectionTested(repository.testConnection(source)))
    }

    fun remove(sourceId: Long) = viewModelScope.launch {
        events.emit(NasSettingsEvent.SourceRemoved(repository.remove(sourceId)))
    }

    fun handleScanAction() {
        val state = uiState.value
        val sourceId = state.activeSource?.id ?: return
        viewModelScope.launch {
            when {
                ScanSessionStatus.isActive(state.scanSession?.status.orEmpty()) -> repository.cancelScan(sourceId)
                state.scanSession?.status in setOf(ScanSessionStatus.FAILED, ScanSessionStatus.CANCELLED) ->
                    repository.enqueueScan(sourceId, retry = true)
                else -> repository.enqueueScan(sourceId)
            }
        }
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val repository = RoomNasSettingsRepository.create(context)

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(NasSettingsViewModel::class.java))
            return NasSettingsViewModel(repository) as T
        }
    }
}
