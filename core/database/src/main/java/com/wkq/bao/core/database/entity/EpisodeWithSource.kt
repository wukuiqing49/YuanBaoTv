package com.wkq.bao.core.database.entity

import androidx.room.Embedded

/** 单集及其当前本地副本，用于详情页准确展示 TF 或 USB。 */
data class EpisodeWithSource(
    @Embedded val episode: EpisodeEntity,
    val localUri: String?,
    val localStorageType: String?,
    val nasUri: String?,
    val seriesBackdropUri: String? = null
)
