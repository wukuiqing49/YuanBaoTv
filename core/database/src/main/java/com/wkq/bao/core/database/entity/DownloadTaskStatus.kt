package com.wkq.bao.core.database.entity

/** 下载任务状态，集中定义以避免页面和服务使用不一致的字符串。 */
object DownloadTaskStatus {
    const val WAITING = "WAITING"
    const val DOWNLOADING = "DOWNLOADING"
    const val PAUSED = "PAUSED"
    const val SUCCESS = "SUCCESS"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
}
