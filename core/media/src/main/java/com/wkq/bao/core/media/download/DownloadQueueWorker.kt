package com.wkq.bao.core.media.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.wkq.bao.core.base.diagnostics.AppDiagnostics
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.database.entity.DownloadTaskErrorCode
import com.wkq.bao.core.database.entity.DownloadTaskStatus
import com.wkq.bao.core.media.scanner.LocalMediaScanController
import kotlinx.coroutines.CancellationException

/** 前台执行串行下载队列，WorkManager 负责重启、网络约束和退避。 */
class DownloadQueueWorker(
    appContext: Context,
    parameters: WorkerParameters
) : CoroutineWorker(appContext, parameters) {
    private val database = AppDatabase.getInstance(appContext)
    private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo(0)

    override suspend fun doWork(): Result {
        AppDiagnostics.record(applicationContext, "download", "queue_started")
        val completedRawStorageTrees = linkedSetOf<Uri>()
        database.downloadDao().requeueInterruptedTasks()
        if (database.downloadDao().getNextTaskByStatus(DownloadTaskStatus.WAITING) == null) {
            AppDiagnostics.record(applicationContext, "download", "queue_finished")
            return Result.success()
        }
        setForeground(createForegroundInfo(0))
        while (true) {
            val task = database.downloadDao().getNextTaskByStatus(DownloadTaskStatus.WAITING)
                ?: run {
                    enqueueLocalIndexing(completedRawStorageTrees)
                    AppDiagnostics.record(applicationContext, "download", "queue_finished")
                    return Result.success()
                }
            try {
                TvDownloadEngine(applicationContext, database).executeTask(task) { progress ->
                    notificationManager.notify(NOTIFICATION_ID, createNotification(progress))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
            val latest = database.downloadDao().getTaskById(task.id) ?: continue
            if (task.episodeId < 0L && latest.status == DownloadTaskStatus.SUCCESS) {
                latest.targetUri.takeIf(String::isNotBlank)?.let(Uri::parse)?.let(completedRawStorageTrees::add)
            }
            if (latest.status == DownloadTaskStatus.FAILED && DownloadTaskErrorCode.isRetryable(latest.errorCode)) {
                AppDiagnostics.record(applicationContext, "download", "retry_${latest.errorCode.lowercase()}")
                if (runAttemptCount >= MAX_RETRY_ATTEMPTS) return Result.failure()
                database.downloadDao().updateTask(latest.copy(status = DownloadTaskStatus.WAITING))
                return Result.retry()
            }
            if (latest.status == DownloadTaskStatus.FAILED) {
                AppDiagnostics.record(applicationContext, "download", "failed_${latest.errorCode.lowercase()}")
            }
        }
    }

    private suspend fun enqueueLocalIndexing(storageTrees: Set<Uri>) {
        if (storageTrees.isEmpty()) return
        val scanController = LocalMediaScanController(applicationContext)
        storageTrees.forEach { treeUri ->
            runCatching { scanController.enqueue(treeUri) }
                .onSuccess { AppDiagnostics.record(applicationContext, "download", "local_index_enqueued") }
                .onFailure { AppDiagnostics.record(applicationContext, "download", "local_index_enqueue_failed") }
        }
    }

    private fun createForegroundInfo(progress: Int): ForegroundInfo = ForegroundInfo(
        NOTIFICATION_ID,
        createNotification(progress),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    )

    private fun createNotification(progress: Int): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = applicationContext.applicationInfo
                .loadLabel(applicationContext.packageManager)
                .toString()
            val channel = NotificationChannel(CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }
        val label = applicationContext.applicationInfo.loadLabel(applicationContext.packageManager).toString()
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(label)
            .setContentText("$progress%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "yuanbao_work_download"
        const val NOTIFICATION_ID = 1002
        const val MAX_RETRY_ATTEMPTS = 5
    }
}
