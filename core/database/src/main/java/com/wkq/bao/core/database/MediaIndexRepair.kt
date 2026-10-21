package com.wkq.bao.core.database

import androidx.room.withTransaction

/** 重新识别文件归属时复制观看状态和收藏；旧副本及旧历史不做破坏性删除。 */
suspend fun AppDatabase.preservePlaybackState(
    oldFileId: Long?, newFileId: Long, seriesId: Long, seasonId: Long, episodeId: Long
) {
    if (oldFileId == null || oldFileId == newFileId) return
    withTransaction {
        val old = mediaDao().getMediaFileById(oldFileId) ?: return@withTransaction
        favoriteDao().getBySeriesId(old.seriesId)?.let { favorite ->
            favoriteDao().insert(favorite.copy(id = 0, seriesId = seriesId))
        }
        val history = watchHistoryDao().getHistoryByEpisodeId(old.episodeId) ?: return@withTransaction
        val current = watchHistoryDao().getHistoryByEpisodeId(episodeId)
        if (current == null || current.lastPlayedAt < history.lastPlayedAt) {
            watchHistoryDao().saveHistory(history.copy(id = current?.id ?: 0,
                seriesId = seriesId, seasonId = seasonId, episodeId = episodeId))
        }
    }
}
