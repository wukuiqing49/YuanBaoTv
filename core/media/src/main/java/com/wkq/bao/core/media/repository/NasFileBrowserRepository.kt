package com.wkq.bao.core.media.repository

import android.content.Context
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.database.entity.DownloadTaskEntity
import com.wkq.bao.core.database.entity.DownloadTaskStatus
import com.wkq.bao.core.database.entity.NasSourceEntity
import com.wkq.bao.core.media.download.DownloadWorkScheduler
import com.wkq.bao.core.media.smb.SmbClientManager
import com.wkq.bao.core.media.smb.SmbCredentialRegistry
import com.wkq.bao.core.media.storage.TvStorageManager
import com.wkq.bao.core.media.webdav.WebDavClientManager
import com.wkq.bao.core.media.webdav.WebDavCredentialRegistry
import com.wkq.bao.core.nas.browser.NasFileEntry
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.ArrayDeque

/** NAS 文件页面的数据入口：按层浏览、递归展开选中目录并复用现有持久化下载队列。 */
class NasFileBrowserRepository private constructor(
    private val context: Context,
    private val database: AppDatabase
) {
    suspend fun listDirectory(source: NasSourceEntity, path: String): Result<List<NasFileEntry>> {
        register(source)
        return if (WebDavClientManager.isWebDav(source)) {
            WebDavClientManager.listDirectoryEntries(source, path)
        } else {
            SmbClientManager.listDirectory(source, path)
        }
    }

    suspend fun enqueueSelected(
        source: NasSourceEntity,
        selected: Collection<NasFileEntry>,
        target: TvStorageManager.StorageTarget
    ): Result<Int> = runCatching {
        require(target.isAvailable && TvStorageManager(context).isStorageTargetAvailable(target.uri)) {
            "下载目标不可用"
        }
        register(source)
        val files = expandFiles(source, selected).distinctBy(NasFileEntry::path)
        var queued = 0
        files.forEach { file ->
            val sourceUri = buildUri(source, file.path)
            val rawEpisodeId = rawDownloadKey(source.id, file.path)
            val targetUri = target.uri.toString()
            val existing = database.downloadDao().getTaskByEpisodeIdAndTargetUri(rawEpisodeId, targetUri)
            if (existing == null || existing.status in setOf(DownloadTaskStatus.FAILED, DownloadTaskStatus.CANCELLED)) {
                database.downloadDao().insertTask(
                    DownloadTaskEntity(
                        id = existing?.id ?: 0L,
                        seriesId = 0L,
                        seasonId = 0L,
                        episodeId = rawEpisodeId,
                        sourceUri = sourceUri,
                        sourceNasId = source.id,
                        targetUri = targetUri,
                        targetStorageType = target.location.name,
                        totalBytes = file.size,
                        sourceLastModifiedAt = file.lastModifiedAt,
                        status = DownloadTaskStatus.WAITING
                    )
                )
                queued++
            }
        }
        if (queued > 0) DownloadWorkScheduler.enqueue(context, expedited = true)
        queued
    }

    private suspend fun expandFiles(
        source: NasSourceEntity,
        selected: Collection<NasFileEntry>
    ): List<NasFileEntry> {
        val files = mutableListOf<NasFileEntry>()
        val directories = ArrayDeque<NasFileEntry>()
        val visitedDirectories = mutableSetOf<String>()
        selected.forEach { if (it.isDirectory) directories += it else files += it }
        while (directories.isNotEmpty()) {
            val directory = directories.removeFirst()
            if (!visitedDirectories.add(directory.path.lowercase())) continue
            val children = listDirectory(source, directory.path).getOrThrow()
            children.forEach { if (it.isDirectory) directories += it else files += it }
        }
        return files
    }

    private fun register(source: NasSourceEntity) {
        if (WebDavClientManager.isWebDav(source)) WebDavCredentialRegistry.register(source)
        else SmbCredentialRegistry.register(source)
    }

    private fun buildUri(source: NasSourceEntity, path: String): String =
        if (WebDavClientManager.isWebDav(source)) WebDavClientManager.buildUri(source, path)
        else SmbClientManager.buildUri(source, path)

    /** 下载表已有 episodeId + targetUri 唯一索引，负数键用于区分不属于媒体索引的原始文件。 */
    private fun rawDownloadKey(sourceId: Long, path: String): Long {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$sourceId|${path.lowercase()}".toByteArray())
        val value = ByteBuffer.wrap(digest.copyOfRange(0, Long.SIZE_BYTES)).long and Long.MAX_VALUE
        return -(value.coerceAtLeast(1L))
    }

    companion object {
        fun create(context: Context): NasFileBrowserRepository {
            val appContext = context.applicationContext
            return NasFileBrowserRepository(appContext, AppDatabase.getInstance(appContext))
        }
    }
}
