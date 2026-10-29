package com.wkq.bao.core.media.download

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.system.ErrnoException
import android.system.OsConstants
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.database.entity.DownloadChunkEntity
import com.wkq.bao.core.database.entity.DownloadChunkStatus
import com.wkq.bao.core.database.entity.DownloadTaskEntity
import com.wkq.bao.core.database.entity.DownloadTaskErrorCode
import com.wkq.bao.core.database.entity.DownloadTaskStatus
import com.wkq.bao.core.database.entity.MediaLocationEntity
import com.wkq.bao.core.media.smb.SmbClientManager
import com.wkq.bao.core.media.webdav.WebDavClientManager
import com.wkq.bao.core.media.storage.MediaStorageLocation
import com.wkq.bao.core.media.storage.TvStorageManager
import com.wkq.bao.core.media.storage.MediaLibraryStorage
import com.wkq.bao.core.media.parser.MediaFileNameParser
import com.wkq.bao.core.database.entity.MediaSeriesType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest

/** SMB 持久化分块下载器：每个分块都需要通过独立文件和 SHA-256 校验。 */
class TvDownloadEngine(
    private val context: Context,
    private val database: AppDatabase
) {
    private data class RemoteInfo(val length: Long, val lastModifiedAt: Long)

    suspend fun executeTask(task: DownloadTaskEntity, onProgress: (Int) -> Unit) =
        DownloadSourceLock.withLock(task.sourceNasId) {
            executeTaskLocked(task, onProgress)
        }

    private suspend fun executeTaskLocked(task: DownloadTaskEntity, onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        val downloadDao = database.downloadDao()
        val latestTask = downloadDao.getTaskById(task.id) ?: return@withContext
        if (downloadDao.claimWaitingTask(latestTask.id) == 0) return@withContext
        val claimedTask = latestTask.copy(status = DownloadTaskStatus.DOWNLOADING, errorMessage = "")

        try {
            val isRawFileTask = claimedTask.episodeId < 0L
            val mediaFile = if (isRawFileTask) {
                null
            } else {
                database.mediaDao().getMediaFileByEpisodeId(claimedTask.episodeId)
                    ?: return@withContext fail(claimedTask, "Media file is missing", DownloadTaskErrorCode.DATA_MISSING)
            }
            val selectedSource = if (isRawFileTask) {
                DownloadSourceSelector.Source(claimedTask.sourceUri, claimedTask.sourceNasId)
                    .takeIf { it.uri.isNotBlank() && it.nasSourceId > 0L }
            } else {
                DownloadSourceSelector.select(
                    claimedTask,
                    checkNotNull(mediaFile),
                    database.mediaDao().getMediaRemoteSources(mediaFile.id)
                )
            } ?: return@withContext fail(claimedTask, "NAS source is unavailable", DownloadTaskErrorCode.SOURCE_UNAVAILABLE)
            val sourceUri = Uri.parse(selectedSource.uri)
            val sourceNasId = selectedSource.nasSourceId
            val nasSource = database.nasDao().getSourceById(sourceNasId)
                ?.takeIf { it.enabled }
                ?: return@withContext fail(claimedTask, "NAS source is unavailable", DownloadTaskErrorCode.SOURCE_UNAVAILABLE)
            if (sourceUri.scheme !in setOf("smb", "http", "https")) return@withContext fail(claimedTask, "Unsupported NAS download source", DownloadTaskErrorCode.UNSUPPORTED_SOURCE)
            if (sourceUri.scheme in setOf("http", "https") && (!WebDavClientManager.isWebDav(nasSource) || !com.wkq.bao.core.media.webdav.WebDavEndpoint.matches(nasSource, sourceUri.toString()))) return@withContext fail(claimedTask, "WebDAV source configuration is unavailable", DownloadTaskErrorCode.SOURCE_UNAVAILABLE)

            val storageTarget = resolveStorageTarget(claimedTask)
                ?: return@withContext fail(claimedTask, "Download storage is unavailable", DownloadTaskErrorCode.STORAGE_UNAVAILABLE)
            val libraries = MediaLibraryStorage(context)
            if (claimedTask.targetLibraryId.isNotBlank() && !libraries.matches(storageTarget.uri, claimedTask.targetLibraryId)) {
                return@withContext fail(claimedTask, "Library identity does not match", DownloadTaskErrorCode.LIBRARY_MISMATCH)
            }
            val root = TvStorageManager.getDocumentFile(context, storageTarget.uri)
                ?: return@withContext fail(claimedTask, "Download storage is unavailable", DownloadTaskErrorCode.STORAGE_UNAVAILABLE)
            if (!TvStorageManager(context).isStorageTargetWritable(storageTarget.uri)) {
                return@withContext fail(claimedTask, "Download storage is not writable", DownloadTaskErrorCode.STORAGE_ACCESS)
            }
            val managed = claimedTask.targetLibraryId.isNotBlank()
            suspend fun checkDestination() {
                ensureTaskIsDownloading(claimedTask.id)
                if (!TvStorageManager(context).isStorageTargetAvailable(storageTarget.uri)) {
                    throw DownloadFailure(DownloadTaskErrorCode.STORAGE_UNAVAILABLE, "Storage was disconnected")
                }
                if (managed && !libraries.matches(storageTarget.uri, claimedTask.targetLibraryId)) {
                    throw DownloadFailure(DownloadTaskErrorCode.LIBRARY_MISMATCH, "Library identity changed")
                }
            }
            val downloadRoot = if (managed) libraries.read(storageTarget.uri)?.root
                else root.findFile(DOWNLOAD_DIRECTORY) ?: root.createDirectory(DOWNLOAD_DIRECTORY)
                ?: return@withContext fail(claimedTask, "Cannot create download directory", DownloadTaskErrorCode.STORAGE_ACCESS)
            checkNotNull(downloadRoot)
            val indexedSeries = mediaFile?.let { database.mediaDao().getSeriesById(it.seriesId) }
            val indexedEpisode = mediaFile?.let { database.mediaDao().getEpisodeById(it.episodeId) }
            val seasonNumber = indexedSeries?.let { series -> database.mediaDao().getSeasonsSync(series.id)
                .firstOrNull { it.id == indexedEpisode?.seasonId }?.seasonNumber } ?: 1
            // 已有旧目录继续原位完成，避免升级中断正在合并的下载。
            val legacyDirectory = if (managed && !isRawFileTask) listOf("Movies", "TVShows").firstNotNullOfOrNull { category ->
                downloadRoot.findFile(category)?.findFile("NAS_${nasSource.id}")
                    ?.findFile("media_${claimedTask.seriesId}_${claimedTask.episodeId}")?.takeIf { it.isDirectory }
            } else null
            val downloadDirectory = if (legacyDirectory != null) legacyDirectory else if (managed && indexedSeries != null) {
                val categoryRoot = MediaLibraryStorage.directory(downloadRoot, MediaDownloadPath.category(indexedSeries))
                val suffix = " [${indexedSeries.id}]"
                val seriesRoot = categoryRoot.listFiles().firstOrNull { it.isDirectory && it.name?.endsWith(suffix) == true }
                    ?: MediaLibraryStorage.directory(categoryRoot, MediaDownloadPath.folder(indexedSeries))
                if (indexedSeries.type == MediaSeriesType.MOVIE) seriesRoot
                else MediaLibraryStorage.directory(seriesRoot, MediaDownloadPath.season(seasonNumber))
            } else if (managed) {
                val parsed = MediaFileNameParser.parse(sourceUri.lastPathSegment.orEmpty(), sourceUri.path.orEmpty().substringBeforeLast('/', ""))
                val categoryRoot = MediaLibraryStorage.directory(downloadRoot, if (parsed.mediaType == MediaSeriesType.MOVIE) "Movies" else "TVShows")
                val sourceRoot = MediaLibraryStorage.directory(categoryRoot, "NAS_${nasSource.id}")
                ensureRelativeDirectory(sourceRoot, nasSource, sourceUri) ?: throw IOException("Cannot create media directory")
            } else if (isRawFileTask) {
                ensureRelativeDirectory(downloadRoot, nasSource, sourceUri)
                    ?: return@withContext fail(claimedTask, "Cannot create source directory", DownloadTaskErrorCode.STORAGE_ACCESS)
            } else downloadRoot
            val temporaryRoot = if (managed) MediaLibraryStorage.directory(downloadRoot, "Downloads") else downloadRoot
            val partsDirectory = temporaryRoot.findFile(PARTS_DIRECTORY)
                ?: temporaryRoot.createDirectory(PARTS_DIRECTORY)
                ?: return@withContext fail(claimedTask, "Cannot create download part directory", DownloadTaskErrorCode.STORAGE_ACCESS)

            val remoteInfo = getRemoteFileInfo(nasSource, sourceUri)
            if (remoteInfo.length <= 0L) throw DownloadFailure(DownloadTaskErrorCode.SOURCE_CHANGED, "NAS source file is empty")
            val assemblingName = "download_${claimedTask.id}.assembling"
            val displayFileName = mediaFile?.fileName?.ifBlank { "episode_${claimedTask.episodeId}.mp4" }
                ?: sourceUri.lastPathSegment.orEmpty().ifBlank { "nas_file_${claimedTask.id}" }
            val finalName = if (managed && indexedSeries != null && indexedEpisode != null && legacyDirectory == null)
                MediaDownloadPath.fileName(indexedSeries, seasonNumber, indexedEpisode.episodeNumber, displayFileName)
            else if (mediaFile == null || managed) safeFileName(displayFileName)
            else "media_${mediaFile.id}_${claimedTask.id}_${safeFileName(displayFileName)}"
            val existingFinalFile = downloadDirectory.findFile(finalName)
            if (existingFinalFile != null) {
                if (existingFinalFile.length() != remoteInfo.length) {
                    throw DownloadFailure(DownloadTaskErrorCode.TARGET_EXISTS, "Completed file name is occupied by a different file")
                }
                if (managed) NasCompanionDownloader(context).copy(nasSource, sourceUri, downloadDirectory, finalName) { checkDestination() }
                checkDestination()
                if (mediaFile == null) completeRawDownload(claimedTask.id, existingFinalFile, remoteInfo)
                else completeDownload(
                    claimedTask.id, mediaFile, displayFileName, existingFinalFile,
                    storageTarget.location.name, remoteInfo
                )
                deletePartFiles(partsDirectory, downloadDao.getChunks(claimedTask.id))
                downloadDao.deleteChunks(claimedTask.id)
                onProgress(100)
                return@withContext
            }

            var chunks = downloadDao.getChunks(claimedTask.id)
            val sourceChanged = claimedTask.totalBytes > 0L && (
                claimedTask.totalBytes != remoteInfo.length ||
                    claimedTask.sourceLastModifiedAt == 0L ||
                    claimedTask.sourceLastModifiedAt != remoteInfo.lastModifiedAt
                )
            if (sourceChanged) {
                deletePartFiles(partsDirectory, chunks)
                downloadDirectory.deleteAssemblingFile(assemblingName)
                downloadDao.deleteChunks(claimedTask.id)
                if (downloadDao.updateAssembledBytesWhileDownloading(claimedTask.id, 0L) == 0) {
                    ensureTaskIsDownloading(claimedTask.id)
                }
                chunks = emptyList()
            }
            if (chunks.isEmpty()) {
                // 旧版单临时文件没有分块完整性证明，升级后不再复用。
                downloadDirectory.findFile("download_${claimedTask.id}.part")?.delete()
                downloadDao.insertChunks(DownloadChunkPlanner.create(claimedTask.id, remoteInfo.length))
                // Room 自动生成主键后必须重新读取，否则 id=0 会使状态更新落空及误匹配其他分块。
                chunks = downloadDao.getChunks(claimedTask.id)
            }

            chunks = verifyCompletedChunks(partsDirectory, chunks)
            chunks = restoreAssembling(claimedTask, downloadDirectory, partsDirectory, chunks, assemblingName)
            var completedBytes = chunks
                .filter { it.status != DownloadChunkStatus.WAITING }
                .sumOf { it.byteCount }
            val assembledBytes = (downloadDao.getTaskById(claimedTask.id) ?: claimedTask).assembledBytes
            val requiredFreeBytes = DownloadStorageRequirement.requiredFreeBytes(remoteInfo.length, assembledBytes)
            if (!TvStorageManager(context).hasAvailableBytes(storageTarget, requiredFreeBytes)) {
                throw DownloadFailure(DownloadTaskErrorCode.STORAGE_CAPACITY, "Download storage has insufficient free space")
            }
            updateTaskProgress(claimedTask.id, completedBytes, remoteInfo, onProgress)

            val throttle = ProgressThrottle()
            var readHandle: SmbClientManager.RemoteFileHandle? = null
            try {
                for (chunk in chunks) {
                    if (chunk.status != DownloadChunkStatus.WAITING) continue
                    checkDestination()
                    val currentAssembledBytes = downloadDao.getTaskById(claimedTask.id)?.assembledBytes ?: 0L
                    if (!TvStorageManager(context).isStorageTargetAvailable(storageTarget.uri)) {
                        throw DownloadFailure(DownloadTaskErrorCode.STORAGE_UNAVAILABLE, "Download storage was disconnected")
                    }
                    if (!TvStorageManager(context).hasAvailableBytes(storageTarget,
                            DownloadStorageRequirement.requiredFreeBytes(remoteInfo.length, currentAssembledBytes))) {
                        throw DownloadFailure(DownloadTaskErrorCode.STORAGE_CAPACITY, "Download storage has insufficient free space")
                    }
                    val downloadedPart = downloadChunkWithRetry(
                        source = nasSource,
                        sourceUri = sourceUri,
                        remoteInfo = remoteInfo,
                        partsDirectory = partsDirectory,
                        chunk = chunk,
                        currentCompletedBytes = completedBytes,
                        throttle = throttle,
                        onProgress = onProgress,
                        readHandle = { readHandle },
                        replaceReadHandle = { replacement ->
                            readHandle?.close()
                            readHandle = replacement
                        }
                    )
                    val part = downloadedPart.part
                    val checksum = downloadedPart.checksum

                    if (part.length() != chunk.byteCount) throw IOException("Download part length validation failed")
                    ensureTaskIsDownloading(claimedTask.id)
                    downloadDao.updateChunk(chunk.copy(
                        sha256 = checksum,
                        status = DownloadChunkStatus.COMPLETED,
                        updatedAt = System.currentTimeMillis()
                    ))
                    completedBytes += chunk.byteCount
                    updateTaskProgress(claimedTask.id, completedBytes, remoteInfo, onProgress)
                    chunks = appendContiguousCompletedChunks(
                        claimedTask.id,
                        downloadDirectory,
                        partsDirectory,
                        chunks.map { if (it.id == chunk.id) it.copy(status = DownloadChunkStatus.COMPLETED, sha256 = checksum) else it },
                        assemblingName
                    )
                }
            } finally {
                readHandle?.close()
            }

            val finalChunks = downloadDao.getChunks(claimedTask.id)
            if (finalChunks.any { it.status != DownloadChunkStatus.ASSEMBLED }) {
                throw DownloadFailure(DownloadTaskErrorCode.STORAGE_ACCESS, "Download parts are incomplete")
            }
            val finalRemoteInfo = getRemoteFileInfo(nasSource, sourceUri)
            if (finalRemoteInfo != remoteInfo) {
                throw DownloadFailure(DownloadTaskErrorCode.SOURCE_CHANGED, "NAS source changed during download")
            }

            val assemblingFile = downloadDirectory.findAssemblingFile(assemblingName)
                ?: throw DownloadFailure(DownloadTaskErrorCode.STORAGE_ACCESS, "Cannot locate assembled download")
            if (assemblingFile.length() != remoteInfo.length) throw DownloadFailure(DownloadTaskErrorCode.STORAGE_ACCESS, "Assembled file length validation failed")
            if (managed && !libraries.matches(storageTarget.uri, claimedTask.targetLibraryId)) {
                throw DownloadFailure(DownloadTaskErrorCode.LIBRARY_MISMATCH, "Library identity changed")
            }
            if (!assemblingFile.renameTo(finalName)) throw DownloadFailure(DownloadTaskErrorCode.STORAGE_ACCESS, "Cannot rename completed download")
            val completedFile = downloadDirectory.findFile(finalName)
                ?: throw DownloadFailure(DownloadTaskErrorCode.STORAGE_ACCESS, "Cannot locate completed download")
            if (managed) NasCompanionDownloader(context).copy(nasSource, sourceUri, downloadDirectory, finalName) { checkDestination() }
            checkDestination()
            if (mediaFile == null) completeRawDownload(claimedTask.id, completedFile, remoteInfo)
            else completeDownload(
                claimedTask.id, mediaFile, displayFileName, completedFile,
                storageTarget.location.name, remoteInfo
            )
            deletePartFiles(partsDirectory, finalChunks)
            downloadDao.deleteChunks(claimedTask.id)
            onProgress(100)
        } catch (interrupted: DownloadInterrupted) {
            // 用户操作已持久化，不能用较早读到的状态覆盖后续恢复或取消。
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.e("TvDownloadEngine", "executeTask error for task " + claimedTask.id, error)
            val errorCode = when {
                error is SecurityException -> DownloadTaskErrorCode.STORAGE_ACCESS
                generateSequence<Throwable>(error) { it.cause }.take(16)
                    .any { it is ErrnoException && it.errno == OsConstants.ENOSPC } -> DownloadTaskErrorCode.STORAGE_CAPACITY
                resolveStorageTarget(claimedTask) == null -> DownloadTaskErrorCode.STORAGE_UNAVAILABLE
                else -> error.toDownloadErrorCode()
            }
            fail(
                downloadDao.getTaskById(claimedTask.id) ?: claimedTask,
                error.message ?: "Download failed",
                errorCode
            )
        }
    }

    private fun resolveStorageTarget(task: DownloadTaskEntity): TvStorageManager.StorageTarget? {
        val storageManager = TvStorageManager(context)
        if (task.targetUri.isNotBlank()) {
            val targetUri = Uri.parse(task.targetUri)
            return TvStorageManager.StorageTarget(
                uri = targetUri,
                location = MediaStorageLocation.fromStored(task.targetStorageType)
                    ?: storageManager.resolveLocalLocation(targetUri),
                isAvailable = storageManager.isStorageTargetAvailable(targetUri)
            ).takeIf { it.isAvailable }
        }
        return storageManager.getAvailableStorageTarget()
    }

    /**
     * SAF 的重命名与 Room 提交无法处于同一个事务；因此此方法必须可重入。
     * 重启后如果发现同一任务的完整最终文件，会在一个 Room 事务内补齐媒体位置和任务状态。
     */
    private suspend fun completeDownload(
        taskId: Long,
        mediaFile: com.wkq.bao.core.database.entity.MediaFileEntity,
        displayFileName: String,
        completedFile: DocumentFile,
        storageType: String,
        remoteInfo: RemoteInfo
    ) {
        val completedUri = completedFile.uri.toString()
        val assembled = AssembledFile(completedFile.length(), sha256(completedFile))
        database.withTransaction {
            val mediaDao = database.mediaDao()
            val downloadDao = database.downloadDao()
            val existingLocation = mediaDao.getMediaLocationByUri(completedUri)
            val localLocation = MediaLocationEntity(
                id = existingLocation?.id ?: 0L,
                mediaFileId = mediaFile.id,
                uri = completedUri,
                storageType = storageType,
                fileName = displayFileName,
                fileSize = assembled.length
            )
            if (existingLocation == null) mediaDao.insertMediaLocation(localLocation)
            else mediaDao.updateMediaLocation(localLocation)
            mediaDao.updateMediaFile(mediaFile.copy(
                fileSize = assembled.length,
                checksum = assembled.sha256,
                downloadStatus = DownloadTaskStatus.SUCCESS,
                updatedAt = System.currentTimeMillis()
            ))
            val completedTask = downloadDao.getTaskById(taskId) ?: return@withTransaction
            if (completedTask.status != DownloadTaskStatus.DOWNLOADING) throw DownloadInterrupted(completedTask.status)
            downloadDao.updateTask(completedTask.copy(
                downloadedBytes = assembled.length,
                assembledBytes = assembled.length,
                totalBytes = remoteInfo.length,
                sourceLastModifiedAt = remoteInfo.lastModifiedAt,
                status = DownloadTaskStatus.SUCCESS,
                finishedAt = System.currentTimeMillis(),
                errorMessage = "",
                errorCode = DownloadTaskErrorCode.NONE
            ))
        }
    }

    private suspend fun completeRawDownload(
        taskId: Long,
        completedFile: DocumentFile,
        remoteInfo: RemoteInfo
    ) {
        val completedLength = completedFile.length()
        database.withTransaction {
            val completedTask = database.downloadDao().getTaskById(taskId) ?: return@withTransaction
            if (completedTask.status != DownloadTaskStatus.DOWNLOADING) throw DownloadInterrupted(completedTask.status)
            database.downloadDao().updateTask(
                completedTask.copy(
                    downloadedBytes = completedLength,
                    assembledBytes = completedLength,
                    totalBytes = remoteInfo.length,
                    sourceLastModifiedAt = remoteInfo.lastModifiedAt,
                    status = DownloadTaskStatus.SUCCESS,
                    finishedAt = System.currentTimeMillis(),
                    errorMessage = "",
                    errorCode = DownloadTaskErrorCode.NONE
                )
            )
        }
    }

    /** 原始 NAS 文件保留配置根目录以下的文件夹结构。 */
    private fun ensureRelativeDirectory(
        downloadRoot: DocumentFile,
        source: com.wkq.bao.core.database.entity.NasSourceEntity,
        uri: Uri
    ): DocumentFile? {
        val uriSegments = uri.pathSegments
        val pathSegments = if (uri.scheme == "smb") uriSegments.drop(1) else uriSegments
        val configuredRoot = if (uri.scheme == "smb") {
            source.rootPath.trim('/').split('/').filter(String::isNotBlank)
        } else {
            listOf(source.shareName, source.rootPath)
                .flatMap { it.trim('/').split('/') }
                .filter(String::isNotBlank)
        }
        val relative = if (
            pathSegments.size >= configuredRoot.size &&
            pathSegments.take(configuredRoot.size).map(String::lowercase) == configuredRoot.map(String::lowercase)
        ) pathSegments.drop(configuredRoot.size) else pathSegments
        var directory = downloadRoot
        relative.dropLast(1).forEach { segment ->
            val safeName = safeFileName(segment)
            directory = directory.findFile(safeName) ?: directory.createDirectory(safeName) ?: return null
            if (!directory.isDirectory) return null
        }
        return directory
    }

    private suspend fun downloadChunkWithRetry(
        source: com.wkq.bao.core.database.entity.NasSourceEntity,
        sourceUri: Uri,
        remoteInfo: RemoteInfo,
        partsDirectory: DocumentFile,
        chunk: DownloadChunkEntity,
        currentCompletedBytes: Long,
        throttle: ProgressThrottle,
        onProgress: (Int) -> Unit,
        readHandle: () -> SmbClientManager.RemoteFileHandle?,
        replaceReadHandle: (SmbClientManager.RemoteFileHandle?) -> Unit
    ): DownloadedPart {
        var lastError: Throwable? = null
        repeat(MAX_RANGE_ATTEMPTS) { attempt ->
            partsDirectory.deletePartFile(chunk.partName)
            val part = partsDirectory.createFile("application/octet-stream", chunk.partName)
                ?: throw DownloadFailure(DownloadTaskErrorCode.STORAGE_ACCESS, "Cannot create download part")
            try {
                val checksum = context.contentResolver.openOutputStream(part.uri, "wt")?.use { output ->
                    if (sourceUri.scheme == "smb") {
                        val handle = readHandle() ?: SmbClientManager.openRemoteFile(source, sourceUri).also(replaceReadHandle)
                        if (RemoteInfo(handle.length, handle.lastModifiedAt) != remoteInfo) {
                            throw DownloadFailure(DownloadTaskErrorCode.SOURCE_CHANGED, "NAS source changed during download")
                        }
                        SmbClientManager.copyRangeTo(handle, output, chunk.startByte, chunk.byteCount) { downloaded, _ ->
                            val totalDownloaded = currentCompletedBytes + downloaded
                            if (throttle.shouldPersist(totalDownloaded, remoteInfo.length)) {
                                updateTaskProgress(chunk.taskId, totalDownloaded, remoteInfo, onProgress)
                            }
                        }
                    } else WebDavClientManager.copyRangeTo(source, sourceUri, output, chunk.startByte, chunk.byteCount) { downloaded, _ ->
                        val totalDownloaded = currentCompletedBytes + downloaded
                        if (throttle.shouldPersist(totalDownloaded, remoteInfo.length)) {
                            updateTaskProgress(chunk.taskId, totalDownloaded, remoteInfo, onProgress)
                        }
                    }
                } ?: throw DownloadFailure(DownloadTaskErrorCode.STORAGE_ACCESS, "Cannot open download part output stream")
                return DownloadedPart(part, checksum)
            } catch (error: Throwable) {
                // 清理失败不能替换真正的传输错误或协程取消信号。
                runCatching { part.delete() }
                runCatching { replaceReadHandle(null) }
                if (error is CancellationException) throw error
                lastError = error
                val retryable = if (sourceUri.scheme == "smb") SmbClientManager.isRetryable(error) else WebDavClientManager.isRetryable(error)
                if (!retryable || attempt == MAX_RANGE_ATTEMPTS - 1) throw error
                delay(RANGE_RETRY_DELAY_MS * (attempt + 1L))
            }
        }
        throw IOException("Download part retry exhausted", lastError)
    }

    private suspend fun restoreAssembling(
        task: DownloadTaskEntity,
        downloadDirectory: DocumentFile,
        partsDirectory: DocumentFile,
        chunks: List<DownloadChunkEntity>,
        assemblingName: String
    ): List<DownloadChunkEntity> {
        val ordered = chunks.sortedBy { it.chunkIndex }
        val assembled = ordered.takeWhile { it.status == DownloadChunkStatus.ASSEMBLED }
        val expectedBytes = assembled.sumOf { it.byteCount }
        val hasOnlyLeadingAssembled = ordered.drop(assembled.size).none { it.status == DownloadChunkStatus.ASSEMBLED }
        val assemblingFile = downloadDirectory.findAssemblingFile(assemblingName)
        val valid = hasOnlyLeadingAssembled &&
            task.assembledBytes == expectedBytes &&
            when {
                expectedBytes == 0L -> assemblingFile == null
                assemblingFile?.length() != expectedBytes -> false
                else -> isAssembledPrefixValid(assemblingFile, assembled)
            }
        if (valid) {
            assembled.forEach { partsDirectory.deletePartFile(it.partName) }
            return ordered
        }

        downloadDirectory.deleteAssemblingFile(assemblingName)
        val reset = ordered.map { chunk ->
            if (chunk.status != DownloadChunkStatus.ASSEMBLED) chunk else chunk.copy(
                status = DownloadChunkStatus.WAITING,
                sha256 = "",
                updatedAt = System.currentTimeMillis()
            ).also { database.downloadDao().updateChunk(it) }
        }
        if (database.downloadDao().updateAssembledBytesWhileDownloading(task.id, 0L) == 0) {
            ensureTaskIsDownloading(task.id)
        }
        return reset
    }

    /** 启动恢复前校验已合并前缀，避免仅凭长度继续损坏的临时文件。 */
    private fun isAssembledPrefixValid(
        assemblingFile: DocumentFile,
        chunks: List<DownloadChunkEntity>
    ): Boolean {
        return runCatching {
            context.contentResolver.openInputStream(assemblingFile.uri)?.use { input ->
                chunks.all { chunk ->
                    if (chunk.sha256.isBlank()) return@all false
                    val digest = MessageDigest.getInstance("SHA-256")
                    var remaining = chunk.byteCount
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (remaining > 0L) {
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read <= 0) return@all false
                        digest.update(buffer, 0, read)
                        remaining -= read
                    }
                    digest.digest().joinToString("") { "%02x".format(it) } == chunk.sha256
                }
            } ?: false
        }.getOrDefault(false)
    }

    private suspend fun appendContiguousCompletedChunks(
        taskId: Long,
        downloadDirectory: DocumentFile,
        partsDirectory: DocumentFile,
        chunks: List<DownloadChunkEntity>,
        assemblingName: String
    ): List<DownloadChunkEntity> {
        val downloadDao = database.downloadDao()
        var updatedChunks = chunks.sortedBy { it.chunkIndex }
        val currentTask = downloadDao.getTaskById(taskId) ?: return updatedChunks
        var assembledBytes = currentTask.assembledBytes
        while (true) {
            val next = updatedChunks.firstOrNull { it.status != DownloadChunkStatus.ASSEMBLED } ?: break
            if (next.status != DownloadChunkStatus.COMPLETED) break
            val part = partsDirectory.findPartFile(next.partName) ?: throw IOException("Download part is missing")
            val assemblingFile = downloadDirectory.findAssemblingFile(assemblingName)
                ?: downloadDirectory.createFile("application/octet-stream", assemblingName)
                ?: throw IOException("Cannot create assembly file")
            appendPart(assemblingFile, part)
            if (assemblingFile.length() != assembledBytes + next.byteCount) {
                throw IOException("Assembled file length validation failed")
            }
            val assembledChunk = next.copy(status = DownloadChunkStatus.ASSEMBLED, updatedAt = System.currentTimeMillis())
            downloadDao.updateChunk(assembledChunk)
            part.delete()
            assembledBytes += next.byteCount
            if (downloadDao.updateAssembledBytesWhileDownloading(taskId, assembledBytes) == 0) {
                ensureTaskIsDownloading(taskId)
            }
            updatedChunks = updatedChunks.map { if (it.id == next.id) assembledChunk else it }
        }
        return updatedChunks
    }

    private fun appendPart(assemblingFile: DocumentFile, part: DocumentFile) {
        context.contentResolver.openOutputStream(assemblingFile.uri, "wa")?.use { output ->
            context.contentResolver.openInputStream(part.uri)?.use { input ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
            } ?: error("Cannot read download part")
        } ?: error("Cannot open assembly output")
    }

    private suspend fun verifyCompletedChunks(
        partsDirectory: DocumentFile,
        chunks: List<DownloadChunkEntity>
    ): List<DownloadChunkEntity> {
        val downloadDao = database.downloadDao()
        return chunks.map { chunk ->
            if (chunk.status != DownloadChunkStatus.COMPLETED) return@map chunk
            val part = partsDirectory.findPartFile(chunk.partName)
            val valid = part != null &&
                part.length() == chunk.byteCount &&
                chunk.sha256.isNotBlank() &&
                sha256(part) == chunk.sha256
            if (valid) chunk else {
                part?.delete()
                val waiting = chunk.copy(
                    status = DownloadChunkStatus.WAITING,
                    sha256 = "",
                    updatedAt = System.currentTimeMillis()
                )
                downloadDao.updateChunk(waiting)
                waiting
            }
        }
    }

    private suspend fun updateTaskProgress(
        taskId: Long,
        downloadedBytes: Long,
        remoteInfo: RemoteInfo,
        onProgress: (Int) -> Unit
    ) {
        if (database.downloadDao().updateProgressWhileDownloading(taskId, downloadedBytes,
                remoteInfo.length, remoteInfo.lastModifiedAt) == 0) {
            ensureTaskIsDownloading(taskId)
        }
        onProgress(((downloadedBytes * 100L) / remoteInfo.length).toInt().coerceIn(0, 100))
    }

    private suspend fun ensureTaskIsDownloading(taskId: Long) {
        val current = database.downloadDao().getTaskById(taskId) ?: throw DownloadInterrupted(DownloadTaskStatus.CANCELLED)
        if (current.status != DownloadTaskStatus.DOWNLOADING) throw DownloadInterrupted(current.status)
    }

    private fun deletePartFiles(partsDirectory: DocumentFile, chunks: List<DownloadChunkEntity>) {
        chunks.forEach { partsDirectory.deletePartFile(it.partName) }
    }

    private fun sha256(file: DocumentFile): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(file.uri)?.use { input ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        } ?: error("Cannot read download part")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun getRemoteFileInfo(
        source: com.wkq.bao.core.database.entity.NasSourceEntity,
        uri: Uri
    ): RemoteInfo = if (uri.scheme == "smb") {
        SmbClientManager.getRemoteFileInfo(source, uri).let { RemoteInfo(it.length, it.lastModifiedAt) }
    } else {
        WebDavClientManager.getRemoteFileInfo(source, uri).let { RemoteInfo(it.length, it.lastModifiedAt) }
    }

    private suspend fun fail(task: DownloadTaskEntity, message: String, errorCode: String) {
        if (errorCode == DownloadTaskErrorCode.STORAGE_UNAVAILABLE) database.downloadDao().waitForStorage(task.id)
        else database.downloadDao().failDownloadingTask(task.id, message, errorCode)
    }

    private class DownloadInterrupted(val status: String) : IOException()

    private class DownloadFailure(val errorCode: String, message: String) : Exception(message)

    private fun Throwable.toDownloadErrorCode(): String = when (this) {
        is DownloadFailure -> errorCode
        is IOException -> DownloadTaskErrorCode.NETWORK
        else -> DownloadTaskErrorCode.UNKNOWN
    }

    private class ProgressThrottle {
        private var lastProgress = -1
        private var lastUpdatedAt = 0L

        fun shouldPersist(downloaded: Long, total: Long): Boolean {
            val progress = ((downloaded * 100L) / total).toInt()
            val now = SystemClock.elapsedRealtime()
            if (progress == lastProgress && now - lastUpdatedAt < PROGRESS_UPDATE_INTERVAL_MS) return false
            lastProgress = progress
            lastUpdatedAt = now
            return true
        }
    }

    private data class AssembledFile(val length: Long, val sha256: String)
    private data class DownloadedPart(val part: DocumentFile, val checksum: String)


    private fun DocumentFile.findPartFile(name: String): DocumentFile? =
        findFile(name) ?: findFile("$name.bin")

    private fun DocumentFile.deletePartFile(name: String) {
        findFile(name)?.delete()
        findFile("$name.bin")?.delete()
    }

    private fun DocumentFile.findAssemblingFile(name: String): DocumentFile? =
        findFile(name) ?: findFile("$name.bin")

    private fun DocumentFile.deleteAssemblingFile(name: String) {
        findFile(name)?.delete()
        findFile("$name.bin")?.delete()
    }

    private companion object {
        const val DOWNLOAD_DIRECTORY = "YuanBaoTV"
        const val PARTS_DIRECTORY = ".yuanbao_parts"
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val PROGRESS_UPDATE_INTERVAL_MS = 1_000L
        const val MAX_RANGE_ATTEMPTS = 3
        const val RANGE_RETRY_DELAY_MS = 1_000L

        fun safeFileName(fileName: String): String =
            DownloadFileName.sanitize(fileName)
    }
}
