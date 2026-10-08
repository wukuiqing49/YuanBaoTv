package com.wkq.bao.core.media.resolver

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

internal class LocalSubtitleResolver(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private data class Candidate(val name: String, val uri: Uri)

    suspend fun resolve(videoUri: Uri): List<MediaItem.SubtitleConfiguration> = withContext(Dispatchers.IO) {
        try {
            val (videoName, siblings) = when (videoUri.scheme) {
                "file" -> {
                    val video = File(videoUri.path ?: return@withContext emptyList())
                    video.name to video.parentFile?.listFiles().orEmpty()
                        .filter { it.isFile }
                        .map { Candidate(it.name, Uri.fromFile(it)) }
                }
                "content" -> documentSiblings(videoUri) ?: return@withContext emptyList()
                else -> return@withContext emptyList()
            }
            siblings.filter { SubtitleFileMatcher.mimeType(videoName, it.name) != null }
                .sortedWith(compareBy<Candidate> { !SubtitleFileMatcher.isExactMatch(videoName, it.name) }
                    .thenBy { it.name.lowercase(Locale.ROOT) })
                .mapIndexed { index, candidate ->
                    MediaItem.SubtitleConfiguration.Builder(candidate.uri)
                        .setMimeType(SubtitleFileMatcher.mimeType(videoName, candidate.name))
                        .setLabel(candidate.name)
                        .setLanguage(SubtitleFileMatcher.language(videoName, candidate.name))
                        .setSelectionFlags(if (index == 0) C.SELECTION_FLAG_DEFAULT else 0)
                        .build()
                }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // 字幕不可读不应阻断视频，日志不记录用户路径。
            Log.w("LocalSubtitleResolver", "Cannot discover external subtitles: ${error.javaClass.simpleName}")
            emptyList()
        }
    }

    private fun documentSiblings(videoUri: Uri): Pair<String, List<Candidate>>? {
        if (!DocumentsContract.isTreeUri(videoUri)) return null
        val documentId = DocumentsContract.getDocumentId(videoUri)
        // 优先由 Provider 返回父目录，兼容非路径型 documentId。
        val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                DocumentsContract.findDocumentPath(resolver, videoUri)?.path
            } catch (_: Exception) {
                null
            }
        } else null
        val parentId = path?.takeIf { it.size >= 2 }?.let { it[it.lastIndex - 1] }
            ?: if (videoUri.authority == "com.android.externalstorage.documents") {
                if ('/' in documentId) documentId.substringBeforeLast('/')
                else documentId.substringBefore(':') + ":"
            } else return null
        val videoName = resolver.query(videoUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: return null
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(videoUri, parentId)
        val candidates = mutableListOf<Candidate>()
        resolver.query(childrenUri, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        ), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                val name = cursor.getString(1) ?: continue
                if (SubtitleFileMatcher.mimeType(videoName, name) != null) {
                    candidates += Candidate(name,
                        DocumentsContract.buildDocumentUriUsingTree(videoUri, cursor.getString(0)))
                }
            }
        }
        return videoName to candidates
    }
}
