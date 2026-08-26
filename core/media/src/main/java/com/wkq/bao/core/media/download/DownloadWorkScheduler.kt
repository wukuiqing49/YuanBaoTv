package com.wkq.bao.core.media.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** 以唯一 Work 持久化调度下载队列，应用或前台服务被回收后仍可恢复。 */
object DownloadWorkScheduler {
    // v1 使用追加链，异常链可能让 Room 中的 WAITING 任务失去执行者；更名可安全脱离旧链。
    private const val UNIQUE_WORK_NAME = "yuanbao_download_queue_v2"

    fun enqueue(context: Context, expedited: Boolean = false) {
        val requestBuilder = OneTimeWorkRequestBuilder<DownloadQueueWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(UNIQUE_WORK_NAME)
        if (expedited) {
            requestBuilder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        WorkManager.getInstance(context.applicationContext)
            // 队列内容已持久化在 Room，运行中的 Worker 会继续读取新增任务；无需中断当前分块。
            .enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                requestBuilder.build()
            )
    }
}
