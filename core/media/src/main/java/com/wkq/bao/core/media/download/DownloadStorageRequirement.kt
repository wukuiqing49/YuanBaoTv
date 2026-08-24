package com.wkq.bao.core.media.download

/** 流式组装时，下载目标只需容纳下一个分块和安全预留。 */
object DownloadStorageRequirement {
    const val SAFETY_RESERVE_BYTES = 64L * 1024 * 1024
    const val MAX_PART_BYTES = DownloadChunkPlanner.CHUNK_SIZE_BYTES

    fun requiredFreeBytes(totalBytes: Long, assembledBytes: Long): Long {
        require(totalBytes > 0L) { "下载文件大小必须大于零" }
        require(assembledBytes in 0L..totalBytes) { "已组装大小超出下载文件范围" }
        val nextPartBytes = minOf(MAX_PART_BYTES, totalBytes - assembledBytes)
        return try {
            Math.addExact(nextPartBytes, SAFETY_RESERVE_BYTES)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
    }
}
