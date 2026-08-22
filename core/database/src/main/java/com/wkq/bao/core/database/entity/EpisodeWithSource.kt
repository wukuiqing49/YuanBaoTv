package com.wkq.bao.core.database.entity

import androidx.room.Embedded

/** 单集及其当前可播放来源，用于详情页准确展示 TF、USB 或 NAS。 */
data class EpisodeWithSource(
    @Embedded val episode: EpisodeEntity,
    val localUri: String?,
    val localStorageType: String?,
    val nasUri: String?
)
