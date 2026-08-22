package com.wkq.bao.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 季信息实体
 */
@Entity(
    tableName = "seasons",
    foreignKeys = [
        ForeignKey(
            entity = MediaSeriesEntity::class,
            parentColumns = ["id"],
            childColumns = ["seriesId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["seriesId", "seasonNumber"], unique = true)]
)
data class SeasonEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val seriesId: Long,
    val seasonNumber: Int,
    val title: String = "",
    val posterUri: String = "",
    val episodeCount: Int = 0
)
