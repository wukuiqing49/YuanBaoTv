package com.wkq.bao.core.media.storage

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.database.entity.DownloadTaskStatus
import com.wkq.bao.core.media.download.DownloadWorkScheduler
import com.wkq.bao.core.media.scanner.LocalMediaScanController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 挂载、拔出和回到应用时重新核对实际存储，绝不把原任务切到当前默认设备。 */
class StorageReconnectWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val storage = TvStorageManager(applicationContext)
        val libraries = MediaLibraryStorage(applicationContext)
        val database = AppDatabase.getInstance(applicationContext)
        val dao = database.downloadDao()
        var resumed = false
        dao.getTasksByStatuses(listOf(DownloadTaskStatus.WAITING, DownloadTaskStatus.DOWNLOADING,
            DownloadTaskStatus.WAITING_STORAGE)).forEach { task ->
            if (task.targetUri.isBlank()) return@forEach
            val tree = Uri.parse(task.targetUri)
            val present = storage.isStorageTargetAvailable(tree)
            if (!present && task.status != DownloadTaskStatus.WAITING_STORAGE) {
                dao.transitionTaskStatus(task.id, task.status, DownloadTaskStatus.WAITING_STORAGE)
            } else if (present && task.status == DownloadTaskStatus.WAITING_STORAGE &&
                task.targetLibraryId.isNotBlank() && libraries.matches(tree, task.targetLibraryId) &&
                storage.isStorageTargetWritable(tree)) {
                resumed = dao.transitionTaskStatus(task.id, DownloadTaskStatus.WAITING_STORAGE, DownloadTaskStatus.WAITING) > 0 || resumed
            }
        }
        val scans = LocalMediaScanController(applicationContext)
        storage.getStorageTargets().forEach { target ->
            if (!target.isAvailable) scans.cancel(target.uri)
            else {
                val id = libraries.expectedId(target.uri)
                if (id.isBlank() || libraries.matches(target.uri, id)) scans.enqueue(target.uri)
            }
        }
        // 回到应用时也修复仍在排队但此前调度失败的任务。
        if (resumed || dao.getNextTaskByStatus(DownloadTaskStatus.WAITING) != null) {
            DownloadWorkScheduler.enqueue(applicationContext)
        }
        Result.success()
    }

    companion object {
        fun enqueue(context: Context, delaySeconds: Long = 0) {
            val builder = OneTimeWorkRequestBuilder<StorageReconnectWorker>()
            if (delaySeconds > 0) {
                builder.setInitialDelay(delaySeconds, java.util.concurrent.TimeUnit.SECONDS)
            }
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "storage_reconnect", ExistingWorkPolicy.KEEP,
                builder.build()
            )
        }
    }
}
