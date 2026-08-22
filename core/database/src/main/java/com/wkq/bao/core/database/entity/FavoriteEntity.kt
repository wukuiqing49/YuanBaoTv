package com.wkq.bao.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 用户收藏的媒体条目。 */
@Entity(tableName = "favorites", indices = [Index(value = ["seriesId"], unique = true)])
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val seriesId: Long,
    val createdAt: Long = System.currentTimeMillis()
)
