package com.wkq.bao.core.media.scanner

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.database.entity.EpisodeEntity
import com.wkq.bao.core.database.entity.MediaFileEntity
import com.wkq.bao.core.database.entity.MediaLocationEntity
import com.wkq.bao.core.database.entity.MediaSeriesEntity
import com.wkq.bao.core.database.entity.MediaSeriesType
import com.wkq.bao.core.database.entity.SeasonEntity
import com.wkq.bao.core.media.artwork.SidecarArtworkResolver
import com.wkq.bao.core.media.parser.MediaFileNameParser
import com.wkq.bao.core.media.scraper.MetadataScraper
import com.wkq.bao.core.media.scraper.MovieNfoParser
import com.wkq.bao.core.media.storage.TvStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import kotlin.coroutines.coroutineContext

/** 扫描用户授权目录中的已有媒体，并将每个文件登记为独立本地副本。 */
class LocalMediaScanner(
    private val context: Context,
    private val database: AppDatabase
) {
    private val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "ts", "webm", "m4v")

    suspend fun scanAndImport(
        treeUri: Uri,
        resumeAfterUri: String = "",
        scanStartedAt: Long = System.currentTimeMillis(),
        initialImportedCount: Int = 0,
        persistCheckpoint: suspend (importedCount: Int, checkpoint: String) -> Unit = { _, _ -> },
        persistCompletion: suspend (importedCount: Int) -> Unit = {},
        onProgress: suspend (importedCount: Int, checkpoint: String) -> Unit = { _, _ -> }
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: error("无法读取所选目录")
            val directories = ArrayDeque<LocalDirectory>()
            val pendingFiles = ArrayList<LocalMediaFile>(BATCH_SIZE)
            directories.add(LocalDirectory(root, SidecarArtworkResolver.DirectoryArtwork()))
            var importedCount = initialImportedCount
            var checkpointReached = resumeAfterUri.isBlank()

            suspend fun flushPendingFiles() {
                if (pendingFiles.isEmpty()) return
                coroutineContext.ensureActive()
                val batch = pendingFiles.toList()
                pendingFiles.clear()
                val nextImportedCount = importedCount + database.withTransaction {
                    val batchCount = batch.sumOf { importFile(it, scanStartedAt) }
                    persistCheckpoint(importedCount + batchCount, batch.last().uri.toString())
                    batchCount
                }
                importedCount = nextImportedCount
                onProgress(importedCount, batch.last().uri.toString())
            }

            while (directories.isNotEmpty()) {
                coroutineContext.ensureActive()
                val directory = directories.removeFirst()
                val children = directory.document.listFiles().sortedBy { it.name.orEmpty().lowercase() }
                val artwork = SidecarArtworkResolver.resolveDirectory(
                    candidates = children.asSequence()
                        .filter(DocumentFile::isFile)
                        .mapNotNull { image ->
                            val fileName = image.name ?: return@mapNotNull null
                            SidecarArtworkResolver.Candidate(fileName, image.uri.toString())
                        }
                        .toList(),
                    inherited = directory.inheritedArtwork
                )
                children.forEach { document ->
                    when {
                        document.isDirectory -> directories.addLast(LocalDirectory(document, artwork))
                        document.isFile && isVideoFile(document.name.orEmpty()) -> {
                            if (!checkpointReached) {
                                checkpointReached = document.uri.toString() == resumeAfterUri
                                return@forEach
                            }
                            pendingFiles += document.toLocalMediaFile(artwork) ?: return@forEach
                            if (pendingFiles.size >= BATCH_SIZE) flushPendingFiles()
                        }
                    }
                }
            }
            check(checkpointReached) { "扫描检查点已失效" }
            flushPendingFiles()
            database.withTransaction {
                database.mediaDao().deleteMediaLocationsNotSeenInTree(
                    treeUri = treeUri.toString().trimEnd('/'),
                    scanStartedAt = scanStartedAt
                )
                persistCompletion(importedCount)
            }
            importedCount
        }.onFailure { if (it is CancellationException) throw it }
    }

    private fun isVideoFile(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in videoExtensions

    private fun DocumentFile.toLocalMediaFile(
        artwork: SidecarArtworkResolver.DirectoryArtwork
    ): LocalMediaFile? {
        val fileName = name ?: return null
        val uri = uri
        val mediaArtwork = SidecarArtworkResolver.resolveMedia(fileName, artwork)
        val isMovie = MediaFileNameParser.parse(fileName).mediaType == MediaSeriesType.MOVIE
        val frameUri = if (isMovie) uri.buildUpon()
            .appendQueryParameter("artworkFrame", "1")
            .appendQueryParameter("artworkVersion", lastModified().toString())
            .build().toString() else ""
        val nfo = mediaArtwork.nfoUri.takeIf { it.isNotBlank() && MediaFileNameParser.parse(fileName).mediaType == MediaSeriesType.MOVIE }
            ?.let { uri -> runCatching {
                context.contentResolver.openInputStream(Uri.parse(uri))?.use(MovieNfoParser::parse)
            }.getOrNull() }
        return LocalMediaFile(
            uri = uri,
            fileName = fileName,
            fileSize = length(),
            mimeType = type ?: "video/*",
            storageType = TvStorageManager(context).resolveLocalLocation(uri).name,
            posterUri = mediaArtwork.posterUri.ifBlank { mediaArtwork.thumbnailUri.takeIf { isMovie }.orEmpty() }.ifBlank { frameUri },
            backdropUri = mediaArtwork.backdropUri.ifBlank { frameUri },
            thumbnailUri = mediaArtwork.thumbnailUri,
            nfo = nfo
        )
    }

    private suspend fun importFile(document: LocalMediaFile, scanStartedAt: Long): Int {
        val fileName = document.fileName
        val parsed = MediaFileNameParser.parse(fileName)
        val mediaDao = database.mediaDao()

        val nfo = document.nfo
        val displayTitle = nfo?.title?.takeIf(String::isNotBlank) ?: parsed.seriesTitle
        val existingSeries = mediaDao.getSeriesByTitle(parsed.seriesTitle)
            ?: displayTitle.takeIf { it != parsed.seriesTitle }?.let { mediaDao.getSeriesByTitle(it) }
        val resolvedPosterUri = document.posterUri.ifBlank {
            document.thumbnailUri.takeIf { parsed.mediaType == MediaSeriesType.MOVIE }.orEmpty()
        }
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
            posterUri = preferArtwork(existingSeries.posterUri, resolvedPosterUri),
            backdropUri = preferArtwork(existingSeries.backdropUri, document.backdropUri)
        )?.also { updated ->
            if (updated != existingSeries) mediaDao.updateSeries(updated)
        } ?: run {
            val metadata = MetadataScraper.scrape(parsed.seriesTitle)
            val seriesId = mediaDao.insertSeries(
                MediaSeriesEntity(
                    title = displayTitle,
                    originalTitle = nfo?.originalTitle?.ifBlank { metadata.originalTitle } ?: metadata.originalTitle,
                    type = parsed.mediaType,
                    genre = nfo?.genre?.ifBlank { metadata.genre } ?: metadata.genre,
                    year = nfo?.year?.ifBlank { metadata.year } ?: metadata.year,
                    description = nfo?.description?.ifBlank { metadata.description } ?: metadata.description,
                    posterUri = resolvedPosterUri.ifBlank { metadata.posterUri },
                    backdropUri = document.backdropUri.ifBlank { metadata.backdropUri },
                    totalSeasons = parsed.seasonNumber.coerceAtLeast(1)
                )
            )
            mediaDao.getSeriesById(seriesId) ?: error("无法创建本地媒体索引")
        }
        val seriesId = series.id

        val season = mediaDao.getSeason(seriesId, parsed.seasonNumber)
        val resolvedSeason = season?.let { current ->
            current.copy(
                episodeCount = maxOf(current.episodeCount, parsed.episodeNumber)
            ).also { updated ->
                if (updated != current) mediaDao.updateSeason(updated)
            }
        } ?: SeasonEntity(
            seriesId = seriesId,
            seasonNumber = parsed.seasonNumber,
            title = "Season ${parsed.seasonNumber}",
            episodeCount = parsed.episodeNumber
        )
        val seasonId = if (season == null) mediaDao.insertSeason(resolvedSeason) else resolvedSeason.id

        val episode = mediaDao.getEpisodeByNumber(seasonId, parsed.episodeNumber)
        val resolvedEpisode = episode?.copy(
            thumbnailUri = episode.thumbnailUri.ifBlank { document.thumbnailUri }
        )?.also { updated ->
            if (updated != episode) mediaDao.updateEpisode(updated)
        } ?: EpisodeEntity(
                seriesId = seriesId,
                seasonId = seasonId,
                episodeNumber = parsed.episodeNumber,
                title = parsed.episodeTitle,
                thumbnailUri = document.thumbnailUri
            )
        val episodeId = if (episode == null) mediaDao.insertEpisode(resolvedEpisode) else resolvedEpisode.id

        val mediaFile = mediaDao.getMediaFileByEpisodeId(episodeId) ?: run {
            val mediaFileId = mediaDao.insertMediaFile(
                MediaFileEntity(
                    episodeId = episodeId,
                    seriesId = seriesId,
                    fileName = fileName,
                    fileSize = document.fileSize,
                    mimeType = document.mimeType
                )
            )
            mediaDao.getMediaFileById(mediaFileId) ?: error("无法创建本地媒体索引")
        }

        val uri = document.uri.toString()
        val existingLocation = mediaDao.getMediaLocationByUri(uri)
        val location = MediaLocationEntity(
            id = existingLocation?.id ?: 0L,
            mediaFileId = mediaFile.id,
            uri = uri,
            storageType = document.storageType,
            fileName = fileName,
            fileSize = document.fileSize,
            updatedAt = scanStartedAt
        )
        if (existingLocation == null) mediaDao.insertMediaLocation(location)
        else mediaDao.updateMediaLocation(location)
        return 1
    }

    private data class LocalMediaFile(
        val uri: Uri,
        val fileName: String,
        val fileSize: Long,
        val mimeType: String,
        val storageType: String,
        val posterUri: String,
        val backdropUri: String,
        val thumbnailUri: String,
        val nfo: MovieNfoParser.Metadata?
    )

    private fun preferArtwork(current: String, candidate: String): String = when {
        current.isBlank() -> candidate
        candidate.isBlank() -> current
        candidate.startsWith("content://") && (current.startsWith("smb://") || current.startsWith("https://")) -> candidate
        "artworkFrame=1" in current && "artworkFrame=1" !in candidate -> candidate
        else -> current
    }

    private data class LocalDirectory(
        val document: DocumentFile,
        val inheritedArtwork: SidecarArtworkResolver.DirectoryArtwork
    )

    private companion object {
        const val BATCH_SIZE = 64
    }
}
