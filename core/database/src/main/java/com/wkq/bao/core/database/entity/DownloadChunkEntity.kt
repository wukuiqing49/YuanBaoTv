package com.wkq.bao.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 下载任务的持久化分块记录。每个完成块都有独立文件和 SHA-256 校验值。 */
@Entity(
    tableName = "download_chunks",
    foreignKeys = [
        ForeignKey(
            entity = DownloadTaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["taskId"]),
        Index(value = ["taskId", "chunkIndex"], unique = true)
    ]
)
data class DownloadChunkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val taskId: Long,
    val chunkIndex: Int,
    val startByte: Long,
    val byteCount: Long,
    val partName: String,
    val sha256: String = "",
    val status: String = DownloadChunkStatus.WAITING,
    val updatedAt: Long = System.currentTimeMillis()
)

object DownloadChunkStatus {
    const val WAITING = "WAITING"
    const val COMPLETED = "COMPLETED"
    /** 已追加到最终临时文件，可删除独立分块文件。 */
    const val ASSEMBLED = "ASSEMBLED"
}
