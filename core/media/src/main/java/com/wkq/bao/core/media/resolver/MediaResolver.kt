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
    suspend fun resolve(
        episodeId: Long,
        allowLocal: Boolean = true,
        excludedNasSourceIds: Set<Long> = emptySet()
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

        // 2. 本地不存在，按最近扫描顺序依次尝试已启用的 NAS 来源。
        mediaDao.getMediaRemoteSources(mediaFile.id).forEach { remoteSource ->
            val nasSourceId = remoteSource.nasSourceId ?: return@forEach
            if (nasSourceId in excludedNasSourceIds) return@forEach
            val nasSource = nasDao.getSourceById(nasSourceId)
            if (nasSource != null && nasSource.enabled) {
                SmbCredentialRegistry.register(nasSource)
                return@withContext PlaybackSource.NasStream(Uri.parse(remoteSource.uri), title, nasSourceId)
            }
        }

        // 兼容升级前只有一个 NAS 来源的媒体记录。
        val nasSourceId = mediaFile.nasSourceId
        if (nasSourceId != null && nasSourceId !in excludedNasSourceIds && mediaFile.nasUri.isNotEmpty()) {
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
