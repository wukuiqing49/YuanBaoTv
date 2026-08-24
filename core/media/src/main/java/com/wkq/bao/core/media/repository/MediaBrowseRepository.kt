package com.wkq.bao.core.media.repository

import android.content.Context
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.database.entity.ContinueWatchingItem
import com.wkq.bao.core.database.entity.EpisodeEntity
import com.wkq.bao.core.database.entity.MediaSeriesEntity
import kotlinx.coroutines.flow.Flow

/** 首页和媒体库共享的只读媒体浏览能力。 */
interface MediaBrowseRepository {
    val continueWatching: Flow<List<ContinueWatchingItem>>
    val allSeries: Flow<List<MediaSeriesEntity>>

    fun observeSeriesByType(type: String?): Flow<List<MediaSeriesEntity>>
    suspend fun getFirstEpisode(seriesId: Long): EpisodeEntity?
}

class RoomMediaBrowseRepository private constructor(
    private val database: AppDatabase
) : MediaBrowseRepository {

    override val continueWatching: Flow<List<ContinueWatchingItem>> =
        database.watchHistoryDao().getContinueWatchingList()
    override val allSeries: Flow<List<MediaSeriesEntity>> = database.mediaDao().getAllSeries()

    override fun observeSeriesByType(type: String?): Flow<List<MediaSeriesEntity>> =
        type?.let(database.mediaDao()::getSeriesByType) ?: database.mediaDao().getAllSeries()

    override suspend fun getFirstEpisode(seriesId: Long): EpisodeEntity? =
        database.mediaDao().getFirstEpisode(seriesId)

    companion object {
        fun create(context: Context): RoomMediaBrowseRepository =
            RoomMediaBrowseRepository(AppDatabase.getInstance(context.applicationContext))
    }
}
