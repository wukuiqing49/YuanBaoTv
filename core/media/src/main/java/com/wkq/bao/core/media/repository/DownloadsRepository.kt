package com.wkq.bao.core.media.repository

import android.content.Context
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.database.entity.DownloadTaskEntity
import com.wkq.bao.core.database.entity.DownloadTaskStatus
import com.wkq.bao.core.database.entity.MediaSeriesEntity
import com.wkq.bao.core.media.download.DownloadWorkScheduler
import kotlinx.coroutines.flow.Flow

interface DownloadsRepository {
    val tasks: Flow<List<DownloadTaskEntity>>
    val downloadedSeries: Flow<List<MediaSeriesEntity>>

    suspend fun togglePauseResume(taskId: Long)
    suspend fun cancel(taskId: Long)
}

class RoomDownloadsRepository private constructor(
    private val appContext: Context,
    private val database: AppDatabase
) : DownloadsRepository {

    override val tasks: Flow<List<DownloadTaskEntity>> = database.downloadDao().getAllTasks()
    override val downloadedSeries: Flow<List<MediaSeriesEntity>> = database.mediaDao().getDownloadedSeries()

    override suspend fun togglePauseResume(taskId: Long) {
        val task = database.downloadDao().getTaskById(taskId) ?: return
        val nextStatus = DownloadTaskAction.nextToggleStatus(task.status) ?: return
        database.downloadDao().updateTask(task.copy(status = nextStatus))
        if (nextStatus == DownloadTaskStatus.WAITING) {
            DownloadWorkScheduler.enqueue(appContext, expedited = true)
        }
    }

    override suspend fun cancel(taskId: Long) {
        val task = database.downloadDao().getTaskById(taskId) ?: return
        if (task.status in setOf(DownloadTaskStatus.SUCCESS, DownloadTaskStatus.CANCELLED)) return
        database.downloadDao().updateTask(task.copy(status = DownloadTaskStatus.CANCELLED))
    }

    companion object {
        fun create(context: Context): RoomDownloadsRepository {
            val appContext = context.applicationContext
            return RoomDownloadsRepository(appContext, AppDatabase.getInstance(appContext))
        }
    }
}

object DownloadTaskAction {
    fun nextToggleStatus(status: String): String? = when (status) {
        DownloadTaskStatus.PAUSED,
        DownloadTaskStatus.FAILED -> DownloadTaskStatus.WAITING

        DownloadTaskStatus.WAITING,
        DownloadTaskStatus.DOWNLOADING -> DownloadTaskStatus.PAUSED

        else -> null
    }
}
