package com.wkq.bao.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.wkq.bao.core.database.entity.FavoriteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites WHERE seriesId = :seriesId LIMIT 1")
    suspend fun getBySeriesId(seriesId: Long): FavoriteEntity?

    @Query("SELECT media_series.* FROM media_series INNER JOIN favorites ON favorites.seriesId = media_series.id ORDER BY favorites.createdAt DESC")
    fun getFavoriteSeries(): Flow<List<com.wkq.bao.core.database.entity.MediaSeriesEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(favorite: FavoriteEntity): Long

    @Delete
    suspend fun delete(favorite: FavoriteEntity)
}
