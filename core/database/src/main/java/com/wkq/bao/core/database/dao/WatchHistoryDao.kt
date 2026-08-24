package com.wkq.bao.core.database.dao

import androidx.room.*
import com.wkq.bao.core.database.entity.ContinueWatchingItem
import com.wkq.bao.core.database.entity.WatchHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchHistoryDao {
    @Query("""
        SELECT watch_histories.*, media_series.title AS seriesTitle, episodes.title AS episodeTitle
        FROM watch_histories
        INNER JOIN media_series ON media_series.id = watch_histories.seriesId
        INNER JOIN episodes ON episodes.id = watch_histories.episodeId
        WHERE watch_histories.completed = 0 AND watch_histories.positionMs > 120000
        ORDER BY watch_histories.lastPlayedAt DESC
        LIMIT 20
    """)
    fun getContinueWatchingList(): Flow<List<ContinueWatchingItem>>

    @Query("SELECT * FROM watch_histories WHERE episodeId = :episodeId LIMIT 1")
    suspend fun getHistoryByEpisodeId(episodeId: Long): WatchHistoryEntity?

    @Query("SELECT * FROM watch_histories WHERE seriesId = :seriesId ORDER BY lastPlayedAt DESC LIMIT 1")
    suspend fun getLastPlayedForSeries(seriesId: Long): WatchHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveHistory(history: WatchHistoryEntity): Long

    @Query("DELETE FROM watch_histories WHERE episodeId = :episodeId")
    suspend fun deleteHistory(episodeId: Long)
}
