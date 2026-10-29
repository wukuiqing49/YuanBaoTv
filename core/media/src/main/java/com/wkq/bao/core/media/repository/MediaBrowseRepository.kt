package com.wkq.bao.core.media.repository

import android.content.Context
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.database.entity.ContinueWatchingItem
import com.wkq.bao.core.database.entity.EpisodeEntity
import com.wkq.bao.core.database.entity.MediaSeriesEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import com.wkq.bao.core.database.MediaLibraryQuery
import com.wkq.bao.core.database.MediaLibraryPage

/** 首页和媒体库共享的只读媒体浏览能力。 */
interface MediaBrowseRepository {
    val continueWatching: Flow<List<ContinueWatchingItem>>
    val allSeries: Flow<List<MediaSeriesEntity>>
    val favoriteSeriesIds: Flow<Set<Long>> get() = flowOf(emptySet())
    val offlineSeriesIds: Flow<Set<Long>> get() = flowOf(emptySet())

    fun observeLibrary(query: MediaLibraryQuery, limit: Int): Flow<MediaLibraryPage> =
        combine(allSeries, favoriteSeriesIds) { items, favorites ->
            val matching = items.filter { item ->
                (query.type == null || item.type == query.type) && (!query.favoritesOnly || item.id in favorites) &&
                    (query.text.isBlank() || item.title.contains(query.text.trim(), true) || item.originalTitle.contains(query.text.trim(), true))
            }
            val sorted = when (query.sort) {
                "TITLE" -> matching.sortedWith(compareBy<MediaSeriesEntity> { it.title.lowercase(java.util.Locale.ROOT) }.thenBy { it.id })
                "YEAR" -> matching.sortedWith(compareByDescending<MediaSeriesEntity> { it.year.toIntOrNull() ?: 0 }.thenBy { it.title }.thenBy { it.id })
                else -> matching.sortedWith(compareByDescending<MediaSeriesEntity> { it.createdAt }.thenBy { it.id })
            }
            MediaLibraryPage(sorted.take(limit), sorted.size)
        }

    fun observeSeriesByType(type: String?): Flow<List<MediaSeriesEntity>>
    suspend fun getFirstEpisode(seriesId: Long): EpisodeEntity?
    suspend fun getPlaybackEpisode(seriesId: Long): EpisodeEntity? = getFirstEpisode(seriesId)
    suspend fun getPlayableEpisode(seriesId: Long, episodeId: Long): EpisodeEntity? = null
}

class RoomMediaBrowseRepository private constructor(
    private val database: AppDatabase,
    context: Context
) : MediaBrowseRepository {
    override val offlineSeriesIds = com.wkq.bao.core.media.storage.StorageAvailabilityObserver(context, database.mediaDao()).offlineSeries

    override val continueWatching: Flow<List<ContinueWatchingItem>> =
        database.watchHistoryDao().getContinueWatchingList().map { items ->
            items.mapNotNull { item ->
                val target = PlaybackSelection(database).select(item.history.seriesId) ?: return@mapNotNull null
                if (item.history.completed && target.episode.id == item.history.episodeId) return@mapNotNull null
                val history = if (target.episode.id == item.history.episodeId) item.history else
                    item.history.copy(episodeId = target.episode.id, seasonId = target.episode.seasonId,
                        positionMs = 0, durationMs = 0, completed = false)
                val title = if (target.isMovie) "" else
                    "S${target.seasonNumber.toString().padStart(2, '0')}E${target.episode.episodeNumber.toString().padStart(2, '0')}"
                item.copy(history = history, episodeTitle = title)
            }
        }
    override val allSeries: Flow<List<MediaSeriesEntity>> = database.mediaDao().getPlayableSeries()
    override val favoriteSeriesIds: Flow<Set<Long>> = database.favoriteDao().getFavoriteSeries()
        .map { series -> series.mapTo(mutableSetOf()) { it.id } }

    override fun observeLibrary(query: MediaLibraryQuery, limit: Int): Flow<MediaLibraryPage> =
        database.mediaDao().observeLibraryChanges(androidx.sqlite.db.SimpleSQLiteQuery("SELECT 1"))
            .map { database.mediaDao().loadLibraryPage(query, limit) }

    override fun observeSeriesByType(type: String?): Flow<List<MediaSeriesEntity>> =
        type?.let(database.mediaDao()::getPlayableSeriesByType) ?: database.mediaDao().getPlayableSeries()

    override suspend fun getFirstEpisode(seriesId: Long): EpisodeEntity? =
        database.mediaDao().getFirstPlayableEpisode(seriesId)

    override suspend fun getPlaybackEpisode(seriesId: Long): EpisodeEntity? =
        PlaybackSelection(database).select(seriesId)?.episode

    override suspend fun getPlayableEpisode(seriesId: Long, episodeId: Long): EpisodeEntity? {
        val episode = database.mediaDao().getEpisodeById(episodeId)?.takeIf { it.seriesId == seriesId }
            ?: return null
        return database.mediaDao().getPlayableEpisodesSync(seriesId, episode.seasonId)
            .firstOrNull { it.id == episodeId }
    }

    companion object {
        fun create(context: Context): RoomMediaBrowseRepository =
            RoomMediaBrowseRepository(AppDatabase.getInstance(context.applicationContext), context.applicationContext)
    }
}
