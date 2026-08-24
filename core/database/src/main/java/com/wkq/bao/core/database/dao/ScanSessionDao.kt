package com.wkq.bao.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.wkq.bao.core.database.entity.ScanSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanSessionDao {
    @Query("SELECT * FROM scan_sessions WHERE kind = :kind AND sourceKey = :sourceKey LIMIT 1")
    fun observe(kind: String, sourceKey: String): Flow<ScanSessionEntity?>

    @Query("SELECT * FROM scan_sessions WHERE kind = :kind AND sourceKey = :sourceKey LIMIT 1")
    suspend fun get(kind: String, sourceKey: String): ScanSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: ScanSessionEntity)
}
