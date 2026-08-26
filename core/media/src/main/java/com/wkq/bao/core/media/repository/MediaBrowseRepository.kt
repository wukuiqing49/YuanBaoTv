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
    // 首页与媒体库只展示拥有可播放本地副本的内容，NAS 远程索引仅服务下载来源选择。
    override val allSeries: Flow<List<MediaSeriesEntity>> = database.mediaDao().getDownloadedSeries()

    override fun observeSeriesByType(type: String?): Flow<List<MediaSeriesEntity>> =
        type?.let(database.mediaDao()::getDownloadedSeriesByType) ?: database.mediaDao().getDownloadedSeries()

    override suspend fun getFirstEpisode(seriesId: Long): EpisodeEntity? =
        database.mediaDao().getFirstDownloadedEpisode(seriesId)

    companion object {
        fun create(context: Context): RoomMediaBrowseRepository =
            RoomMediaBrowseRepository(AppDatabase.getInstance(context.applicationContext))
    }
}
