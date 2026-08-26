package com.wkq.bao.core.media.repository

import android.content.Context
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.database.entity.DownloadTaskEntity
import com.wkq.bao.core.database.entity.DownloadTaskStatus
import com.wkq.bao.core.database.entity.NasSourceEntity
import com.wkq.bao.core.media.download.DownloadWorkScheduler
import com.wkq.bao.core.media.storage.MediaStorageLocation
import com.wkq.bao.core.media.smb.SmbClientManager
import com.wkq.bao.core.media.smb.SmbCredentialRegistry
import com.wkq.bao.core.media.storage.TvStorageManager
import com.wkq.bao.core.media.webdav.WebDavClientManager
import com.wkq.bao.core.media.webdav.WebDavCredentialRegistry
import com.wkq.bao.core.nas.browser.NasFileEntry
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

data class EnqueueNasMediaResult(
    val videoCount: Int,
    val danmakuCount: Int
) {
    val totalCount: Int get() = videoCount + danmakuCount
}

/** NAS 媒体浏览只识别视频和与视频同名的弹幕、字幕文件。 */
internal object NasMediaFileClassifier {
    private val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "ts", "webm", "m4v")
    private val danmakuExtensions = setOf("xml", "ass", "srt", "vtt")

    fun isVideo(entry: NasFileEntry): Boolean = !entry.isDirectory && extension(entry.name) in videoExtensions

    fun isDanmaku(entry: NasFileEntry): Boolean = !entry.isDirectory && extension(entry.name) in danmakuExtensions

    fun belongsToVideo(danmaku: NasFileEntry, video: NasFileEntry): Boolean {
        if (!isDanmaku(danmaku) || !isVideo(video)) return false
        if (parentPath(danmaku.path) != parentPath(video.path)) return false
        val danmakuStem = stem(danmaku.name)
        val videoStem = stem(video.name)
        return danmakuStem == videoStem ||
            danmakuStem.startsWith("$videoStem.") ||
            danmakuStem.startsWith("$videoStem-") ||
            danmakuStem.startsWith("${videoStem}_")
    }

    private fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    private fun stem(name: String): String = name.substringBeforeLast('.').lowercase()

    fun parentPath(path: String): String = path.substringBeforeLast('/', "")
}

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

    /**
     * 仅展示视频文件以及其后代目录中含有视频的文件夹。
     * 目录判定结果按来源和路径缓存，避免用户在同一层级来回进入时重复遍历 NAS。
     */
    suspend fun listMediaDirectory(source: NasSourceEntity, path: String): Result<List<NasFileEntry>> = runCatching {
        val entries = listDirectory(source, path).getOrThrow()
        entries.filter { entry ->
            NasMediaFileClassifier.isVideo(entry) ||
                (entry.isDirectory && containsVideo(source, entry.path))
        }
    }

    suspend fun refreshMediaDirectory(source: NasSourceEntity, path: String): Result<List<NasFileEntry>> {
        directoryVideoCache.keys.removeAll { it.startsWith("${source.id}|") }
        return listMediaDirectory(source, path)
    }

    suspend fun enqueueSelected(
        source: NasSourceEntity,
        selected: Collection<NasFileEntry>,
        target: TvStorageManager.StorageTarget
    ): Result<Int> = runCatching {
        require(target.location != MediaStorageLocation.INTERNAL_STORAGE) {
            "NAS downloads require external storage"
        }
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

    /** 选择视频或文件夹时，同时下载同目录且同名的弹幕、字幕文件。 */
    suspend fun enqueueMediaSelected(
        source: NasSourceEntity,
        selected: Collection<NasFileEntry>,
        target: TvStorageManager.StorageTarget
    ): Result<EnqueueNasMediaResult> = runCatching {
        require(target.location != MediaStorageLocation.INTERNAL_STORAGE) {
            "NAS downloads require external storage"
        }
        require(target.isAvailable && TvStorageManager(context).isStorageTargetAvailable(target.uri)) {
            "下载目标不可用"
        }
        register(source)
        val selectedFiles = expandFiles(source, selected).distinctBy(NasFileEntry::path)
        val videos = selectedFiles.filter(NasMediaFileClassifier::isVideo)
        val danmaku = findAssociatedDanmaku(source, videos)
        val queuedPaths = enqueueFiles(source, (videos + danmaku).distinctBy(NasFileEntry::path), target)
        EnqueueNasMediaResult(
            videoCount = videos.count { it.path in queuedPaths },
            danmakuCount = danmaku.count { it.path in queuedPaths }
        )
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

    private suspend fun findAssociatedDanmaku(
        source: NasSourceEntity,
        videos: List<NasFileEntry>
    ): List<NasFileEntry> = videos
        .groupBy { NasMediaFileClassifier.parentPath(it.path) }
        .flatMap { (directory, videosInDirectory) ->
            listDirectory(source, directory).getOrThrow().filter { candidate ->
                videosInDirectory.any { video -> NasMediaFileClassifier.belongsToVideo(candidate, video) }
            }
        }

    private suspend fun enqueueFiles(
        source: NasSourceEntity,
        files: List<NasFileEntry>,
        target: TvStorageManager.StorageTarget
    ): Set<String> {
        val targetUri = target.uri.toString()
        val queuedPaths = mutableSetOf<String>()
        files.forEach { file ->
            val rawEpisodeId = rawDownloadKey(source.id, file.path)
            val existing = database.downloadDao().getTaskByEpisodeIdAndTargetUri(rawEpisodeId, targetUri)
            if (existing == null || existing.status in setOf(DownloadTaskStatus.FAILED, DownloadTaskStatus.CANCELLED)) {
                database.downloadDao().insertTask(
                    DownloadTaskEntity(
                        id = existing?.id ?: 0L,
                        seriesId = 0L,
                        seasonId = 0L,
                        episodeId = rawEpisodeId,
                        sourceUri = buildUri(source, file.path),
                        sourceNasId = source.id,
                        targetUri = targetUri,
                        targetStorageType = target.location.name,
                        totalBytes = file.size,
                        sourceLastModifiedAt = file.lastModifiedAt,
                        status = DownloadTaskStatus.WAITING
                    )
                )
                queuedPaths += file.path
            }
        }
        if (queuedPaths.isNotEmpty()) DownloadWorkScheduler.enqueue(context, expedited = true)
        return queuedPaths
    }

    private suspend fun containsVideo(source: NasSourceEntity, path: String): Boolean {
        val cacheKey = "${source.id}|${path.lowercase()}"
        directoryVideoCache[cacheKey]?.let { return it }
        val directories = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        directories += path
        while (directories.isNotEmpty()) {
            val directory = directories.removeFirst()
            if (!visited.add(directory.lowercase())) continue
            val entries = listDirectory(source, directory).getOrThrow()
            if (entries.any(NasMediaFileClassifier::isVideo)) {
                directoryVideoCache[cacheKey] = true
                return true
            }
            entries.filter(NasFileEntry::isDirectory).forEach { directories += it.path }
        }
        directoryVideoCache[cacheKey] = false
        return false
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
        private val directoryVideoCache = ConcurrentHashMap<String, Boolean>()

        fun create(context: Context): NasFileBrowserRepository {
            val appContext = context.applicationContext
            return NasFileBrowserRepository(appContext, AppDatabase.getInstance(appContext))
        }
    }
}
