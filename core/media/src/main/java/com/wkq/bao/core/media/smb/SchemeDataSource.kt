package com.wkq.bao.core.media.smb

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener

/** 统一分发 content://、file://、http(s):// 与 smb:// 播放源。 */
class SchemeDataSource(context: Context) : DataSource {
    class Factory(private val context: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource = SchemeDataSource(context)
    }

    private val localDataSource = DefaultDataSource(context, true)
    private val smbDataSource = SmbDataSource()
    private var delegate: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        smbDataSource.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        delegate = if (dataSpec.uri.scheme == "smb") smbDataSource else localDataSource
        return delegate!!.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return delegate?.read(buffer, offset, length) ?: throw IllegalStateException("数据源尚未打开")
    }

    override fun getUri(): Uri? = delegate?.uri

    override fun close() {
        delegate?.close()
        delegate = null
    }
}
