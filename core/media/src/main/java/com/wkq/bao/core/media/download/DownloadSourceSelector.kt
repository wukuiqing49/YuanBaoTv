package com.wkq.bao.core.media.download

import com.wkq.bao.core.database.entity.DownloadTaskEntity
import com.wkq.bao.core.database.entity.MediaFileEntity
import com.wkq.bao.core.database.entity.MediaRemoteSourceEntity

/** 将下载任务严格解析为一个已登记的 NAS 来源，防止 URI 与凭据跨来源混用。 */
object DownloadSourceSelector {
    data class Source(val uri: String, val nasSourceId: Long)

    fun select(
        task: DownloadTaskEntity,
        mediaFile: MediaFileEntity,
        remoteSources: List<MediaRemoteSourceEntity>
    ): Source? {
        val requestedUri = task.sourceUri.ifBlank { mediaFile.nasUri }
        if (requestedUri.isBlank()) return null

        remoteSources.firstOrNull { source ->
            source.uri == requestedUri &&
                source.nasSourceId != null &&
                (task.sourceNasId == 0L || source.nasSourceId == task.sourceNasId)
        }?.let { return Source(it.uri, checkNotNull(it.nasSourceId)) }

        // 兼容 7 版数据库迁移前创建的单来源任务，但不接受 ID 与 URI 不一致的记录。
        if (mediaFile.nasUri != requestedUri) return null
        val legacyNasId = mediaFile.nasSourceId ?: return null
        if (task.sourceNasId != 0L && task.sourceNasId != legacyNasId) return null
        return Source(requestedUri, legacyNasId)
    }
}
