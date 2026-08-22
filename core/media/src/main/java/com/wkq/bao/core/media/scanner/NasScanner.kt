package com.wkq.bao.core.media.scanner

import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.database.entity.EpisodeEntity
import com.wkq.bao.core.database.entity.MediaFileEntity
import com.wkq.bao.core.database.entity.MediaSeriesEntity
import com.wkq.bao.core.database.entity.NasSourceEntity
import com.wkq.bao.core.database.entity.SeasonEntity
import com.wkq.bao.core.media.parser.MediaFileNameParser
import com.wkq.bao.core.media.smb.SmbCredentialRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * NAS 局域网媒体扫描与入库调度器
 */
class NasScanner(private val database: AppDatabase) {

    private val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "ts", "webm", "m4v")

    fun isVideoFile(fileName: String): Boolean {
        val ext = fileName.substringAfterLast(".", "").lowercase()
        return videoExtensions.contains(ext)
    }

    /**
     * 连接真实 NAS 并执行扫描入库
     */
    suspend fun scanAndImport(nasSource: NasSourceEntity): Result<Int> = withContext(Dispatchers.IO) {
        SmbCredentialRegistry.register(nasSource)
        val listResult = com.wkq.bao.core.media.smb.SmbClientManager.listFilesRecursive(nasSource)
        if (listResult.isFailure) {
            return@withContext Result.failure(listResult.exceptionOrNull() ?: Exception("扫描失败"))
        }
        val files = listResult.getOrDefault(emptyList())
        val count = importFiles(nasSource, files)
        Result.success(count)
    }

    /**
     * 将扫描到的文件列表解析并组织入库
     */
    suspend fun importFiles(
        nasSource: NasSourceEntity,
        rawFileList: List<String>
    ): Int = withContext(Dispatchers.IO) {
        var importedCount = 0

        for (filePath in rawFileList) {
            val fileName = filePath.substringAfterLast("/")
            if (!isVideoFile(fileName)) continue

            val parsed = MediaFileNameParser.parse(fileName)
            
            // 1. 查找或插入 Series
            val existingSeries = database.mediaDao().getAllSeriesSync().find { 
                it.title.equals(parsed.seriesTitle, ignoreCase = true) 
            }
            val seriesId = if (existingSeries != null) {
                existingSeries.id
            } else {
                val scraped = com.wkq.bao.core.media.scraper.MetadataScraper.scrape(parsed.seriesTitle)
                database.mediaDao().insertSeries(
                    MediaSeriesEntity(
                        title = parsed.seriesTitle,
                        originalTitle = scraped.originalTitle,
                        type = "CARTOON",
                        genre = scraped.genre,
                        year = scraped.year,
                        description = scraped.description,
                        posterUri = scraped.posterUri,
                        backdropUri = scraped.backdropUri,
                        totalSeasons = parsed.seasonNumber.coerceAtLeast(1)
                    )
                )
            }

            // 2. 查找或插入 Season
            val existingSeason = database.mediaDao().getSeasonsSync(seriesId).find { 
                it.seasonNumber == parsed.seasonNumber 
            }
            val seasonId = existingSeason?.id ?: database.mediaDao().insertSeason(
                SeasonEntity(
                    seriesId = seriesId,
                    seasonNumber = parsed.seasonNumber,
                    title = "Season ${parsed.seasonNumber}"
                )
            )

            // 3. 查找或插入 Episode
            val existingEpisode = database.mediaDao().getEpisodesSync(seriesId, seasonId).find { 
                it.episodeNumber == parsed.episodeNumber 
            }
            val episodeId = existingEpisode?.id ?: database.mediaDao().insertEpisode(
                EpisodeEntity(
                    seriesId = seriesId,
                    seasonId = seasonId,
                    episodeNumber = parsed.episodeNumber,
                    title = parsed.episodeTitle.ifEmpty { "第 ${parsed.episodeNumber} 集" },
                    durationMs = 1200000L
                )
            )

            // 4. 插入或更新 MediaFile 关联映射 (NasUri -> LocalUri)
            val nasUri = com.wkq.bao.core.media.smb.SmbClientManager.buildUri(nasSource, filePath)
            val existingFile = database.mediaDao().getMediaFiles(episodeId).firstOrNull()

            if (existingFile == null) {
                database.mediaDao().insertMediaFile(
                    MediaFileEntity(
                        seriesId = seriesId,
                        episodeId = episodeId,
                        nasSourceId = nasSource.id,
                        nasUri = nasUri,
                        fileName = fileName,
                        fileSize = 850000000L,
                        mimeType = "video/*"
                    )
                )
            } else if (existingFile.nasUri != nasUri || existingFile.nasSourceId != nasSource.id) {
                database.mediaDao().updateMediaFile(
                    existingFile.copy(
                        nasSourceId = nasSource.id,
                        nasUri = nasUri,
                        fileName = fileName,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            importedCount++
        }

        // 更新 NAS 最后扫描时间
        database.nasDao().updateSource(
            nasSource.copy(lastScanAt = System.currentTimeMillis())
        )

        return@withContext importedCount
    }
}
