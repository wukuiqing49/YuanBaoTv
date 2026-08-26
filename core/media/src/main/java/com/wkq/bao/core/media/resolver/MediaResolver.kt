package com.wkq.bao.core.media.resolver

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.media.storage.MediaStorageLocation
import com.wkq.bao.core.media.storage.TvStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 媒体路径与可用性决策解析器，只返回已下载的本地副本。
 */
class MediaResolver(private val context: Context) {

    private val mediaDao = AppDatabase.getInstance(context).mediaDao()

    /**
     * 解析指定集的实际播放源
     */
    suspend fun resolve(
        episodeId: Long,
        allowLocal: Boolean = true,
        @Suppress("UNUSED_PARAMETER") excludedNasSourceIds: Set<Long> = emptySet()
    ): PlaybackSource = withContext(Dispatchers.IO) {
        val episode = mediaDao.getEpisodeById(episodeId)
            ?: return@withContext PlaybackSource.Unavailable("Episode not found: $episodeId", episodeId)

        val mediaFile = mediaDao.getMediaFileByEpisodeId(episodeId)
            ?: return@withContext PlaybackSource.Unavailable("Media file not indexed for episode: $episodeId", episodeId)

        val title = episode.title.ifEmpty { "Episode ${episode.episodeNumber}" }

        // 1. 按最近使用顺序检查全部本机/外接副本
        if (allowLocal) {
            mediaDao.getMediaLocations(mediaFile.id).forEach { localLocation ->
                val localUri = Uri.parse(localLocation.uri)
                if (isLocalFileValid(localUri)) {
                    val location = MediaStorageLocation.fromStored(localLocation.storageType)
                        ?: TvStorageManager(context).resolveLocalLocation(localUri)
                    return@withContext PlaybackSource.Local(localUri, title, location)
                }
            }

            // 兼容尚未升级到多位置表的旧记录。
            val legacyUri = mediaFile.localUri?.let(Uri::parse)
            if (legacyUri != null && isLocalFileValid(legacyUri)) {
                val location = MediaStorageLocation.fromStored(mediaFile.localStorageType)
                    ?: TvStorageManager(context).resolveLocalLocation(legacyUri)
                return@withContext PlaybackSource.Local(legacyUri, title, location)
            }
        }

        PlaybackSource.Unavailable("Local media is not available on the selected storage", episodeId)
    }

    /**
     * 校验本地 SAF DocumentUri 或常规 File 是否存在且可读
     */
    private fun isLocalFileValid(uri: Uri): Boolean {
        return try {
            if (uri.scheme == "file") {
                val file = File(uri.path ?: return false)
                file.exists() && file.length() > 0
            } else {
                val documentFile = DocumentFile.fromSingleUri(context, uri)
                documentFile != null && documentFile.exists() && documentFile.length() > 0
            }
        } catch (e: Exception) {
            false
        }
    }
}
