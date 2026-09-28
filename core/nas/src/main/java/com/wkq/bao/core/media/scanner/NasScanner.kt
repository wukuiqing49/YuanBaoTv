package com.wkq.bao.core.media.scanner

import android.net.Uri
import androidx.room.withTransaction
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.database.entity.EpisodeEntity
import com.wkq.bao.core.database.entity.MediaFileEntity
import com.wkq.bao.core.database.entity.MediaRemoteSourceEntity
import com.wkq.bao.core.database.entity.MediaSeriesEntity
import com.wkq.bao.core.database.entity.MediaSeriesType
import com.wkq.bao.core.database.entity.NasSourceEntity
import com.wkq.bao.core.database.entity.SeasonEntity
import com.wkq.bao.core.media.parser.MediaFileNameParser
import com.wkq.bao.core.media.scraper.MetadataScraper
import com.wkq.bao.core.media.scraper.MovieNfoParser
import com.wkq.bao.core.media.smb.SmbClientManager
import com.wkq.bao.core.media.smb.SmbCredentialRegistry
import com.wkq.bao.core.media.webdav.WebDavClientManager
import com.wkq.bao.core.media.webdav.WebDavCredentialRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import kotlin.coroutines.coroutineContext

/** NAS 媒体扫描。遍历与入库同步进行，避免先聚合整个库的路径列表。 */
class NasScanner(private val database: AppDatabase) {

