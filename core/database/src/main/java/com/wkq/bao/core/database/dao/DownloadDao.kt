package com.wkq.bao.core.database.dao

import androidx.room.*
import com.wkq.bao.core.database.entity.DownloadChunkEntity
import com.wkq.bao.core.database.entity.DownloadTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM download_tasks ORDER BY createdAt DESC")
    fun getAllTasks(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE status = :status ORDER BY createdAt ASC")
    suspend fun getTasksByStatus(status: String): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_tasks WHERE status = :status ORDER BY createdAt ASC LIMIT 1")
    suspend fun getNextTaskByStatus(status: String): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE status IN (:statuses) ORDER BY createdAt ASC")
    suspend fun getTasksByStatuses(statuses: List<String>): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_tasks WHERE episodeId = :episodeId AND targetUri = :targetUri LIMIT 1")
    suspend fun getTaskByEpisodeIdAndTargetUri(episodeId: Long, targetUri: String): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE id = :id LIMIT 1")
    suspend fun getTaskById(id: Long): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE sourceNasId = :sourceNasId ORDER BY createdAt ASC")
    suspend fun getTasksBySourceNasId(sourceNasId: Long): List<DownloadTaskEntity>

    @Query("UPDATE download_tasks SET status = :cancelled WHERE sourceNasId = :sourceNasId AND status IN (:activeStatuses)")
    suspend fun cancelActiveTasksBySourceNasId(
        sourceNasId: Long,
        activeStatuses: List<String>,
        cancelled: String = "CANCELLED"
    ): Int

    @Query("DELETE FROM download_tasks WHERE sourceNasId = :sourceNasId")
    suspend fun deleteTasksBySourceNasId(sourceNasId: Long): Int

    @Query("UPDATE download_tasks SET status = :downloading, errorMessage = '', errorCode = '' WHERE id = :id AND status = :waiting")
    suspend fun claimWaitingTask(id: Long, waiting: String = "WAITING", downloading: String = "DOWNLOADING"): Int

    @Query("UPDATE download_tasks SET status = :waiting WHERE status = :downloading")
    suspend fun requeueInterruptedTasks(waiting: String = "WAITING", downloading: String = "DOWNLOADING"): Int

    @Query("SELECT * FROM download_chunks WHERE taskId = :taskId ORDER BY chunkIndex ASC")
    suspend fun getChunks(taskId: Long): List<DownloadChunkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<DownloadChunkEntity>)

    @Update
    suspend fun updateChunk(chunk: DownloadChunkEntity)

    @Query("DELETE FROM download_chunks WHERE taskId = :taskId")
    suspend fun deleteChunks(taskId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: DownloadTaskEntity): Long

    @Update
    suspend fun updateTask(task: DownloadTaskEntity)

    @Delete
    suspend fun deleteTask(task: DownloadTaskEntity)
}
