package com.wkq.bao.core.media.scanner

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
import com.wkq.bao.core.media.smb.SmbClientManager
import com.wkq.bao.core.media.smb.SmbCredentialRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
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
        SmbCredentialRegistry.register(nasSource)
        var importedCount = initialImportedCount
        val pendingFiles = ArrayList<SmbClientManager.RemoteMediaFile>(BATCH_SIZE)

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

        val scanResult = SmbClientManager.scanFilesRecursive(
            source = nasSource,
            resumeAfterPath = resumeAfterPath.ifBlank { null }
        ) { remoteFile ->
            if (isVideoFile(remoteFile.path.substringAfterLast("/"))) {
                pendingFiles += remoteFile
                if (pendingFiles.size >= BATCH_SIZE) flushPendingFiles()
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
            .map { SmbClientManager.RemoteMediaFile(it, 0L, 0L) }
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
        remoteFile: SmbClientManager.RemoteMediaFile,
        scanStartedAt: Long
    ): Int {
        val mediaDao = database.mediaDao()
        val fileName = remoteFile.path.substringAfterLast("/")
        val parsed = MediaFileNameParser.parse(fileName)

        val existingSeries = mediaDao.getSeriesByTitle(parsed.seriesTitle)
        val series = existingSeries?.copy(
            type = existingSeries.type.takeUnless { it in setOf(MediaSeriesType.CARTOON, MediaSeriesType.LOCAL) }
                ?: parsed.mediaType,
            totalSeasons = maxOf(existingSeries.totalSeasons, parsed.seasonNumber),
            updatedAt = System.currentTimeMillis()
        )?.also { updated ->
            if (updated != existingSeries) mediaDao.updateSeries(updated)
        } ?: run {
            val scraped = MetadataScraper.scrape(parsed.seriesTitle)
            val seriesId = mediaDao.insertSeries(
                MediaSeriesEntity(
                    title = parsed.seriesTitle,
                    originalTitle = scraped.originalTitle,
                    type = parsed.mediaType,
                    genre = scraped.genre,
                    year = scraped.year,
                    description = scraped.description,
                    posterUri = scraped.posterUri,
                    backdropUri = scraped.backdropUri,
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
        val episodeId = existingEpisode?.id ?: mediaDao.insertEpisode(
            EpisodeEntity(
                seriesId = seriesId,
                seasonId = seasonId,
                episodeNumber = parsed.episodeNumber,
                title = parsed.episodeTitle.ifEmpty { "Episode ${parsed.episodeNumber}" }
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

        val nasUri = SmbClientManager.buildUri(nasSource, remoteFile.path)
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
}
