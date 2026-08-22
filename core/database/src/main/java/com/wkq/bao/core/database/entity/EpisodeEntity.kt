package com.wkq.bao.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 单集视频元数据实体
 */
@Entity(
    tableName = "episodes",
    foreignKeys = [
        ForeignKey(
            entity = MediaSeriesEntity::class,
            parentColumns = ["id"],
            childColumns = ["seriesId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = SeasonEntity::class,
            parentColumns = ["id"],
            childColumns = ["seasonId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["seriesId"]),
        Index(value = ["seasonId", "episodeNumber"])
    ]
)
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val seriesId: Long,
    val seasonId: Long,
    val episodeNumber: Int,
    val title: String = "",
    val description: String = "",
    val durationMs: Long = 0L,
    val thumbnailUri: String = "",
    val airDate: String = ""
)
