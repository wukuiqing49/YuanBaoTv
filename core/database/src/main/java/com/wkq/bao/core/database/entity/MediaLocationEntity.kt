package com.wkq.bao.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 同一媒体文件在本机、TF、USB 或移动硬盘中的一个可播放副本。 */
@Entity(
    tableName = "media_locations",
    foreignKeys = [
        ForeignKey(
            entity = MediaFileEntity::class,
            parentColumns = ["id"],
            childColumns = ["mediaFileId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["mediaFileId"]),
        Index(value = ["uri"], unique = true)
    ]
)
data class MediaLocationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val mediaFileId: Long,
    val uri: String,
    val storageType: String,
    val fileName: String,
    val fileSize: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis()
)
