package com.wkq.bao.core.media.repository

import android.content.Context
import android.net.Uri
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.database.entity.DownloadTaskEntity
import com.wkq.bao.core.database.entity.DownloadTaskStatus
import com.wkq.bao.core.database.entity.EpisodeEntity
import com.wkq.bao.core.database.entity.EpisodeWithSource
import com.wkq.bao.core.database.entity.FavoriteEntity
import com.wkq.bao.core.database.entity.MediaSeriesEntity
import com.wkq.bao.core.database.entity.SeasonEntity
import com.wkq.bao.core.media.download.DownloadWorkScheduler
import com.wkq.bao.core.media.storage.TvStorageManager
import com.wkq.bao.core.media.storage.MediaStorageLocation
import kotlinx.coroutines.flow.Flow

interface MediaDetailRepository {
    suspend fun getSeries(seriesId: Long): MediaSeriesEntity?
    fun observeSeasons(seriesId: Long): Flow<List<SeasonEntity>>
    fun observeEpisodes(seriesId: Long, seasonId: Long): Flow<List<EpisodeWithSource>>
    suspend fun getFirstPlayableEpisode(seriesId: Long, seasonId: Long, isMovie: Boolean): EpisodeEntity?
    suspend fun isFavorite(seriesId: Long): Boolean
    suspend fun toggleFavorite(seriesId: Long): Boolean
    suspend fun enqueueDownloads(
        seriesId: Long,
        seasonId: Long,
        isMovie: Boolean,
        downloadTarget: DownloadTarget
    ): EnqueueDownloadsResult
    suspend fun enqueueEpisode(seriesId: Long, episodeId: Long, downloadTarget: DownloadTarget): EnqueueDownloadsResult
}

data class DownloadTarget(
    val uri: String,
    val location: MediaStorageLocation
)

sealed interface EnqueueDownloadsResult {
    data object StorageTargetRequired : EnqueueDownloadsResult
    data object NoItemsQueued : EnqueueDownloadsResult
    data object MovieQueued : EnqueueDownloadsResult
    data object EpisodeQueued : EnqueueDownloadsResult
    data class SeasonQueued(val count: Int) : EnqueueDownloadsResult
}

class RoomMediaDetailRepository private constructor(
    private val appContext: Context,
    private val database: AppDatabase
) : MediaDetailRepository {

    override suspend fun getSeries(seriesId: Long): MediaSeriesEntity? =
        database.mediaDao().getSeriesById(seriesId)

    override fun observeSeasons(seriesId: Long): Flow<List<SeasonEntity>> =
        database.mediaDao().getDownloadedSeasonsBySeriesId(seriesId)

    override fun observeEpisodes(seriesId: Long, seasonId: Long): Flow<List<EpisodeWithSource>> =
        database.mediaDao().getEpisodesWithSource(seriesId, seasonId)

    override suspend fun getFirstPlayableEpisode(
        seriesId: Long,
        seasonId: Long,
        isMovie: Boolean
    ): EpisodeEntity? = if (isMovie) {
        database.mediaDao().getFirstDownloadedEpisode(seriesId)
    } else {
        database.mediaDao().getDownloadedEpisodesSync(seriesId, seasonId).firstOrNull()
    }

    override suspend fun isFavorite(seriesId: Long): Boolean =
        database.favoriteDao().getBySeriesId(seriesId) != null

    override suspend fun toggleFavorite(seriesId: Long): Boolean {
        val favorite = database.favoriteDao().getBySeriesId(seriesId)
        if (favorite == null) {
            database.favoriteDao().insert(FavoriteEntity(seriesId = seriesId))
            return true
        }
        database.favoriteDao().delete(favorite)
        return false
    }

    override suspend fun enqueueDownloads(
        seriesId: Long,
        seasonId: Long,
        isMovie: Boolean,
        downloadTarget: DownloadTarget
    ): EnqueueDownloadsResult {
        val episodes = if (isMovie) {
            database.mediaDao().getEpisodesForSeriesSync(seriesId)
        } else {
            database.mediaDao().getEpisodesSync(seriesId, seasonId)
        }
        val queuedCount = enqueueEpisodeList(seriesId, episodes, downloadTarget)
            ?: return EnqueueDownloadsResult.StorageTargetRequired
        if (queuedCount == 0) return EnqueueDownloadsResult.NoItemsQueued
        DownloadWorkScheduler.enqueue(appContext, expedited = true)
        return if (isMovie) EnqueueDownloadsResult.MovieQueued
        else EnqueueDownloadsResult.SeasonQueued(queuedCount)
    }

    override suspend fun enqueueEpisode(
        seriesId: Long,
        episodeId: Long,
        downloadTarget: DownloadTarget
    ): EnqueueDownloadsResult {
        val episode = database.mediaDao().getEpisodeById(episodeId)
            ?.takeIf { it.seriesId == seriesId }
            ?: return EnqueueDownloadsResult.NoItemsQueued
        val queuedCount = enqueueEpisodeList(seriesId, listOf(episode), downloadTarget)
            ?: return EnqueueDownloadsResult.StorageTargetRequired
        if (queuedCount == 0) return EnqueueDownloadsResult.NoItemsQueued
        DownloadWorkScheduler.enqueue(appContext, expedited = true)
        return EnqueueDownloadsResult.EpisodeQueued
    }

    /** 返回 null 表示目标存储已失效，否则返回实际新入队数量。 */
    private suspend fun enqueueEpisodeList(
        seriesId: Long,
        episodes: List<EpisodeEntity>,
        downloadTarget: DownloadTarget
    ): Int? {
        val targetUri = Uri.parse(downloadTarget.uri)
        if (!TvStorageManager(appContext).isStorageTargetAvailable(targetUri)) return null
        val enabledNasIds = database.nasDao().getEnabledSources().mapTo(mutableSetOf()) { it.id }
        var queuedCount = 0
        episodes.forEach { episode ->
            val mediaFile = database.mediaDao().getMediaFileByEpisodeId(episode.id) ?: return@forEach
            val remoteSource = database.mediaDao().getMediaRemoteSources(mediaFile.id)
                .firstOrNull { it.nasSourceId in enabledNasIds }
            if (remoteSource == null) return@forEach
            val targetUriValue = targetUri.toString()
            val existing = database.downloadDao().getTaskByEpisodeIdAndTargetUri(episode.id, targetUriValue)
            if (existing == null || existing.status in setOf(DownloadTaskStatus.FAILED, DownloadTaskStatus.CANCELLED)) {
                database.downloadDao().insertTask(
                    DownloadTaskEntity(
                        id = existing?.id ?: 0L,
                        seriesId = seriesId,
                        seasonId = episode.seasonId,
                        episodeId = episode.id,
                        sourceUri = remoteSource.uri,
                        sourceNasId = remoteSource.nasSourceId ?: 0L,
                        targetUri = targetUriValue,
                        targetStorageType = downloadTarget.location.name,
                        status = DownloadTaskStatus.WAITING
                    )
                )
                queuedCount++
            }
        }
        return queuedCount
    }

    companion object {
        fun create(context: Context): RoomMediaDetailRepository {
            val appContext = context.applicationContext
            return RoomMediaDetailRepository(appContext, AppDatabase.getInstance(appContext))
        }
    }
}
