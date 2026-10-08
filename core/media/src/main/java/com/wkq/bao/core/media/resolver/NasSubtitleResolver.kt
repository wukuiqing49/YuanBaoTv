package com.wkq.bao.core.media.resolver

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.media.smb.SmbClientManager
import com.wkq.bao.core.media.webdav.WebDavClientManager
import kotlinx.coroutines.CancellationException
import java.util.Locale

/** 只查询影片所在目录，字幕沿用播放器已注册的 NAS 鉴权。 */
internal class NasSubtitleResolver(context: Context) {
    private val nasDao = AppDatabase.getInstance(context.applicationContext).nasDao()

    suspend fun resolve(stream: PlaybackSource.NasStream): List<MediaItem.SubtitleConfiguration> {
        try {
            val source = nasDao.getEnabledSources().firstOrNull { it.id == stream.nasSourceId } ?: return emptyList()
            val videoName = stream.uri.lastPathSegment ?: return emptyList()
            val webDav = WebDavClientManager.isWebDav(source)
            val path = if (webDav) {
                val base = Uri.parse(WebDavClientManager.buildUri(source, ""))
                if (base.host != stream.uri.host || base.port != stream.uri.port ||
                    stream.uri.pathSegments.take(base.pathSegments.size) != base.pathSegments) return emptyList()
                stream.uri.pathSegments.drop(base.pathSegments.size).joinToString("/")
            } else SmbClientManager.parseLocation(stream.uri)?.path ?: return emptyList()
            val directory = path.substringBeforeLast('/', "")
            val result = if (webDav) WebDavClientManager.listDirectoryEntries(source, directory, 4)
                else SmbClientManager.listDirectory(source, directory, 4)
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            return result.getOrDefault(emptyList()).asSequence()
                .filter { !it.isDirectory && it.size <= MAX_SUBTITLE_BYTES && SubtitleFileMatcher.mimeType(videoName, it.name) != null }
                .sortedWith(compareBy<com.wkq.bao.core.nas.browser.NasFileEntry> {
                    !SubtitleFileMatcher.isExactMatch(videoName, it.name)
                }.thenBy { it.name.lowercase(Locale.ROOT) })
                .take(16)
                .mapIndexed { index, entry ->
                    val uri = if (webDav) WebDavClientManager.buildUri(source, entry.path)
                        else SmbClientManager.buildUri(source, entry.path)
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(uri))
                        .setMimeType(SubtitleFileMatcher.mimeType(videoName, entry.name))
                        .setLabel(entry.name).setLanguage(SubtitleFileMatcher.language(videoName, entry.name))
                        .setSelectionFlags(if (index == 0) C.SELECTION_FLAG_DEFAULT else 0).build()
                }.toList()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return emptyList()
        }
    }

    private companion object { const val MAX_SUBTITLE_BYTES = 8 * 1024 * 1024L }
}
