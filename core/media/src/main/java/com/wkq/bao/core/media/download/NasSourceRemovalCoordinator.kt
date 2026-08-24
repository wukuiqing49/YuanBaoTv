package com.wkq.bao.core.media.download

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.database.entity.DownloadTaskEntity
import com.wkq.bao.core.database.entity.DownloadTaskStatus
import com.wkq.bao.core.database.entity.NasSourceEntity
import com.wkq.bao.core.media.storage.TvStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface NasSourceRemovalResult {
    data object Removed : NasSourceRemovalResult
    data object NotFound : NasSourceRemovalResult
    data object StorageUnavailable : NasSourceRemovalResult
    data object DownloadStillStopping : NasSourceRemovalResult
    data object CleanupFailed : NasSourceRemovalResult
}

/**
 * 先停止指定 NAS 的断点下载并清理临时文件，再原子删除来源关联数据。
 * 已完成的媒体位置不属于下载临时文件，因此会保留并继续可播放。
 */
class NasSourceRemovalCoordinator(private val context: Context) {
    private val appContext = context.applicationContext
    private val database = AppDatabase.getInstance(appContext)
    private val storageManager = TvStorageManager(appContext)

    suspend fun remove(sourceNasId: Long): NasSourceRemovalResult = withContext(Dispatchers.IO) {
        val nasDao = database.nasDao()
        val source = nasDao.getSourceById(sourceNasId) ?: return@withContext NasSourceRemovalResult.NotFound
        var removed = false

        try {
            // 先阻止新下载启动，再让正在执行的引擎观察到取消状态。
            if (source.enabled) nasDao.updateSource(source.copy(enabled = false))
            database.downloadDao().cancelActiveTasksBySourceNasId(
                sourceNasId = sourceNasId,
                activeStatuses = listOf(
                    DownloadTaskStatus.WAITING,
                    DownloadTaskStatus.DOWNLOADING,
                    DownloadTaskStatus.PAUSED,
                    DownloadTaskStatus.FAILED
                )
            )

            val result = withTimeoutOrNull(STOP_TIMEOUT_MS) {
                DownloadSourceLock.withLock(sourceNasId) {
                    removeLocked(sourceNasId, source)
                }
            } ?: NasSourceRemovalResult.DownloadStillStopping
            removed = result == NasSourceRemovalResult.Removed
            return@withContext result
        } finally {
            if (!removed && source.enabled) {
                // 页面销毁或异常取消时恢复来源开关，避免遗留不可见状态变更。
                withContext(NonCancellable) {
                    nasDao.updateSource(source)
                }
            }
        }
    }

    private suspend fun removeLocked(sourceNasId: Long, source: NasSourceEntity): NasSourceRemovalResult {
        val tasks = database.downloadDao().getTasksBySourceNasId(sourceNasId)
        when (cleanupTemporaryArtifacts(tasks)) {
            ArtifactCleanupResult.CLEANED -> Unit
            ArtifactCleanupResult.STORAGE_UNAVAILABLE -> return NasSourceRemovalResult.StorageUnavailable
            ArtifactCleanupResult.FAILED -> return NasSourceRemovalResult.CleanupFailed
        }

        database.withTransaction {
            database.mediaDao().deleteMediaRemoteSourcesByNasSourceId(sourceNasId)
            database.downloadDao().deleteTasksBySourceNasId(sourceNasId)
            database.nasDao().deleteSource(source)
        }
        DownloadWorkScheduler.enqueue(appContext)
        return NasSourceRemovalResult.Removed
    }

    private fun cleanupTemporaryArtifacts(tasks: List<DownloadTaskEntity>): ArtifactCleanupResult {
        tasks.filter { it.status != DownloadTaskStatus.SUCCESS }.forEach { task ->
            val targetUri = runCatching { Uri.parse(task.targetUri) }.getOrNull()
                ?: return ArtifactCleanupResult.STORAGE_UNAVAILABLE
            if (!storageManager.isStorageTargetAvailable(targetUri)) {
                return ArtifactCleanupResult.STORAGE_UNAVAILABLE
            }
            val root = DocumentFile.fromTreeUri(appContext, targetUri)
                ?: return ArtifactCleanupResult.STORAGE_UNAVAILABLE
            val downloadDirectory = root.findFile(DOWNLOAD_DIRECTORY) ?: return@forEach
            val partsDirectory = downloadDirectory.findFile(PARTS_DIRECTORY)
            val files = buildList {
                add(downloadDirectory.findFile("download_${task.id}.assembling"))
                add(downloadDirectory.findFile("download_${task.id}.part"))
                partsDirectory?.listFiles()
                    ?.filter { it.name?.startsWith("chunk_${task.id}_") == true && it.name?.endsWith(".part") == true }
                    ?.let(::addAll)
            }
            if (files.filterNotNull().any { !it.delete() }) return ArtifactCleanupResult.FAILED
        }
        return ArtifactCleanupResult.CLEANED
    }

    private enum class ArtifactCleanupResult { CLEANED, STORAGE_UNAVAILABLE, FAILED }

    private companion object {
        const val DOWNLOAD_DIRECTORY = "YuanBaoTV"
        const val PARTS_DIRECTORY = ".yuanbao_parts"
        const val STOP_TIMEOUT_MS = 10_000L
    }
}
