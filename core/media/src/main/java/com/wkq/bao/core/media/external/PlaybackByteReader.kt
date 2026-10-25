package com.wkq.bao.core.media.external

import java.io.Closeable
import java.io.IOException

/** 为外部播放器提供按偏移读取，连续读取复用连接，跳转时重新定位。 */
internal class PlaybackByteReader(private val source: Source, private val size: Long) : Closeable {
    interface Source : Closeable {
        fun openAt(offset: Long)
        fun read(buffer: ByteArray, offset: Int, length: Int): Int
    }
    private var position = -1L
    private var closed = false

    fun readAt(offset: Long, length: Int, buffer: ByteArray): Int {
        check(!closed)
        require(offset >= 0 && length >= 0 && length <= buffer.size)
        if (offset >= size || length == 0) return 0
        try {
            if (position != offset) {
                source.close()
                position = -1
                source.openAt(offset)
                position = offset
            }
            val count = minOf(length.toLong(), size - offset).toInt()
            var total = 0
            while (total < count) {
                val read = source.read(buffer, total, count - total)
                if (read < 0) break
                if (read == 0) throw IOException("Media source made no progress")
                total += read
                position += read
            }
            return total
        } catch (error: Exception) {
            position = -1
            runCatching { source.close() }
            throw error
        }
    }

    override fun close() {
        if (!closed) { closed = true; source.close() }
    }
}
