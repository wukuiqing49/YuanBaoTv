package com.wkq.bao.core.media.download

/** 目标盘需要容纳剩余完整文件、合并时暂存的一个分块和安全预留。 */
object DownloadStorageRequirement {
    const val SAFETY_RESERVE_BYTES = 64L * 1024 * 1024
    const val MAX_PART_BYTES = DownloadChunkPlanner.CHUNK_SIZE_BYTES

    fun requiredFreeBytes(totalBytes: Long, assembledBytes: Long): Long {
        require(totalBytes > 0L) { "下载文件大小必须大于零" }
        require(assembledBytes in 0L..totalBytes) { "已组装大小超出下载文件范围" }
        val remainingBytes = totalBytes - assembledBytes
        val nextPartBytes = minOf(MAX_PART_BYTES, remainingBytes)
        return try {
            Math.addExact(Math.addExact(remainingBytes, nextPartBytes), SAFETY_RESERVE_BYTES)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
    }
}
