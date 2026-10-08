package com.wkq.bao.core.media.repository

import android.content.Context
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.database.entity.ContinueWatchingItem
import com.wkq.bao.core.database.entity.EpisodeEntity
import com.wkq.bao.core.database.entity.MediaSeriesEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** 首页和媒体库共享的只读媒体浏览能力。 */
interface MediaBrowseRepository {
    val continueWatching: Flow<List<ContinueWatchingItem>>
    val allSeries: Flow<List<MediaSeriesEntity>>
    val favoriteSeriesIds: Flow<Set<Long>> get() = flowOf(emptySet())

    fun observeSeriesByType(type: String?): Flow<List<MediaSeriesEntity>>
    suspend fun getFirstEpisode(seriesId: Long): EpisodeEntity?
}

class RoomMediaBrowseRepository private constructor(
    private val database: AppDatabase
) : MediaBrowseRepository {

    override val continueWatching: Flow<List<ContinueWatchingItem>> =
        database.watchHistoryDao().getContinueWatchingList()
    override val allSeries: Flow<List<MediaSeriesEntity>> = database.mediaDao().getPlayableSeries()
    override val favoriteSeriesIds: Flow<Set<Long>> = database.favoriteDao().getFavoriteSeries()
        .map { series -> series.mapTo(mutableSetOf()) { it.id } }

    override fun observeSeriesByType(type: String?): Flow<List<MediaSeriesEntity>> =
        type?.let(database.mediaDao()::getPlayableSeriesByType) ?: database.mediaDao().getPlayableSeries()

    override suspend fun getFirstEpisode(seriesId: Long): EpisodeEntity? =
        database.mediaDao().getFirstPlayableEpisode(seriesId)

    companion object {
        fun create(context: Context): RoomMediaBrowseRepository =
            RoomMediaBrowseRepository(AppDatabase.getInstance(context.applicationContext))
    }
}
