package com.wkq.bao.core.media.repository

import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.database.entity.EpisodeEntity

data class PlaybackTarget(val episode: EpisodeEntity, val resume: Boolean = false, val seasonNumber: Int = 1)

/** 所有入口共用的续播规则，不把数据库自增 ID 当成季序。 */
internal class PlaybackSelection(private val database: AppDatabase) {
    suspend fun select(seriesId: Long, seasonId: Long = 0): PlaybackTarget? {
        val dao = database.mediaDao()
        val series = dao.getSeriesById(seriesId) ?: return null
        val seasons = dao.getSeasonsSync(seriesId)
        val episodes = seasons.filter { seasonId == 0L || it.id == seasonId }
            .flatMap { dao.getPlayableEpisodesSync(seriesId, it.id) }
        if (episodes.isEmpty()) return null
        val history = database.watchHistoryDao().getLastPlayedForSeries(seriesId)
        return choosePlaybackTarget(episodes, seasons, history, series.type == "MOVIE")
    }
}

/** 已按季号、集号排序的可播序列；纯函数用于验证续播边界。 */
internal fun choosePlaybackTarget(
    episodes: List<EpisodeEntity>,
    seasons: List<com.wkq.bao.core.database.entity.SeasonEntity>,
    history: com.wkq.bao.core.database.entity.WatchHistoryEntity?,
    isMovie: Boolean
): PlaybackTarget? {
        if (episodes.isEmpty()) return null
        val seasonNumbers = seasons.associate { it.id to it.seasonNumber }
        val ordered = episodes.sortedWith(compareBy<EpisodeEntity> { seasonNumbers[it.seasonId] ?: Int.MAX_VALUE }
            .thenBy { it.episodeNumber }.thenBy { it.id })
        val previous = history?.let { h -> ordered.firstOrNull { it.id == h.episodeId } }
        val selected = when {
            previous != null && !history.completed -> previous
            previous != null && !isMovie -> ordered.dropWhile { it.id != previous.id }.drop(1).firstOrNull() ?: previous
            else -> ordered.first()
        }
        return PlaybackTarget(selected, history?.episodeId == selected.id && !history.completed && history.positionMs > 5000,
            seasons.firstOrNull { it.id == selected.seasonId }?.seasonNumber ?: 1)
}
