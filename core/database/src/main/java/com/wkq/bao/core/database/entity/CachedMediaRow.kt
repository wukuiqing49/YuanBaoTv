package com.wkq.bao.core.database.entity

/** 一个已登记的本地副本；物理文件统计和单集统计分别处理。 */
data class CachedMediaRow(
    val seriesId: Long, val title: String, val type: String, val posterUri: String,
    val seasonId: Long, val seasonNumber: Int, val episodeId: Long, val episodeNumber: Int,
    val locationId: Long, val uri: String, val fileSize: Long, val storageType: String
)
