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

    /** 从持久化完成记录恢复待入库目录，扫描开始前已完成的文件由该次扫描覆盖。 */
    @Query("""
        SELECT DISTINCT task.targetUri FROM download_tasks task
        WHERE task.episodeId < 0 AND task.status = 'SUCCESS' AND task.targetUri != ''
          AND NOT EXISTS (
              SELECT 1 FROM scan_sessions scan
              WHERE scan.kind = 'LOCAL' AND scan.sourceKey = task.targetUri
                AND scan.status = 'SUCCEEDED' AND scan.startedAt >= task.finishedAt
          )
    """)
    suspend fun getRawDownloadTreesPendingIndex(): List<String>

    @Query("""
        SELECT MAX(finishedAt) FROM download_tasks
        WHERE episodeId < 0 AND status = 'SUCCESS' AND targetUri = :treeUri
    """)
    suspend fun getLatestRawDownloadCompletion(treeUri: String): Long?

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

    @Query("UPDATE download_tasks SET status = :nextStatus WHERE id = :id AND status = :expectedStatus")
    suspend fun transitionTaskStatus(id: Long, expectedStatus: String, nextStatus: String): Int

    @Query("UPDATE download_tasks SET status = 'CANCELLED' WHERE id = :id AND status NOT IN ('SUCCESS', 'CANCELLED')")
    suspend fun cancelPendingTask(id: Long): Int

    @Query("UPDATE download_tasks SET downloadedBytes = :downloadedBytes, totalBytes = :totalBytes, sourceLastModifiedAt = :modifiedAt WHERE id = :id AND status = 'DOWNLOADING'")
    suspend fun updateProgressWhileDownloading(id: Long, downloadedBytes: Long, totalBytes: Long, modifiedAt: Long): Int

    @Query("UPDATE download_tasks SET assembledBytes = :assembledBytes WHERE id = :id AND status = 'DOWNLOADING'")
    suspend fun updateAssembledBytesWhileDownloading(id: Long, assembledBytes: Long): Int

    @Query("UPDATE download_tasks SET status = 'FAILED', errorMessage = :message, errorCode = :errorCode WHERE id = :id AND status = 'DOWNLOADING'")
    suspend fun failDownloadingTask(id: Long, message: String, errorCode: String): Int

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
