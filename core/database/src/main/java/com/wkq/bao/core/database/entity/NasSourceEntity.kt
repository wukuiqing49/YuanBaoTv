package com.wkq.bao.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * NAS 数据源配置实体
 */
@Entity(tableName = "nas_sources")
data class NasSourceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val type: String = "SMB", // SMB, WebDAV, HTTP
    val host: String,
    val port: Int = 445,
    val username: String = "",
    val passwordEncrypted: String = "",
    val shareName: String = "",
    val rootPath: String = "",
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastScanAt: Long = 0L
)
