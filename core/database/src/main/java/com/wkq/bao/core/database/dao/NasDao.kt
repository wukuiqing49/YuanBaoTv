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

    /**
     * 播放服务在进程重建后按 smb://host/share 回查来源；只允许已启用的 SMB 配置参与解析。
     */
    @Query(
        "SELECT * FROM nas_sources " +
            "WHERE enabled = 1 AND type = 'SMB' " +
            "AND LOWER(host) = LOWER(:host) " +
            "AND LOWER(TRIM(shareName, '/')) = LOWER(TRIM(:shareName, '/')) " +
            "LIMIT 1"
    )
    fun getEnabledSmbSourceByAddress(host: String, shareName: String): NasSourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSource(source: NasSourceEntity): Long

    @Update
    suspend fun updateSource(source: NasSourceEntity)

    @Delete
    suspend fun deleteSource(source: NasSourceEntity)
}
