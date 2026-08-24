package com.wkq.bao.core.media.download

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * 旧下载服务兼容入口。实际下载统一由 WorkManager 前台任务执行。
 */
@Deprecated("使用 DownloadWorkScheduler，后台下载统一由 WorkManager 管理")
class DownloadForegroundService : Service() {

    companion object {
        fun enqueueDownload(context: Context) {
            DownloadWorkScheduler.enqueue(context, expedited = true)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DownloadWorkScheduler.enqueue(this, expedited = true)
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
