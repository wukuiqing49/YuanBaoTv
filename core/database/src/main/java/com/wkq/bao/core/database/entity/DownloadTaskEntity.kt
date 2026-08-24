package com.wkq.bao.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 离线下载任务实体
 */
@Entity(
    tableName = "download_tasks",
    indices = [
        Index(value = ["episodeId", "targetUri"], unique = true),
        Index(value = ["status"]),
        // NAS 删除、来源切换和后台恢复均按来源查询任务，避免任务规模增长后全表扫描。
        Index(value = ["sourceNasId"])
    ]
)
data class DownloadTaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val seriesId: Long,
    val seasonId: Long,
    val episodeId: Long,
    val sourceUri: String,
    /** 入队时锁定的 NAS 来源，避免多 NAS 扫描后断点下载连接到错误凭据。 */
    val sourceNasId: Long = 0L,
    val targetUri: String = "",
    val targetStorageType: String = "",
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    /** 已安全追加到最终临时文件的连续字节数，用于大文件低空间恢复。 */
    val assembledBytes: Long = 0L,
    /** 最近一次确认的 NAS 文件修改时间，用于拒绝续传已变化的源文件。 */
    val sourceLastModifiedAt: Long = 0L,
    val status: String = "WAITING", // WAITING, DOWNLOADING, PAUSED, SUCCESS, FAILED
    val errorMessage: String = "",
    /** 机器可判定的失败原因，供后台重试和界面展示策略使用。 */
    val errorCode: String = DownloadTaskErrorCode.NONE,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = 0L
)

/** 下载失败码：文案可本地化，重试决策不依赖文案。 */
object DownloadTaskErrorCode {
    const val NONE = ""
    const val NETWORK = "NETWORK"
    const val STORAGE_UNAVAILABLE = "STORAGE_UNAVAILABLE"
    const val STORAGE_CAPACITY = "STORAGE_CAPACITY"
    const val STORAGE_ACCESS = "STORAGE_ACCESS"
    const val SOURCE_UNAVAILABLE = "SOURCE_UNAVAILABLE"
    const val SOURCE_CHANGED = "SOURCE_CHANGED"
    const val UNSUPPORTED_SOURCE = "UNSUPPORTED_SOURCE"
    const val TARGET_EXISTS = "TARGET_EXISTS"
    const val DATA_MISSING = "DATA_MISSING"
    const val UNKNOWN = "UNKNOWN"

    fun isRetryable(code: String): Boolean = code in setOf(NETWORK, STORAGE_UNAVAILABLE)
}