    private val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "ts", "webm", "m4v")

    fun isVideoFile(fileName: String): Boolean =
        fileName.substringAfterLast(".", "").lowercase() in videoExtensions

    suspend fun scanAndImport(
        nasSource: NasSourceEntity,
        resumeAfterPath: String = "",
        scanStartedAt: Long = maxOf(System.currentTimeMillis(), nasSource.lastScanAt + 1L),
        initialImportedCount: Int = 0,
        persistCheckpoint: suspend (importedCount: Int, checkpoint: String) -> Unit = { _, _ -> },
        persistCompletion: suspend (importedCount: Int) -> Unit = {},
        onProgress: suspend (importedCount: Int, checkpoint: String) -> Unit = { _, _ -> }
    ): Result<Int> = withContext(Dispatchers.IO) {
        if (WebDavClientManager.isWebDav(nasSource)) WebDavCredentialRegistry.register(nasSource)
        else SmbCredentialRegistry.register(nasSource)
        var importedCount = initialImportedCount
        val pendingFiles = ArrayList<NasRemoteMediaFile>(BATCH_SIZE)
        val nfoCache = object : LinkedHashMap<String, MovieNfoParser.Metadata?>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MovieNfoParser.Metadata?>): Boolean =
                size > 128
        }

        suspend fun flushPendingFiles() {
            if (pendingFiles.isEmpty()) return
            coroutineContext.ensureActive()
            val batch = pendingFiles.toList()
            pendingFiles.clear()
            val nextImportedCount = importedCount + database.withTransaction {
                val batchCount = batch.sumOf { importFile(nasSource, it, scanStartedAt) }
                persistCheckpoint(importedCount + batchCount, batch.last().path)
                batchCount
            }
            importedCount = nextImportedCount
            onProgress(importedCount, batch.last().path)
        }

        val onRemoteFile: suspend (NasRemoteMediaFile) -> Unit = { remoteFile ->
            if (isVideoFile(remoteFile.path.substringAfterLast("/"))) {
                val isMovie = MediaFileNameParser.parse(remoteFile.path.substringAfterLast('/')).mediaType == MediaSeriesType.MOVIE
                val nfo = remoteFile.nfoUri.takeIf { isMovie && it.isNotBlank() }?.let { uri ->
                    if (!nfoCache.containsKey(uri)) nfoCache[uri] = readMovieNfo(nasSource, uri)
                    nfoCache[uri]
                }
                pendingFiles += remoteFile.copy(nfo = nfo)
                if (pendingFiles.size >= BATCH_SIZE) flushPendingFiles()
            }
        }
        val scanResult = if (WebDavClientManager.isWebDav(nasSource)) {
            WebDavClientManager.scanFilesRecursive(nasSource, resumeAfterPath.ifBlank { null }) { remoteFile ->
                onRemoteFile(
                    NasRemoteMediaFile(
                        remoteFile.path,
                        remoteFile.length,
                        remoteFile.lastModifiedAt,
                        remoteFile.posterUri,
                        remoteFile.backdropUri,
                        remoteFile.thumbnailUri,
                        remoteFile.nfoUri
                    )
                )
            }
        } else {
            SmbClientManager.scanFilesRecursive(nasSource, resumeAfterPath.ifBlank { null }) { remoteFile ->
                onRemoteFile(NasRemoteMediaFile(
                    remoteFile.path, remoteFile.length, remoteFile.lastModifiedAt,
                    remoteFile.posterUri, remoteFile.backdropUri, remoteFile.thumbnailUri,
                    remoteFile.nfoUri
                ))
            }
        }
        if (scanResult.isFailure) {
            return@withContext Result.failure(scanResult.exceptionOrNull() ?: Exception("NAS scan failed"))
        }
        flushPendingFiles()
        database.withTransaction {
            database.mediaDao().deleteMediaRemoteSourcesNotSeenSince(nasSource.id, scanStartedAt)
            database.nasDao().updateSource(nasSource.copy(lastScanAt = scanStartedAt))
            persistCompletion(importedCount)
        }
        Result.success(importedCount)
    }

    /** 保留测试和导入接口；实时扫描路径使用 [scanAndImport] 以获取 SMB 目录项元数据。 */
    suspend fun importFiles(nasSource: NasSourceEntity, rawFileList: List<String>): Int = withContext(Dispatchers.IO) {
        val scanStartedAt = maxOf(System.currentTimeMillis(), nasSource.lastScanAt + 1L)
        var importedCount = 0
        rawFileList.asSequence()
            .filter { isVideoFile(it.substringAfterLast("/")) }
            .map { NasRemoteMediaFile(it, 0L, 0L) }
            .chunked(BATCH_SIZE)
            .forEach { batch ->
                coroutineContext.ensureActive()
                importedCount += database.withTransaction {
                    batch.sumOf { importFile(nasSource, it, scanStartedAt) }
                }
            }
        database.withTransaction {
            database.mediaDao().deleteMediaRemoteSourcesNotSeenSince(nasSource.id, scanStartedAt)
            database.nasDao().updateSource(nasSource.copy(lastScanAt = scanStartedAt))
        }
        importedCount
    }

    private suspend fun importFile(
        nasSource: NasSourceEntity,
        remoteFile: NasRemoteMediaFile,
        scanStartedAt: Long
    ): Int {
        val mediaDao = database.mediaDao()
        val fileName = remoteFile.path.substringAfterLast("/")
        val parsed = MediaFileNameParser.parse(fileName)
        val nasUri = if (WebDavClientManager.isWebDav(nasSource)) {
            WebDavClientManager.buildUri(nasSource, remoteFile.path)
        } else {
            SmbClientManager.buildUri(nasSource, remoteFile.path)
        }
        // 没有 poster.jpg 等侧车图时，由 Coil 按需从视频提取一帧并写入磁盘缓存。
        val videoFrameUri = Uri.parse(nasUri).buildUpon()
            .appendQueryParameter("artworkFrame", "1")
            .appendQueryParameter("artworkVersion", remoteFile.lastModifiedAt.toString())
            .build()
            .toString()

        val scraped = MetadataScraper.scrape(parsed.seriesTitle)
        val nfo = remoteFile.nfo
        val resolvedPosterUri = remoteFile.posterUri.ifBlank {
            remoteFile.thumbnailUri.takeIf { parsed.mediaType == MediaSeriesType.MOVIE }.orEmpty()
        }.ifBlank { scraped.posterUri }
            .ifBlank { videoFrameUri }
        val resolvedBackdropUri = remoteFile.backdropUri.ifBlank { scraped.backdropUri }
            .ifBlank { videoFrameUri }
        val resolvedEpisodeThumbnailUri = remoteFile.thumbnailUri.ifBlank { videoFrameUri }

        val displayTitle = nfo?.title?.takeIf(String::isNotBlank) ?: parsed.seriesTitle
        val existingSeries = mediaDao.getSeriesByTitle(parsed.seriesTitle)
            ?: displayTitle.takeIf { it != parsed.seriesTitle }?.let { mediaDao.getSeriesByTitle(it) }
        val series = existingSeries?.copy(
            type = existingSeries.type.takeUnless { it in setOf(MediaSeriesType.CARTOON, MediaSeriesType.LOCAL) }
                ?: parsed.mediaType,
            totalSeasons = maxOf(existingSeries.totalSeasons, parsed.seasonNumber),
            originalTitle = if (existingSeries.originalTitle.isBlank() || existingSeries.originalTitle == existingSeries.title) {
                nfo?.originalTitle?.ifBlank { existingSeries.originalTitle } ?: existingSeries.originalTitle
            } else existingSeries.originalTitle,
            year = existingSeries.year.ifBlank { nfo?.year.orEmpty() },
            genre = existingSeries.genre.ifBlank { nfo?.genre.orEmpty() },
            description = existingSeries.description.ifBlank { nfo?.description.orEmpty() },
            posterUri = NasArtworkPriority.prefer(existingSeries.posterUri, resolvedPosterUri),
            backdropUri = NasArtworkPriority.prefer(existingSeries.backdropUri, resolvedBackdropUri),
            updatedAt = System.currentTimeMillis()
        )?.also { updated ->
            if (updated != existingSeries) mediaDao.updateSeries(updated)
        } ?: run {
            val seriesId = mediaDao.insertSeries(
                MediaSeriesEntity(
                    title = displayTitle,
                    originalTitle = nfo?.originalTitle?.ifBlank { scraped.originalTitle } ?: scraped.originalTitle,
                    type = parsed.mediaType,
                    genre = nfo?.genre?.ifBlank { scraped.genre } ?: scraped.genre,
                    year = nfo?.year?.ifBlank { scraped.year } ?: scraped.year,
                    description = nfo?.description?.ifBlank { scraped.description } ?: scraped.description,
                    posterUri = resolvedPosterUri,
                    backdropUri = resolvedBackdropUri,
                    totalSeasons = parsed.seasonNumber.coerceAtLeast(1)
                )
            )
            mediaDao.getSeriesById(seriesId) ?: error("Cannot create NAS media index")
        }
        val seriesId = series.id

        val existingSeason = mediaDao.getSeason(seriesId, parsed.seasonNumber)
        val season = existingSeason?.copy(
            episodeCount = maxOf(existingSeason.episodeCount, parsed.episodeNumber)
        )?.also { updated ->
            if (updated != existingSeason) mediaDao.updateSeason(updated)
        } ?: SeasonEntity(
            seriesId = seriesId,
            seasonNumber = parsed.seasonNumber,
            title = "Season ${parsed.seasonNumber}",
            episodeCount = parsed.episodeNumber
        )
        val seasonId = if (existingSeason == null) mediaDao.insertSeason(season) else season.id

        val existingEpisode = mediaDao.getEpisodeByNumber(seasonId, parsed.episodeNumber)
        val resolvedEpisode = existingEpisode?.copy(
            thumbnailUri = NasArtworkPriority.prefer(
                existingEpisode.thumbnailUri,
                resolvedEpisodeThumbnailUri
            )
        )?.also { updated ->
            if (updated != existingEpisode) mediaDao.updateEpisode(updated)
        }
        val episodeId = resolvedEpisode?.id ?: mediaDao.insertEpisode(
            EpisodeEntity(
                seriesId = seriesId,
                seasonId = seasonId,
                episodeNumber = parsed.episodeNumber,
                title = parsed.episodeTitle,
                thumbnailUri = resolvedEpisodeThumbnailUri
            )
        )

        val existingFile = mediaDao.getMediaFileByEpisodeId(episodeId)
        val mediaFile = existingFile?.copy(
            fileName = existingFile.fileName.ifBlank { fileName },
            fileSize = maxOf(existingFile.fileSize, remoteFile.length),
            updatedAt = System.currentTimeMillis()
        )?.also { updated ->
            if (updated != existingFile) mediaDao.updateMediaFile(updated)
        } ?: run {
            val mediaFileId = mediaDao.insertMediaFile(
                MediaFileEntity(
                    seriesId = seriesId,
                    episodeId = episodeId,
                    fileName = fileName,
                    fileSize = remoteFile.length,
                    mimeType = "video/*"
                )
            )
            mediaDao.getMediaFileById(mediaFileId) ?: error("Cannot create NAS media file")
        }

        val existingRemote = mediaDao.getMediaRemoteSource(mediaFile.id, nasSource.id, nasUri)
        val remoteSource = MediaRemoteSourceEntity(
            id = existingRemote?.id ?: 0L,
            mediaFileId = mediaFile.id,
            nasSourceId = nasSource.id,
            uri = nasUri,
            fileName = fileName,
            fileSize = remoteFile.length,
            updatedAt = scanStartedAt
        )
        if (existingRemote == null) mediaDao.insertMediaRemoteSource(remoteSource)
        else mediaDao.updateMediaRemoteSource(remoteSource)
        return 1
    }

    private companion object {
        const val BATCH_SIZE = 64
    }

    private fun readMovieNfo(source: NasSourceEntity, uri: String): MovieNfoParser.Metadata? = runCatching {
        val parsedUri = Uri.parse(uri)
        if (WebDavClientManager.isWebDav(source)) {
            WebDavClientManager.openRemoteFile(source, parsedUri).use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.byteStream()?.use(MovieNfoParser::parse)
            }
        } else {
            SmbClientManager.withRemoteFile(source, parsedUri) { file, length ->
                val bytes = ByteArray(minOf(length, 256L * 1024L + 1L).toInt())
                var offset = 0
                while (offset < bytes.size) {
                    val read = file.read(bytes, offset.toLong(), offset, bytes.size - offset)
                    if (read <= 0) break
                    offset += read
                }
                MovieNfoParser.parse(ByteArrayInputStream(bytes, 0, offset))
            }
        }
    }.getOrNull()
}

/** 真实海报到达后可以替换此前生成的抽帧，避免占位封面长期滞留。 */
internal object NasArtworkPriority {
    fun prefer(current: String, candidate: String): String = when {
        candidate.isBlank() -> current
        current.isBlank() -> candidate
        isGeneratedFrame(current) && !isGeneratedFrame(candidate) -> candidate
        else -> current
    }

    private fun isGeneratedFrame(uri: String): Boolean = uri
        .substringAfter('?', missingDelimiterValue = "")
        .split('&')
        .any { it == "artworkFrame=1" }
}
