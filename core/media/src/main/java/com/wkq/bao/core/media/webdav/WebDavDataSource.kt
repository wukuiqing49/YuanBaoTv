package com.wkq.bao.core.media.webdav

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import com.wkq.bao.core.media.webdav.WebDavClientManager.authorizationHeader
import com.wkq.bao.core.media.webdav.WebDavCredentialRegistry

/** 为 Media3 的 WebDAV 视频请求附加当前 NAS 的 Basic Auth。 */
class WebDavDataSource(context: Context) : DataSource {
    private val transferListeners = linkedSetOf<TransferListener>()
    private var delegate: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
        delegate?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = WebDavCredentialRegistry.resolve(dataSpec.uri)
            ?: throw java.io.IOException("未找到 WebDAV 媒体源配置，请先在 NAS 设置中完成连接")
        val headers = authorizationHeader(source)?.let { mapOf("Authorization" to it) }.orEmpty()
        return DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(false)
            .createDataSource()
            .also {
                transferListeners.forEach(it::addTransferListener)
                delegate = it
            }
            .open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate?.read(buffer, offset, length) ?: throw IllegalStateException("WebDAV 数据源尚未打开")

    override fun getUri(): Uri? = delegate?.uri

    override fun close() {
        delegate?.close()
        delegate = null
    }
}
