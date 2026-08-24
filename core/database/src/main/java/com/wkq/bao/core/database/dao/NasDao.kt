package com.wkq.bao.core.database.dao

import androidx.room.*
import com.wkq.bao.core.database.entity.NasSourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NasDao {
    @Query("SELECT * FROM nas_sources ORDER BY id ASC")
    fun getAllSources(): Flow<List<NasSourceEntity>>

    @Query("SELECT * FROM nas_sources WHERE enabled = 1")
    suspend fun getEnabledSources(): List<NasSourceEntity>

    @Query("SELECT * FROM nas_sources")
    suspend fun getAllSourcesSync(): List<NasSourceEntity>

    @Query("SELECT * FROM nas_sources WHERE id = :id LIMIT 1")
    suspend fun getSourceById(id: Long): NasSourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSource(source: NasSourceEntity): Long

    @Update
    suspend fun updateSource(source: NasSourceEntity)

    @Delete
    suspend fun deleteSource(source: NasSourceEntity)
}
