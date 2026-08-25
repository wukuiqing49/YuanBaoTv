package com.wkq.bao.core.media.artwork

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.wkq.bao.core.database.entity.NasSourceEntity
import com.wkq.bao.core.media.smb.SmbClientManager
import com.wkq.bao.core.media.webdav.WebDavClientManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/** 从 NAS 视频随机读取少量数据生成缩略图，结果由 Coil 磁盘缓存持久化。 */
internal object NasVideoFrameExtractor {

    suspend fun extract(source: NasSourceEntity, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val dataSource = when (uri.scheme) {
            "smb" -> SmbVideoDataSource(SmbClientManager.openRemoteFile(source, uri))
            "https" -> {
                val size = WebDavClientManager.getRemoteFileInfo(source, uri).length
                WebDavVideoDataSource(source, uri, size)
            }
            else -> throw IOException("不支持的视频封面来源: ${uri.scheme}")
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(dataSource)
            retriever.embeddedPicture?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
            var frame: Bitmap? = null
            for (timeUs in FRAME_TIMES_US) {
                frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (frame != null) break
            }
            frame ?: throw IOException("视频中没有可用画面")
            frame.toJpeg()
        } catch (error: Throwable) {
            if (error is IOException) throw error
            throw IOException("无法生成 NAS 视频封面", error)
        } finally {
            retriever.release()
            dataSource.releaseResources()
        }
    }

    private fun Bitmap.toJpeg(): ByteArray {
        val scale = minOf(1f, MAX_FRAME_WIDTH.toFloat() / width.coerceAtLeast(1))
        val outputBitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(this, (width * scale).toInt(), (height * scale).toInt(), true)
        } else this
        return try {
            ByteArrayOutputStream().use { output ->
                check(outputBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    "视频封面编码失败"
                }
                output.toByteArray()
            }
        } finally {
            if (outputBitmap !== this) outputBitmap.recycle()
            recycle()
        }
    }

    private abstract class ManagedMediaDataSource : MediaDataSource() {
        // Retriever 会自行调用 close；真正资源释放必须等所有回退取帧结束。
        final override fun close() = Unit
        abstract fun releaseResources()
    }

    private class SmbVideoDataSource(
        private val handle: SmbClientManager.RemoteFileHandle
    ) : ManagedMediaDataSource() {
        override fun getSize(): Long = handle.length

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= handle.length) return -1
            val requested = minOf(size.toLong(), handle.length - position).toInt()
            return handle.file.read(buffer, position, offset, requested).takeIf { it > 0 } ?: -1
        }

        override fun releaseResources() = handle.close()
    }

    private class WebDavVideoDataSource(
        private val source: NasSourceEntity,
        private val uri: Uri,
        private val length: Long
    ) : ManagedMediaDataSource() {
        override fun getSize(): Long = length

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= length) return -1
            return WebDavClientManager.readAt(
                source,
                uri,
                position,
                buffer,
                offset,
                minOf(size.toLong(), length - position).toInt()
            )
        }

        override fun releaseResources() = Unit
    }

    private val FRAME_TIMES_US = longArrayOf(10_000_000L, 1_000_000L, 0L, -1L)
    private const val MAX_FRAME_WIDTH = 720
    private const val JPEG_QUALITY = 84
}
