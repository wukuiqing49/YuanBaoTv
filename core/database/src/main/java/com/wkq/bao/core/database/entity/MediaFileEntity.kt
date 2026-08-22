package com.wkq.bao.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 媒体文件物理映射实体（连接 NAS 串流地址与 USB 本地离线路径）
 */
@Entity(
    tableName = "media_files",
    foreignKeys = [
        ForeignKey(
            entity = EpisodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["episodeId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = MediaSeriesEntity::class,
            parentColumns = ["id"],
            childColumns = ["seriesId"],
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
        Index(value = ["episodeId"], unique = true),
        Index(value = ["seriesId"]),
        Index(value = ["nasSourceId"]),
        Index(value = ["localUri"])
    ]
)
data class MediaFileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val episodeId: Long,
    val seriesId: Long,
    val nasSourceId: Long? = null,
    val nasUri: String = "",
    val localUri: String? = null,
    val fileName: String = "",
    val fileSize: Long = 0L,
    val mimeType: String = "video/mp4",
    val checksum: String = "",
    val downloadStatus: String = "NONE", // NONE, WAITING, DOWNLOADING, SUCCESS, FAILED
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
