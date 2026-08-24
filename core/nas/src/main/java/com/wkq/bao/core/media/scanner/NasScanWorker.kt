package com.wkq.bao.core.media.scanner

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.wkq.bao.core.base.diagnostics.AppDiagnostics
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.database.entity.ScanSessionEntity
import com.wkq.bao.core.database.entity.ScanSessionKind
import com.wkq.bao.core.database.entity.ScanSessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class NasScanWorker(appContext: Context, parameters: WorkerParameters) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        AppDiagnostics.record(applicationContext, "nas_scan", "started")
        val sourceId = inputData.getLong(KEY_SOURCE_ID, 0L)
        val database = AppDatabase.getInstance(applicationContext)
        val sessionDao = database.scanSessionDao()
        val sourceKey = sourceId.toString()
        val initialSession = sessionDao.get(ScanSessionKind.NAS, sourceKey) ?: ScanSessionEntity(
            id = "nas:$sourceKey",
            kind = ScanSessionKind.NAS,
            sourceKey = sourceKey,
            workName = NasScanScheduler.workName(sourceId)
        )
        val source = database.nasDao().getSourceById(sourceId) ?: return Result.success().also {
            sessionDao.upsert(initialSession.copy(
                status = ScanSessionStatus.CANCELLED,
                errorMessage = "NAS source missing",
                updatedAt = System.currentTimeMillis()
            ))
            AppDiagnostics.record(applicationContext, "nas_scan", "source_missing")
        }
        if (!source.enabled) return Result.success().also {
            sessionDao.upsert(initialSession.copy(
                status = ScanSessionStatus.CANCELLED,
                errorMessage = "NAS source disabled",
                updatedAt = System.currentTimeMillis()
            ))
            AppDiagnostics.record(applicationContext, "nas_scan", "source_disabled")
        }
        var session = initialSession.copy(
            status = ScanSessionStatus.RUNNING,
            errorMessage = "",
            updatedAt = System.currentTimeMillis()
        )
        sessionDao.upsert(session)
        val scanResult = try {
            NasScanner(database).scanAndImport(
                nasSource = source,
                resumeAfterPath = session.checkpoint,
                scanStartedAt = session.startedAt,
                initialImportedCount = session.importedCount,
                persistCheckpoint = { importedCount, checkpoint ->
                    sessionDao.upsert(session.copy(
                        processedCount = importedCount,
                        importedCount = importedCount,
                        checkpoint = checkpoint,
                        updatedAt = System.currentTimeMillis()
                    ))
                },
                persistCompletion = { importedCount ->
                    sessionDao.upsert(session.copy(
                        status = ScanSessionStatus.SUCCEEDED,
                        processedCount = importedCount,
                        importedCount = importedCount,
                        checkpoint = "",
                        errorMessage = "",
                        updatedAt = System.currentTimeMillis()
                    ))
                },
                onProgress = { importedCount, checkpoint ->
                    session = session.copy(
                        processedCount = importedCount,
                        importedCount = importedCount,
                        checkpoint = checkpoint,
                        updatedAt = System.currentTimeMillis()
                    )
                    setProgress(workDataOf(KEY_IMPORTED_COUNT to importedCount))
                }
            )
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                sessionDao.upsert(session.copy(status = ScanSessionStatus.CANCELLED, updatedAt = System.currentTimeMillis()))
            }
            throw cancelled
        }
        return scanResult.fold(
            onSuccess = { importedCount ->
                AppDiagnostics.record(applicationContext, "nas_scan", "succeeded")
                Result.success(workDataOf(KEY_IMPORTED_COUNT to importedCount))
            },
            onFailure = { error ->
                val retry = runAttemptCount < MAX_RETRY_ATTEMPTS
                AppDiagnostics.record(applicationContext, "nas_scan", if (retry) "retry" else "failed")
                val invalidCheckpoint = error.message == "扫描检查点已失效"
                sessionDao.upsert(session.copy(
                    status = if (retry) ScanSessionStatus.RETRYING else ScanSessionStatus.FAILED,
                    importedCount = if (invalidCheckpoint) 0 else session.importedCount,
                    processedCount = if (invalidCheckpoint) 0 else session.processedCount,
                    checkpoint = if (invalidCheckpoint) "" else session.checkpoint,
                    errorMessage = error.message.orEmpty(),
                    updatedAt = System.currentTimeMillis()
                ))
                if (retry) Result.retry() else Result.failure()
            }
        )
    }

    companion object {
        const val KEY_SOURCE_ID = "source_id"
        const val KEY_IMPORTED_COUNT = "imported_count"
        private const val MAX_RETRY_ATTEMPTS = 5
    }
}

object NasScanScheduler {
    fun workName(sourceId: Long) = "yuanbao_nas_scan_$sourceId"

    fun enqueue(context: Context, sourceId: Long, replace: Boolean = false) {
        val workName = workName(sourceId)
        val request = OneTimeWorkRequestBuilder<NasScanWorker>()
            .setInputData(workDataOf(NasScanWorker.KEY_SOURCE_ID to sourceId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(workName)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(workName, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context, sourceId: Long) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName(sourceId))
    }
}
