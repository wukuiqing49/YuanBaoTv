package com.wkq.bao.core.database.dao

import androidx.room.*
import com.wkq.bao.core.database.entity.WatchHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchHistoryDao {
    @Query("SELECT * FROM watch_histories WHERE completed = 0 AND positionMs > 120000 ORDER BY lastPlayedAt DESC LIMIT 20")
    fun getContinueWatchingList(): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_histories WHERE episodeId = :episodeId LIMIT 1")
    suspend fun getHistoryByEpisodeId(episodeId: Long): WatchHistoryEntity?

    @Query("SELECT * FROM watch_histories WHERE seriesId = :seriesId ORDER BY lastPlayedAt DESC LIMIT 1")
    suspend fun getLastPlayedForSeries(seriesId: Long): WatchHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveHistory(history: WatchHistoryEntity): Long

    @Query("DELETE FROM watch_histories WHERE episodeId = :episodeId")
    suspend fun deleteHistory(episodeId: Long)
}
