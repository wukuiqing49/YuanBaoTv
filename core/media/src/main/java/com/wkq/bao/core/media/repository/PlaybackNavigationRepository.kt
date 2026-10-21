package com.wkq.bao.core.media.repository

import android.content.Context
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.database.entity.EpisodeEntity

/** 播放页使用的剧集定位与连续播放编排能力。 */
interface PlaybackNavigationRepository {
    suspend fun resolveSeasonId(episodeId: Long): Long
    suspend fun findNextEpisode(seriesId: Long, episodeId: Long): EpisodeEntity?
    suspend fun hasEpisodeGap(currentId: Long, next: EpisodeEntity): Boolean = false
    suspend fun isMovie(seriesId: Long): Boolean = false
    suspend fun episodeTitle(episodeId: Long): String = ""
}

class RoomPlaybackNavigationRepository private constructor(
    private val database: AppDatabase
) : PlaybackNavigationRepository {

    override suspend fun resolveSeasonId(episodeId: Long): Long =
        database.mediaDao().getEpisodeById(episodeId)?.seasonId ?: 0L

    override suspend fun hasEpisodeGap(currentId: Long, next: EpisodeEntity): Boolean {
        val dao = database.mediaDao()
        val current = dao.getEpisodeById(currentId) ?: return true
        if (current.seasonId == next.seasonId) return next.episodeNumber != current.episodeNumber + 1
        val seasons = dao.getSeasonsSync(current.seriesId)
        val currentSeason = seasons.firstOrNull { it.id == current.seasonId } ?: return true
        val nextSeason = seasons.firstOrNull { it.id == next.seasonId } ?: return true
        return nextSeason.seasonNumber != currentSeason.seasonNumber + 1 || next.episodeNumber != 1
    }

    override suspend fun isMovie(seriesId: Long): Boolean =
        database.mediaDao().getSeriesById(seriesId)?.type == "MOVIE"

    override suspend fun episodeTitle(episodeId: Long): String {
        val dao = database.mediaDao()
        val episode = dao.getEpisodeById(episodeId) ?: return ""
        val series = dao.getSeriesById(episode.seriesId) ?: return episode.title
        if (series.type == "MOVIE") return series.title
        val season = dao.getSeasonsSync(series.id).firstOrNull { it.id == episode.seasonId }
        return "${series.title} · S${(season?.seasonNumber ?: 1).toString().padStart(2, '0')}E${episode.episodeNumber.toString().padStart(2, '0')}" +
            episode.title.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
    }

    override suspend fun findNextEpisode(seriesId: Long, episodeId: Long): EpisodeEntity? {
        if (isMovie(seriesId)) return null
        val mediaDao = database.mediaDao()
        val current = mediaDao.getEpisodeById(episodeId)
            ?.takeIf { it.seriesId == seriesId }
            ?: return null
        mediaDao.getPlayableEpisodesSync(seriesId, current.seasonId)
            .firstOrNull { it.episodeNumber > current.episodeNumber }
            ?.takeIf { it.seriesId == seriesId }
            ?.let { return it }

        val seasons = mediaDao.getSeasonsSync(seriesId)
        val currentSeasonIndex = seasons.indexOfFirst { it.id == current.seasonId }
        if (currentSeasonIndex < 0) return null
        for (season in seasons.drop(currentSeasonIndex + 1)) {
            mediaDao.getPlayableEpisodesSync(seriesId, season.id).firstOrNull()?.let { return it }
        }
        return null
    }

    companion object {
        fun create(context: Context): RoomPlaybackNavigationRepository =
            RoomPlaybackNavigationRepository(AppDatabase.getInstance(context.applicationContext))
    }
}
