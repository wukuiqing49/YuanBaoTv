package com.wkq.bao.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded

/** 首页继续观看所需的可展示进度数据，将标题解析保留在数据层。 */
data class ContinueWatchingItem(
    @Embedded val history: WatchHistoryEntity,
    @ColumnInfo(name = "seriesTitle") val seriesTitle: String,
    @ColumnInfo(name = "episodeTitle") val episodeTitle: String
)
