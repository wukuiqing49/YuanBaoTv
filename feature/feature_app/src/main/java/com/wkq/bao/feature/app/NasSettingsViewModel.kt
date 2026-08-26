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
import com.wkq.bao.core.media.repository.NasFileBrowserRepository
import com.wkq.bao.core.media.storage.TvStorageManager
import com.wkq.bao.core.nas.browser.NasFileEntry
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

data class NasBrowserUiState(
    val sourceId: Long = 0L,
    val rootPath: String = "",
    val currentPath: String = "",
    val entries: List<NasFileEntry> = emptyList(),
    val selectedPaths: Set<String> = emptySet(),
    val loading: Boolean = false,
    val downloadInProgress: Boolean = false,
    val loadFailed: Boolean = false
)

sealed interface NasSettingsEvent {
    data class ConnectionTested(val result: Result<String>) : NasSettingsEvent
    data class SourceRemoved(val result: NasSourceRemovalResult) : NasSettingsEvent
    data class FilesQueued(val count: Int) : NasSettingsEvent
    data object FileActionFailed : NasSettingsEvent
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NasSettingsViewModel(
    private val repository: NasSettingsRepository,
    private val browserRepository: NasFileBrowserRepository
) : ViewModel() {
    private val selectedSourceId = MutableStateFlow<Long?>(null)
    private val editorDraft = MutableStateFlow<NasEditorDraft?>(null)
    private val scanSession = selectedSourceId.flatMapLatest { sourceId ->
        sourceId?.let(repository::observeScan) ?: flowOf(null)
    }
    val events = MutableSharedFlow<NasSettingsEvent>(extraBufferCapacity = 1)
    private val _browserState = MutableStateFlow(NasBrowserUiState())
    val browserState: StateFlow<NasBrowserUiState> = _browserState

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

    fun openRoot(source: NasSourceEntity, force: Boolean = false) {
        if (!force && _browserState.value.sourceId == source.id) return
        val rootPath = browserRootPath(source)
        loadDirectory(source, rootPath, rootPath)
    }

    fun openDirectory(source: NasSourceEntity, entry: NasFileEntry) {
        if (!entry.isDirectory || _browserState.value.loading) return
        loadDirectory(source, _browserState.value.rootPath, entry.path)
    }

    fun goUp(source: NasSourceEntity) {
        val state = _browserState.value
        if (state.loading || state.currentPath == state.rootPath) return
        val parent = state.currentPath.substringBeforeLast('/', state.rootPath)
            .takeIf { it.length >= state.rootPath.length }
            ?: state.rootPath
        loadDirectory(source, state.rootPath, parent)
    }

    fun refreshFiles(source: NasSourceEntity) {
        val state = _browserState.value
        val rootPath = if (state.sourceId == source.id) state.rootPath else browserRootPath(source)
        val currentPath = if (state.sourceId == source.id) state.currentPath else rootPath
        loadDirectory(source, rootPath, currentPath, refresh = true)
    }

    fun toggleSelection(entry: NasFileEntry) {
        _browserState.value = _browserState.value.let { state ->
            val selected = state.selectedPaths.toMutableSet()
            if (!selected.add(entry.path)) selected.remove(entry.path)
            state.copy(selectedPaths = selected)
        }
    }

    fun toggleSelectAll() {
        _browserState.value = _browserState.value.let { state ->
            val allPaths = state.entries.mapTo(mutableSetOf(), NasFileEntry::path)
            state.copy(selectedPaths = if (state.selectedPaths.containsAll(allPaths)) emptySet() else allPaths)
        }
    }

    fun enqueueSelected(source: NasSourceEntity, target: TvStorageManager.StorageTarget) {
        val state = _browserState.value
        val selected = state.entries.filter { it.path in state.selectedPaths }
        if (selected.isEmpty() || state.downloadInProgress) return
        _browserState.value = state.copy(downloadInProgress = true)
        viewModelScope.launch {
            browserRepository.enqueueMediaSelected(source, selected, target)
                .onSuccess { result ->
                    _browserState.value = _browserState.value.copy(
                        selectedPaths = emptySet(),
                        downloadInProgress = false
                    )
                    events.emit(NasSettingsEvent.FilesQueued(result.totalCount))
                }
                .onFailure {
                    _browserState.value = _browserState.value.copy(downloadInProgress = false)
                    events.emit(NasSettingsEvent.FileActionFailed)
                }
        }
    }

    private fun loadDirectory(
        source: NasSourceEntity,
        rootPath: String,
        path: String,
        refresh: Boolean = false
    ) {
        _browserState.value = NasBrowserUiState(
            sourceId = source.id,
            rootPath = rootPath,
            currentPath = path,
            loading = true
        )
        viewModelScope.launch {
            val request = if (refresh) {
                browserRepository.refreshMediaDirectory(source, path)
            } else {
                browserRepository.listMediaDirectory(source, path)
            }
            request
                .onSuccess { entries ->
                    _browserState.value = _browserState.value.copy(entries = entries, loading = false)
                }
                .onFailure {
                    _browserState.value = _browserState.value.copy(loading = false, loadFailed = true)
                }
        }
    }

    /** WebDAV 客户端的路径相对已配置根目录，SMB 路径相对共享目录。 */
    private fun browserRootPath(source: NasSourceEntity): String =
        if (source.type.equals("WEBDAV", ignoreCase = true)) "" else source.rootPath.trim('/')

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
        private val browserRepository = NasFileBrowserRepository.create(context)

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(NasSettingsViewModel::class.java))
            return NasSettingsViewModel(repository, browserRepository) as T
        }
    }
}
