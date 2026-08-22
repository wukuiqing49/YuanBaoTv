package com.wkq.bao.core.media.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.database.entity.DownloadTaskEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 离线下载前台服务 (常驻通知栏，脱离 Activity 生命周期，支持大文件后台稳定下载)
 */
class DownloadForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "yuanbao_download_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START_DOWNLOAD = "action_start_download"

        fun enqueueDownload(context: Context) {
            val intent = Intent(context, DownloadForegroundService::class.java).apply {
                action = ACTION_START_DOWNLOAD
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val database by lazy { AppDatabase.getInstance(this) }
    private val downloadEngine by lazy { TvDownloadEngine(this, database) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("正在准备离线下载...", 0))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serviceScope.launch {
            val pendingTasks = database.downloadDao().getTasksByStatus("WAITING")
            for (task in pendingTasks) {
                downloadEngine.executeTask(task) { progress ->
                    val notification = buildNotification("正在下载剧集 (Episode ${task.episodeId})", progress)
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIFICATION_ID, notification)
                }
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(content: String, progress: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("圆宝TV - 离线下载中心")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "圆宝TV 离线下载",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
