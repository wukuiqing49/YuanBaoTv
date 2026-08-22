package com.wkq.bao.core.media.smb

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/** 让 Media3 能按需随机读取 smb:// URI，支持拖动进度条与重新缓冲。 */
class SmbDataSource : DataSource {
    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource()
    }

    private val listeners = mutableListOf<TransferListener>()
    private var handle: SmbClientManager.RemoteFileHandle? = null
    private var openedUri: Uri? = null
    private var readPosition = 0L
    private var bytesRemaining = C.LENGTH_UNSET.toLong()

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = SmbCredentialRegistry.resolve(dataSpec.uri)
            ?: throw IOException("未找到 SMB 媒体源配置，请先在 NAS 设置中完成连接")
        val openedHandle = try {
            SmbClientManager.openRemoteFile(source, dataSpec.uri)
        } catch (error: Throwable) {
            throw IOException("无法打开 NAS 媒体", error)
        }
        handle = openedHandle
        openedUri = dataSpec.uri
        readPosition = dataSpec.position
        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            openedHandle.length - readPosition
        } else {
            minOf(dataSpec.length, openedHandle.length - readPosition)
        }
        if (bytesRemaining < 0L) throw IOException("播放位置超出 SMB 文件长度")
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val remoteFile = handle?.file ?: throw IOException("SMB 数据源尚未打开")
        return try {
            val read = remoteFile.read(buffer, readPosition, offset, minOf(length.toLong(), bytesRemaining).toInt())
            if (read <= 0) return C.RESULT_END_OF_INPUT
            readPosition += read
            bytesRemaining -= read
            read
        } catch (error: Throwable) {
            throw IOException("读取 NAS 媒体失败", error)
        }
    }

    override fun getUri(): Uri? = openedUri

    override fun close() {
        handle?.close()
        handle = null
        openedUri = null
        bytesRemaining = C.LENGTH_UNSET.toLong()
    }
}
