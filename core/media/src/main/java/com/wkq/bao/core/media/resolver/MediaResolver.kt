package com.wkq.bao.core.media.resolver

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.media.smb.SmbCredentialRegistry
import com.wkq.bao.core.media.storage.MediaStorageLocation
import com.wkq.bao.core.media.storage.TvStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 媒体路径与可用性决策解析器 (LOCAL FIRST, NAS SECOND)
 */
class MediaResolver(private val context: Context) {

    private val mediaDao = AppDatabase.getInstance(context).mediaDao()
    private val nasDao = AppDatabase.getInstance(context).nasDao()

    /**
     * 解析指定集的实际播放源
     */
    suspend fun resolve(episodeId: Long): PlaybackSource = withContext(Dispatchers.IO) {
        val episode = mediaDao.getEpisodeById(episodeId)
            ?: return@withContext PlaybackSource.Unavailable("Episode not found: $episodeId", episodeId)

        val mediaFile = mediaDao.getMediaFileByEpisodeId(episodeId)
            ?: return@withContext PlaybackSource.Unavailable("Media file not indexed for episode: $episodeId", episodeId)

        val title = episode.title.ifEmpty { "Episode ${episode.episodeNumber}" }

        // 1. 优先检查本地/USB存储是否存在有效文件
        val localUriString = mediaFile.localUri
        if (!localUriString.isNullOrEmpty()) {
            val localUri = Uri.parse(localUriString)
            if (isLocalFileValid(localUri)) {
                val location = MediaStorageLocation.fromStored(mediaFile.localStorageType)
                    ?: TvStorageManager(context).resolveLocalLocation(localUri)
                return@withContext PlaybackSource.Local(localUri, title, location)
            }
        }

        // 2. 本地不存在，检查 NAS 是否配置且源可用
        val nasSourceId = mediaFile.nasSourceId
        if (nasSourceId != null && mediaFile.nasUri.isNotEmpty()) {
            val nasSource = nasDao.getSourceById(nasSourceId)
            if (nasSource != null && nasSource.enabled) {
                SmbCredentialRegistry.register(nasSource)
                return@withContext PlaybackSource.NasStream(Uri.parse(mediaFile.nasUri), title, nasSourceId)
            }
        }

        // 3. 本地与 NAS 均无法播放
        PlaybackSource.Unavailable("NAS is offline and resource is not downloaded to local storage", episodeId)
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
