package com.wkq.bao.core.media.download

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 同一 NAS 来源的下载与清理串行执行，避免临时文件并发读写。 */
object DownloadSourceLock {
    private val locks = ConcurrentHashMap<Long, Mutex>()

    suspend fun <T> withLock(sourceNasId: Long, block: suspend () -> T): T {
        return locks.getOrPut(sourceNasId) { Mutex() }.withLock { block() }
    }
}
