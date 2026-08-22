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
        Index(value = ["episodeId"], unique = true),
        Index(value = ["status"])
    ]
)
data class DownloadTaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val seriesId: Long,
    val seasonId: Long,
    val episodeId: Long,
    val sourceUri: String,
    val targetUri: String = "",
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val status: String = "WAITING", // WAITING, DOWNLOADING, PAUSED, SUCCESS, FAILED
    val errorMessage: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = 0L
)
