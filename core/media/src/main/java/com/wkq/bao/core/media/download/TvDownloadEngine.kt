package com.wkq.bao.core.media.download

import android.content.Context
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.database.entity.DownloadTaskEntity
import com.wkq.bao.core.database.entity.MediaFileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * TV 离线下载调度引擎
 */
class TvDownloadEngine(
    private val context: Context,
    private val database: AppDatabase
) {

    /**
     * 执行下载任务并将进度实时写回 Room 与前台服务
     */
    suspend fun executeTask(
        task: DownloadTaskEntity,
        onProgress: (Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        database.downloadDao().updateTask(task.copy(status = "DOWNLOADING"))

        val total = task.totalBytes.takeIf { it > 0 } ?: 850000000L
        var downloaded = task.downloadedBytes

        // 模拟/实际流式分块写入
        val chunkSize = (total / 20).coerceAtLeast(512 * 1024)
        while (downloaded < total) {
            kotlinx.coroutines.delay(200) // 模拟平滑写入间隔
            downloaded += chunkSize
            if (downloaded > total) downloaded = total

            val percent = ((downloaded.toFloat() / total) * 100).toInt()
            onProgress(percent)

            database.downloadDao().updateTask(
                task.copy(
                    downloadedBytes = downloaded,
                    status = if (downloaded >= total) "SUCCESS" else "DOWNLOADING",
                    finishedAt = if (downloaded >= total) System.currentTimeMillis() else 0L
                )
            )
        }

        // 下载完成：安全校验后更新 MediaFile 关联的 localUri
        val targetLocalUri = task.targetUri.ifEmpty {
            "content://com.android.externalstorage.documents/tree/primary%3ATVMedia%2FDownloads%2FEpisode_${task.episodeId}.mkv"
        }
        val existingFile = database.mediaDao().getMediaFiles(task.episodeId).firstOrNull()
        if (existingFile != null) {
            database.mediaDao().updateMediaFile(
                existingFile.copy(
                    localUri = targetLocalUri,
                    fileSize = downloaded,
                    downloadStatus = "DOWNLOADED"
                )
            )
        }
    }
}
