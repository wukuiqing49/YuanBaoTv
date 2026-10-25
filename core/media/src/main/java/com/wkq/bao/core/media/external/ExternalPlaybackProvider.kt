package com.wkq.bao.core.media.external

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.media3.datasource.DataSpec
import com.wkq.bao.core.media.smb.SchemeDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 不导出的只读媒体桥：外部只得到随机内容 URI，凭据与原始地址留在本进程。 */
class ExternalPlaybackProvider : ContentProvider() {
    data class Ticket(val uri: Uri, val mimeType: String)
    private data class Entry(val source: Uri, val size: Long, val name: String, val mime: String, val createdAt: Long)

    companion object {
        private val entries = ConcurrentHashMap<String, Entry>()
        private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

        suspend fun prepare(context: Context, source: Uri, title: String): Ticket {
            // IO 返回后再登记授权；页面在探测期间取消时，不遗留无人接收的 ticket。
            val size = withContext(Dispatchers.IO) {
                val data = SchemeDataSource(context.applicationContext)
                try { data.open(DataSpec(source)) } finally { data.close() }
            }
            require(size > 0) { "Unknown media size" }
            val now = System.currentTimeMillis()
            entries.entries.removeAll { now - it.value.createdAt > MAX_AGE_MS }
            check(entries.size < 16) { "Too many playback grants" }
            val extension = source.lastPathSegment?.substringAfterLast('.', "")?.lowercase()
                ?.takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "mp4"
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "video/mp4"
            val token = UUID.randomUUID().toString()
            entries[token] = Entry(source, size, "$title.$extension", mime, now)
            return Ticket(Uri.Builder().scheme("content").authority("${context.packageName}.playback")
                .appendPath(token).appendPath("video.$extension").build(), mime)
        }

        fun release(context: Context, uri: Uri) {
            uri.pathSegments.firstOrNull()?.let { entries.remove(it) }
            context.revokeUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun entry(uri: Uri): Entry {
        val token = uri.pathSegments.firstOrNull() ?: throw FileNotFoundException("Invalid playback grant")
        return entries[token]?.takeIf { System.currentTimeMillis() - it.createdAt <= MAX_AGE_MS }
            ?: throw FileNotFoundException("Playback grant expired")
    }

    override fun onCreate() = true
    override fun getType(uri: Uri): String = entry(uri).mime
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val item = entry(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { when (it) { OpenableColumns.DISPLAY_NAME -> item.name; OpenableColumns.SIZE -> item.size; else -> null } })
        }
    }

    @RequiresApi(26)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        val item = entry(uri)
        val app = requireNotNull(context).applicationContext
        val data = SchemeDataSource(app)
        val reader = PlaybackByteReader(object : PlaybackByteReader.Source {
            override fun openAt(offset: Long) { data.open(DataSpec.Builder().setUri(item.source).setPosition(offset).build()) }
            override fun read(buffer: ByteArray, offset: Int, length: Int) = data.read(buffer, offset, length)
            override fun close() = data.close()
        }, item.size)
        val worker = HandlerThread("ExternalPlaybackReader").apply { start() }
        val callback = object : ProxyFileDescriptorCallback() {
            override fun onGetSize() = item.size
            override fun onRead(offset: Long, size: Int, data: ByteArray): Int = try {
                reader.readAt(offset, size, data)
            } catch (error: Exception) {
                throw ErrnoException("read", OsConstants.EIO)
            }
            override fun onRelease() {
                try { reader.close() } finally { worker.quitSafely() }
            }
        }
        return try {
            app.getSystemService(StorageManager::class.java).openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY, callback, Handler(worker.looper))
        } catch (error: Exception) {
            try { reader.close() } finally { worker.quitSafely() }
            throw FileNotFoundException("Cannot open playback descriptor")
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
}
