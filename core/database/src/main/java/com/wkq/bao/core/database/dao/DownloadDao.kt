package com.wkq.bao.core.database.dao

import androidx.room.*
import com.wkq.bao.core.database.entity.DownloadTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM download_tasks ORDER BY createdAt DESC")
    fun getAllTasks(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE status = :status ORDER BY createdAt ASC")
    suspend fun getTasksByStatus(status: String): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_tasks WHERE episodeId = :episodeId LIMIT 1")
    suspend fun getTaskByEpisodeId(episodeId: Long): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE id = :id LIMIT 1")
    suspend fun getTaskById(id: Long): DownloadTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: DownloadTaskEntity): Long

    @Update
    suspend fun updateTask(task: DownloadTaskEntity)

    @Delete
    suspend fun deleteTask(task: DownloadTaskEntity)
}
