package com.wkq.bao.core.media.artwork

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import coil.Coil
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.annotation.ExperimentalCoilApi
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.disk.DiskCache
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.wkq.bao.core.media.smb.SmbClientManager
import com.wkq.bao.core.media.smb.SmbCredentialRegistry
import java.io.IOException
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer

/** 为全局 Coil 注册 SMB 图片读取能力，页面层无需感知 NAS 凭据和连接细节。 */
object NasArtworkLoader {
    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val appContext = context.applicationContext
            Coil.setImageLoader(
                ImageLoaderFactory {
                    ImageLoader.Builder(appContext)
                        .components { add(SmbArtworkFetcher.Factory(appContext)) }
                        .build()
                }
            )
            installed = true
        }
    }
}

@OptIn(ExperimentalCoilApi::class)
private class SmbArtworkFetcher(
    private val appContext: Context,
    private val data: Uri,
    private val options: Options,
    private val diskCache: DiskCache?
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        if (options.diskCachePolicy.readEnabled) {
            readFromDiskCache()?.let { return it }
        }
        if (!options.networkCachePolicy.readEnabled) {
            throw IOException("SMB 图片未命中缓存且当前请求禁止网络读取")
        }
        val source = SmbCredentialRegistry.resolve(appContext, data)
            ?: throw IOException("未找到 SMB 图片源配置")
        val handle = try {
            SmbClientManager.openRemoteFile(source, data)
        } catch (error: Throwable) {
            throw IOException("无法打开 NAS 图片", error)
        }
        val cache = diskCache
        return if (cache != null && options.diskCachePolicy.writeEnabled) {
            writeToDiskCache(cache, handle)
        } else {
            networkResult(handle)
        }
    }

    private fun readFromDiskCache(): SourceResult? {
        val cache = diskCache ?: return null
        val snapshot = cache.openSnapshot(cacheKey) ?: return null
        return SourceResult(
            source = ImageSource(snapshot.data, cache.fileSystem, cacheKey, snapshot),
            mimeType = mimeType,
            dataSource = DataSource.DISK
        )
    }

    private fun writeToDiskCache(
        cache: DiskCache,
        handle: SmbClientManager.RemoteFileHandle
    ): SourceResult {
        val editor = cache.openEditor(cacheKey) ?: return networkResult(handle)
        try {
            cache.fileSystem.write(editor.metadata) {}
            SmbRemoteSource(handle).buffer().use { remoteSource ->
                cache.fileSystem.write(editor.data) { writeAll(remoteSource) }
            }
            val snapshot = editor.commitAndOpenSnapshot()
                ?: throw IOException("无法提交 NAS 图片缓存")
            return SourceResult(
                source = ImageSource(snapshot.data, cache.fileSystem, cacheKey, snapshot),
                mimeType = mimeType,
                dataSource = DataSource.NETWORK
            )
        } catch (error: Throwable) {
            runCatching { editor.abort() }
            runCatching { handle.close() }
            if (error is IOException) throw error
            throw IOException("缓存 NAS 图片失败", error)
        }
    }

    private fun networkResult(handle: SmbClientManager.RemoteFileHandle): SourceResult =
        SourceResult(
            source = ImageSource(SmbRemoteSource(handle).buffer(), appContext),
            mimeType = mimeType,
            dataSource = DataSource.NETWORK
        )

    private val cacheKey: String get() = options.diskCacheKey ?: data.toString()

    private val mimeType: String?
        get() = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
            data.lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase()
        )

    class Factory(private val appContext: Context) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != "smb") return null
            return SmbArtworkFetcher(appContext, data, options, imageLoader.diskCache)
        }
    }
}

private class SmbRemoteSource(
    private val handle: SmbClientManager.RemoteFileHandle
) : Source {
    private val buffer = ByteArray(READ_BUFFER_SIZE)
    private var position = 0L
    private var closed = false

    override fun read(sink: Buffer, byteCount: Long): Long {
        check(!closed) { "SMB 图片源已经关闭" }
        if (byteCount == 0L) return 0L
        if (position >= handle.length) return -1L
        val requested = minOf(byteCount, buffer.size.toLong(), handle.length - position).toInt()
        val read = try {
            handle.file.read(buffer, position, 0, requested)
        } catch (error: Throwable) {
            throw IOException("读取 NAS 图片失败", error)
        }
        if (read <= 0) return -1L
        sink.write(buffer, 0, read)
        position += read
        return read.toLong()
    }

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() {
        if (closed) return
        closed = true
        handle.close()
    }

    private companion object {
        const val READ_BUFFER_SIZE = 64 * 1024
    }
}
