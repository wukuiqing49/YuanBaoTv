package com.wkq.bao.core.media.scanner

import android.content.Context
import android.net.Uri
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.database.entity.ScanSessionEntity
import com.wkq.bao.core.database.entity.ScanSessionKind
import com.wkq.bao.core.database.entity.ScanSessionStatus
import kotlinx.coroutines.flow.Flow

class LocalMediaScanController(context: Context) {
    private val appContext = context.applicationContext
    private val sessionDao = AppDatabase.getInstance(appContext).scanSessionDao()

    fun observe(treeUri: Uri): Flow<ScanSessionEntity?> =
        sessionDao.observe(ScanSessionKind.LOCAL, treeUri.toString())

    suspend fun enqueue(treeUri: Uri, retry: Boolean = false) {
        val sourceKey = treeUri.toString()
        val existing = sessionDao.get(ScanSessionKind.LOCAL, sourceKey)
        val now = System.currentTimeMillis()
        val session = if (retry && existing != null) {
            existing.copy(status = ScanSessionStatus.QUEUED, errorMessage = "", updatedAt = now)
        } else {
            ScanSessionEntity(
                id = LocalMediaScanScheduler.sessionId(sourceKey),
                kind = ScanSessionKind.LOCAL,
                sourceKey = sourceKey,
                workName = LocalMediaScanScheduler.workName(sourceKey),
                startedAt = now,
                updatedAt = now
            )
        }
        sessionDao.upsert(session)
        LocalMediaScanScheduler.enqueue(appContext, treeUri, replace = retry)
    }

    suspend fun cancel(treeUri: Uri) {
        LocalMediaScanScheduler.cancel(appContext, treeUri)
        sessionDao.get(ScanSessionKind.LOCAL, treeUri.toString())?.let { session ->
            sessionDao.upsert(session.copy(status = ScanSessionStatus.CANCELLED, updatedAt = System.currentTimeMillis()))
        }
    }
}
