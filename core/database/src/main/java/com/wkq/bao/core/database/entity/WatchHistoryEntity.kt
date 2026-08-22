package com.wkq.bao.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 观看历史与播放进度实体
 */
@Entity(
    tableName = "watch_histories",
    indices = [
        Index(value = ["episodeId"], unique = true),
        Index(value = ["seriesId"]),
        Index(value = ["lastPlayedAt"])
    ]
)
data class WatchHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val seriesId: Long,
    val seasonId: Long,
    val episodeId: Long,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val completed: Boolean = false,
    val lastPlayedAt: Long = System.currentTimeMillis()
)
