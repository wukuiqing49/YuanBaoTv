package com.wkq.bao.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 同一媒体文件在不同 NAS 中的一个远端来源。 */
@Entity(
    tableName = "media_remote_sources",
    foreignKeys = [
        ForeignKey(
            entity = MediaFileEntity::class,
            parentColumns = ["id"],
            childColumns = ["mediaFileId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = NasSourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["nasSourceId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["mediaFileId"]),
        Index(value = ["nasSourceId"]),
        Index(value = ["mediaFileId", "nasSourceId", "uri"], unique = true)
    ]
)
data class MediaRemoteSourceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val mediaFileId: Long,
    val nasSourceId: Long?,
    val uri: String,
    val fileName: String,
    val fileSize: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis()
)
