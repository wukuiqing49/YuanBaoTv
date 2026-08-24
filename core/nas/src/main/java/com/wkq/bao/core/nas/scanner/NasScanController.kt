package com.wkq.bao.core.nas.scanner

import android.content.Context
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.database.entity.ScanSessionEntity
import com.wkq.bao.core.database.entity.ScanSessionKind
import com.wkq.bao.core.database.entity.ScanSessionStatus
import kotlinx.coroutines.flow.Flow

/** core:nas 对外暴露的扫描控制边界；旧 scanner 包继续作为内部兼容实现。 */
class NasScanController(context: Context) {
    private val appContext = context.applicationContext
    private val sessionDao = AppDatabase.getInstance(appContext).scanSessionDao()

    fun observe(sourceId: Long): Flow<ScanSessionEntity?> =
        sessionDao.observe(ScanSessionKind.NAS, sourceId.toString())

    suspend fun enqueue(sourceId: Long, retry: Boolean = false) {
        val sourceKey = sourceId.toString()
        val existing = sessionDao.get(ScanSessionKind.NAS, sourceKey)
        val now = System.currentTimeMillis()
        val session = if (retry && existing != null) {
            existing.copy(status = ScanSessionStatus.QUEUED, errorMessage = "", updatedAt = now)
        } else {
            ScanSessionEntity(
                id = "nas:$sourceKey",
                kind = ScanSessionKind.NAS,
                sourceKey = sourceKey,
                workName = com.wkq.bao.core.media.scanner.NasScanScheduler.workName(sourceId),
                startedAt = now,
                updatedAt = now
            )
        }
        sessionDao.upsert(session)
        com.wkq.bao.core.media.scanner.NasScanScheduler.enqueue(appContext, sourceId, replace = retry)
    }

    suspend fun cancel(sourceId: Long) {
        com.wkq.bao.core.media.scanner.NasScanScheduler.cancel(appContext, sourceId)
        sessionDao.get(ScanSessionKind.NAS, sourceId.toString())?.let { session ->
            sessionDao.upsert(session.copy(status = ScanSessionStatus.CANCELLED, updatedAt = System.currentTimeMillis()))
        }
    }
}
