package com.wkq.bao.core.media.download

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.database.entity.DownloadTaskEntity
import com.wkq.bao.core.database.entity.DownloadTaskStatus
import com.wkq.bao.core.media.smb.SmbClientManager
import com.wkq.bao.core.media.storage.MediaStorageLocation
import com.wkq.bao.core.media.storage.TvStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** 将 NAS 文件下载到用户选定的 TF 卡或 USB 硬盘，并只在校验完成后暴露本地 URI。 */
class TvDownloadEngine(
    private val context: Context,
    private val database: AppDatabase
) {
    suspend fun executeTask(task: DownloadTaskEntity, onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        val latestTask = database.downloadDao().getTaskById(task.id) ?: return@withContext
        if (latestTask.status != DownloadTaskStatus.WAITING) return@withContext

        val mediaFile = database.mediaDao().getMediaFileByEpisodeId(latestTask.episodeId)
            ?: return@withContext fail(latestTask, "未找到待下载的媒体文件")
        val nasSource = mediaFile.nasSourceId?.let { database.nasDao().getSourceById(it) }
            ?: return@withContext fail(latestTask, "NAS 来源不可用")
        val sourceUri = Uri.parse(mediaFile.nasUri.ifBlank { latestTask.sourceUri })
        if (sourceUri.scheme != "smb") return@withContext fail(latestTask, "当前仅支持 SMB 离线下载")

        val storageManager = TvStorageManager(context)
        val storageTarget = latestTask.targetUri.takeIf { it.isNotBlank() }?.let { treeUri ->
            val targetUri = Uri.parse(treeUri)
            val location = MediaStorageLocation.fromStored(latestTask.targetStorageType)
                ?: storageManager.resolveLocalLocation(targetUri)
            TvStorageManager.StorageTarget(
                uri = targetUri,
                location = location,
                isAvailable = storageManager.isStorageTargetAvailable(targetUri)
            )
        } ?: storageManager.getStorageTarget()
            ?: return@withContext fail(latestTask, "未选择 TF 内存卡或 USB 硬盘")

        if (!storageTarget.isAvailable) return@withContext fail(latestTask, "下载目标未连接或不是可移除存储")

        val root = DocumentFile.fromTreeUri(context, storageTarget.uri)
            ?: return@withContext fail(latestTask, "下载目标不可用")
        val downloadDirectory = root.findFile(DOWNLOAD_DIRECTORY) ?: root.createDirectory(DOWNLOAD_DIRECTORY)
            ?: return@withContext fail(latestTask, "无法创建下载目录")
        val fileName = mediaFile.fileName.ifBlank { "episode_${latestTask.episodeId}.mp4" }
        val tempName = "$fileName.download"
        val tempFile = downloadDirectory.findFile(tempName)
            ?: downloadDirectory.createFile("application/octet-stream", tempName)
            ?: return@withContext fail(latestTask, "无法创建临时下载文件")

        try {
            database.downloadDao().updateTask(latestTask.copy(status = DownloadTaskStatus.DOWNLOADING, errorMessage = ""))
            context.contentResolver.openOutputStream(tempFile.uri, "wt")?.use { output ->
                SmbClientManager.copyTo(nasSource, sourceUri, output) { downloaded, total ->
                    val current = database.downloadDao().getTaskById(latestTask.id) ?: return@copyTo
                    if (current.status != DownloadTaskStatus.DOWNLOADING) throw DownloadInterrupted(current.status)
                    val percent = if (total > 0) ((downloaded * 100L) / total).toInt() else 0
                    database.downloadDao().updateTask(current.copy(downloadedBytes = downloaded, totalBytes = total))
                    onProgress(percent.coerceIn(0, 100))
                }
            } ?: throw IOException("无法打开下载目标输出流")

            val finishedSize = tempFile.length()
            val expectedSize = database.downloadDao().getTaskById(latestTask.id)?.totalBytes ?: 0L
            if (expectedSize <= 0L || finishedSize != expectedSize) throw IOException("下载文件大小校验失败")

            downloadDirectory.findFile(fileName)?.delete()
            if (!tempFile.renameTo(fileName)) throw IOException("无法完成下载文件重命名")
            val completedUri = downloadDirectory.findFile(fileName)?.uri?.toString()
                ?: throw IOException("无法定位下载完成文件")
            database.mediaDao().updateMediaFile(mediaFile.copy(
                localUri = completedUri,
                localStorageType = storageTarget.location.name,
                fileSize = finishedSize,
                downloadStatus = DownloadTaskStatus.SUCCESS,
                updatedAt = System.currentTimeMillis()
            ))
            val completedTask = database.downloadDao().getTaskById(latestTask.id) ?: latestTask
            database.downloadDao().updateTask(completedTask.copy(
                downloadedBytes = finishedSize,
                totalBytes = expectedSize,
                status = DownloadTaskStatus.SUCCESS,
                finishedAt = System.currentTimeMillis()
            ))
            onProgress(100)
        } catch (interrupted: DownloadInterrupted) {
            val current = database.downloadDao().getTaskById(latestTask.id) ?: latestTask
            database.downloadDao().updateTask(current.copy(status = interrupted.status))
        } catch (error: Exception) {
            fail(database.downloadDao().getTaskById(latestTask.id) ?: latestTask, error.message ?: "下载失败")
        }
    }

    private suspend fun fail(task: DownloadTaskEntity, message: String) {
        database.downloadDao().updateTask(task.copy(status = DownloadTaskStatus.FAILED, errorMessage = message))
    }

    private class DownloadInterrupted(val status: String) : IOException()

    private companion object {
        const val DOWNLOAD_DIRECTORY = "YuanBaoTV"
    }
}
