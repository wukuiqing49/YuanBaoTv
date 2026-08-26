package com.wkq.bao.core.media.repository

import android.content.Context
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.database.entity.EpisodeEntity

/** 播放页使用的剧集定位与连续播放编排能力。 */
interface PlaybackNavigationRepository {
    suspend fun resolveSeasonId(episodeId: Long): Long
    suspend fun findNextEpisode(seriesId: Long, episodeId: Long): EpisodeEntity?
}

class RoomPlaybackNavigationRepository private constructor(
    private val database: AppDatabase
) : PlaybackNavigationRepository {

    override suspend fun resolveSeasonId(episodeId: Long): Long =
        database.mediaDao().getEpisodeById(episodeId)?.seasonId ?: 0L

    override suspend fun findNextEpisode(seriesId: Long, episodeId: Long): EpisodeEntity? {
        val mediaDao = database.mediaDao()
        val current = mediaDao.getEpisodeById(episodeId)
            ?.takeIf { it.seriesId == seriesId }
            ?: return null
        mediaDao.getDownloadedEpisodeByNumber(current.seasonId, current.episodeNumber + 1)
            ?.takeIf { it.seriesId == seriesId }
            ?.let { return it }

        val seasons = mediaDao.getSeasonsSync(seriesId)
        val currentSeasonIndex = seasons.indexOfFirst { it.id == current.seasonId }
        if (currentSeasonIndex < 0) return null
        for (season in seasons.drop(currentSeasonIndex + 1)) {
            mediaDao.getDownloadedEpisodesSync(seriesId, season.id).firstOrNull()?.let { return it }
        }
        return null
    }

    companion object {
        fun create(context: Context): RoomPlaybackNavigationRepository =
            RoomPlaybackNavigationRepository(AppDatabase.getInstance(context.applicationContext))
    }
}
