package com.wkq.bao.core.media.download

import com.wkq.bao.core.database.entity.DownloadChunkEntity

/** 纯分块规划逻辑，供下载器和单元测试共用。 */
object DownloadChunkPlanner {
    const val CHUNK_SIZE_BYTES = 8L * 1024 * 1024

    fun create(taskId: Long, totalBytes: Long): List<DownloadChunkEntity> = buildList {
        require(taskId > 0L) { "下载任务尚未持久化" }
        require(totalBytes >= 0L) { "下载文件大小不能为负数" }
        var startByte = 0L
        var chunkIndex = 0
        while (startByte < totalBytes) {
            val byteCount = minOf(CHUNK_SIZE_BYTES, totalBytes - startByte)
            add(DownloadChunkEntity(
                taskId = taskId,
                chunkIndex = chunkIndex,
                startByte = startByte,
                byteCount = byteCount,
                partName = "chunk_${taskId}_$chunkIndex.part"
            ))
            startByte += byteCount
            chunkIndex++
        }
    }
}
