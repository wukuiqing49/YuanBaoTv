package com.wkq.bao.feature.app

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wkq.bao.core.database.entity.DownloadTaskEntity
import com.wkq.bao.core.database.entity.DownloadTaskStatus
import com.wkq.bao.core.database.entity.MediaSeriesEntity
import com.wkq.bao.core.database.entity.ScanSessionEntity
import com.wkq.bao.core.database.entity.ScanSessionStatus
import com.wkq.bao.core.media.scanner.LocalMediaScanController
import com.wkq.bao.core.media.repository.DownloadsRepository
import com.wkq.bao.core.media.repository.RoomDownloadsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DownloadsUiState(
    val tasks: List<DownloadTaskEntity> = emptyList(),
    val downloadedSeries: List<MediaSeriesEntity> = emptyList(),
    val scanSession: ScanSessionEntity? = null
) {
    val keepScreenOn: Boolean = tasks.any { it.status == DownloadTaskStatus.DOWNLOADING }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DownloadsViewModel(
    private val repository: DownloadsRepository,
    private val scanController: LocalMediaScanController? = null
) : ViewModel() {
    private val selectedTreeUri = MutableStateFlow<Uri?>(null)
    private val scanSession = selectedTreeUri.flatMapLatest { uri ->
        if (uri != null && scanController != null) scanController.observe(uri) else flowOf(null)
    }

    val uiState: StateFlow<DownloadsUiState> = combine(
        repository.tasks,
        repository.downloadedSeries,
        scanSession
    ) { tasks, downloadedSeries, session ->
        DownloadsUiState(
            tasks = tasks.filter {
                it.status in setOf(
                    DownloadTaskStatus.DOWNLOADING,
                    DownloadTaskStatus.WAITING,
                    DownloadTaskStatus.PAUSED,
                    DownloadTaskStatus.FAILED
                ) || (it.status == DownloadTaskStatus.SUCCESS && it.seriesId == 0L)
            },
            downloadedSeries = downloadedSeries,
            scanSession = session
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), DownloadsUiState())

    fun togglePauseResume(taskId: Long) {
        viewModelScope.launch { repository.togglePauseResume(taskId) }
    }

    fun cancel(taskId: Long) {
        viewModelScope.launch { repository.cancel(taskId) }
    }

    fun observeStorage(treeUri: Uri?) {
        selectedTreeUri.value = treeUri
    }

    fun startLocalScan(treeUri: Uri) {
        selectedTreeUri.value = treeUri
        viewModelScope.launch { scanController?.enqueue(treeUri) }
    }

    fun handleLocalScanAction() {
        val uri = selectedTreeUri.value ?: return
        val session = uiState.value.scanSession
        viewModelScope.launch {
            when {
                ScanSessionStatus.isActive(session?.status.orEmpty()) -> scanController?.cancel(uri)
                session?.status in setOf(ScanSessionStatus.FAILED, ScanSessionStatus.CANCELLED) ->
                    scanController?.enqueue(uri, retry = true)
                else -> scanController?.enqueue(uri)
            }
        }
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val repository = RoomDownloadsRepository.create(context)
        private val scanController = LocalMediaScanController(context)

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DownloadsViewModel::class.java))
            return DownloadsViewModel(repository, scanController) as T
        }
    }
}
