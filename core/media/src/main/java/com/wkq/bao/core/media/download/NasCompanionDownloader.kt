package com.wkq.bao.core.media.download

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.wkq.bao.core.database.entity.NasSourceEntity
import com.wkq.bao.core.media.smb.SmbClientManager
import com.wkq.bao.core.media.webdav.WebDavClientManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.util.UUID

/** 与视频一同完成后才交付入库；附件失败可重试，不能把缺字幕的下载报告为完成。 */
internal class NasCompanionDownloader(private val context: Context) {
    suspend fun copy(source: NasSourceEntity, video: Uri, target: DocumentFile, localVideo: String, checkTask: suspend () -> Unit) {
        val videoName = video.lastPathSegment ?: return
        if (videoName.substringAfterLast('.').lowercase() !in setOf("mp4", "mkv", "avi", "mov", "ts", "webm", "m4v")) return
        val web = WebDavClientManager.isWebDav(source)
        val path = if (web) {
            val base = Uri.parse(WebDavClientManager.buildUri(source, ""))
            check(base.host == video.host && video.pathSegments.take(base.pathSegments.size) == base.pathSegments)
            video.pathSegments.drop(base.pathSegments.size).joinToString("/")
        } else SmbClientManager.parseLocation(video)?.path ?: return
        val directory = path.substringBeforeLast('/', "")
        val entries = (if (web) WebDavClientManager.listDirectoryEntries(source, directory)
            else SmbClientManager.listDirectory(source, directory)).getOrThrow()
            .filter { !it.isDirectory && MediaCompanionFiles.matches(videoName, it.name) }
        require(entries.size <= 64) { "Too many media companion files" }
        val coroutineContext = currentCoroutineContext()
        for (entry in entries) {
            checkTask()
            coroutineContext.ensureActive()
            require(entry.size in 0..16L * 1024 * 1024) { "Companion file exceeds limit" }
            val localName = MediaCompanionFiles.localName(videoName, localVideo, entry.name)
            val existing = target.findFile(localName)
            if (existing != null) {
                check(existing.isFile && existing.length() == entry.size) { "Companion target is occupied" }
                continue
            }
            val remote = Uri.parse(if (web) WebDavClientManager.buildUri(source, entry.path)
                else SmbClientManager.buildUri(source, entry.path))
            val pending = target.createFile("application/octet-stream", ".companion-${UUID.randomUUID()}.pending")
                ?: throw IOException("Cannot create companion file")
            var completed = false
            try {
                context.contentResolver.openOutputStream(pending.uri, "w")?.use { output ->
                    var copied = 0L
                    val buffer = ByteArray(64 * 1024)
                    if (web) {
                        WebDavClientManager.withRemoteStream(source, remote) { input ->
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    copied += read
                                    check(copied <= entry.size) { "Companion source changed" }
                                    output.write(buffer, 0, read)
                                }
                        }
                    } else {
                        SmbClientManager.withRemoteFile(source, remote) { file, length ->
                            check(length == entry.size) { "Companion source changed" }
                            while (copied < length) {
                                coroutineContext.ensureActive()
                                val read = file.read(buffer, copied, 0, minOf(buffer.size.toLong(), length - copied).toInt())
                                if (read <= 0) throw IOException("Incomplete companion")
                                output.write(buffer, 0, read)
                                copied += read
                            }
                        }
                    }
                    check(copied == entry.size) { "Incomplete companion" }
                } ?: throw IOException("Cannot write companion")
                checkTask()
                check(pending.length() == entry.size && pending.renameTo(localName)) { "Cannot finalize companion" }
                completed = true
            } finally {
                if (!completed) runCatching { pending.delete() }
            }
        }
    }
}
