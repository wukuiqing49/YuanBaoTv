package com.wkq.bao.core.media.webdav

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec

/** Media3 复用经过来源校验的 WebDAV 客户端，凭据不会随重定向外发。 */
class WebDavDataSource(@Suppress("UNUSED_PARAMETER") context: Context) : BaseDataSource(true) {
    private var stream: WebDavClientManager.PlaybackStream? = null
    private var currentUri: Uri? = null
    private var remaining = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val source = WebDavCredentialRegistry.resolve(dataSpec.uri)
            ?: throw java.io.IOException("WebDAV source unavailable")
        val opened = WebDavClientManager.openPlayback(source, dataSpec.uri.toString(), dataSpec.position, dataSpec.length)
        stream = opened
        currentUri = dataSpec.uri
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else opened.length
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count = stream?.input?.read(buffer, offset,
            if (remaining < 0) length else minOf(length.toLong(), remaining).toInt())
            ?: throw java.io.IOException("WebDAV source not open")
        if (count < 0) {
            if (remaining > 0) throw java.io.EOFException("WebDAV response truncated")
            return C.RESULT_END_OF_INPUT
        }
        if (remaining >= 0) remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        val opened = stream ?: return
        stream = null
        currentUri = null
        try { opened.close() } finally { transferEnded() }
    }
}
