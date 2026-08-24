package com.wkq.bao.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 可跨进程恢复的媒体扫描会话。checkpoint 由具体扫描器解释。 */
@Entity(
    tableName = "scan_sessions",
    indices = [Index(value = ["kind", "sourceKey"], unique = true)]
)
data class ScanSessionEntity(
    @PrimaryKey
    val id: String,
    val kind: String,
    val sourceKey: String,
    val workName: String,
    val status: String = ScanSessionStatus.QUEUED,
    val processedCount: Int = 0,
    val importedCount: Int = 0,
    val checkpoint: String = "",
    val errorMessage: String = "",
    val startedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

object ScanSessionKind {
    const val NAS = "NAS"
    const val LOCAL = "LOCAL"
}

object ScanSessionStatus {
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val RETRYING = "RETRYING"
    const val SUCCEEDED = "SUCCEEDED"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"

    fun isActive(status: String): Boolean = status in setOf(QUEUED, RUNNING, RETRYING)
}
