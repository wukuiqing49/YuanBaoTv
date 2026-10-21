package com.wkq.bao.core.media.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.database.entity.CachedMediaRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class CachedSeries(val seriesId: Long, val title: String, val posterUri: String,
    val type: String, val seasonCount: Int, val episodeCount: Int, val totalBytes: Long)

fun groupCachedMedia(rows: List<CachedMediaRow>): List<CachedSeries> = rows.groupBy { it.seriesId }.values.map { copies ->
    val first = copies.first()
    CachedSeries(first.seriesId, first.title, first.posterUri, first.type,
        copies.map { it.seasonId }.distinct().size, copies.map { it.episodeId }.distinct().size,
        copies.distinctBy { it.uri }.sumOf { it.fileSize.coerceAtLeast(0) })
}

/** 只删除用户确认的那一个本地副本，保留 NAS、其他副本与观看历史。 */
internal suspend fun removeCachedCopy(context: Context, database: AppDatabase,
    seriesId: Long, episodeId: Long, uri: String): Boolean = withContext(Dispatchers.IO) {
    val dao = database.mediaDao()
    val episode = dao.getEpisodeById(episodeId)?.takeIf { it.seriesId == seriesId } ?: return@withContext false
    val file = dao.getMediaFileByEpisodeId(episode.id) ?: return@withContext false
    val location = dao.getMediaLocations(file.id).firstOrNull { it.uri == uri } ?: return@withContext false
    val parsed = Uri.parse(uri)
    val removed = when (parsed.scheme) {
        "file" -> File(parsed.path ?: return@withContext false).let { it.isFile && it.delete() }
        "content" -> DocumentFile.fromSingleUri(context, parsed)?.let { it.isFile && it.delete() } == true
        else -> false
    }
    if (!removed) return@withContext false
    database.withTransaction {
        dao.deleteLocalCopy(location.id, uri)
        if (!dao.hasMediaLocations(file.id)) database.downloadDao().invalidateCompletedEpisode(episodeId)
    }
    true
}
