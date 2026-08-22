package com.wkq.bao.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 剧集 / 动漫 / 电影 主体信息实体
 */
@Entity(
    tableName = "media_series",
    indices = [Index(value = ["title"])]
)
data class MediaSeriesEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val originalTitle: String = "",
    val type: String = "CARTOON", // CARTOON, TV, MOVIE
    val posterUri: String = "",
    val backdropUri: String = "",
    val description: String = "",
    val year: String = "",
    val genre: String = "",
    val totalSeasons: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
