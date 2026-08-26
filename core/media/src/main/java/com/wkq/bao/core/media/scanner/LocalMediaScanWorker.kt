package com.wkq.bao.core.media.scanner

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
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
import java.security.MessageDigest

class LocalMediaScanWorker(appContext: Context, parameters: WorkerParameters) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        AppDiagnostics.record(applicationContext, "local_scan", "started")
        val uri = inputData.getString(KEY_TREE_URI)?.let(Uri::parse) ?: return Result.failure().also {
            AppDiagnostics.record(applicationContext, "local_scan", "invalid_uri")
        }
        val database = AppDatabase.getInstance(applicationContext)
        val sessionDao = database.scanSessionDao()
        val sourceKey = uri.toString()
        var session = (sessionDao.get(ScanSessionKind.LOCAL, sourceKey) ?: ScanSessionEntity(
            id = LocalMediaScanScheduler.sessionId(sourceKey),
            kind = ScanSessionKind.LOCAL,
            sourceKey = sourceKey,
            workName = LocalMediaScanScheduler.workName(sourceKey)
        )).copy(status = ScanSessionStatus.RUNNING, errorMessage = "", updatedAt = System.currentTimeMillis())
        sessionDao.upsert(session)
        val scanResult = try {
            LocalMediaScanner(applicationContext, database).scanAndImport(
                treeUri = uri,
                resumeAfterUri = session.checkpoint,
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
        return scanResult
            .fold(
                onSuccess = { importedCount ->
                    AppDiagnostics.record(applicationContext, "local_scan", "succeeded")
                    Result.success(workDataOf(KEY_IMPORTED_COUNT to importedCount))
                },
                onFailure = { error ->
                    val retry = runAttemptCount < MAX_RETRY_ATTEMPTS
                    AppDiagnostics.record(applicationContext, "local_scan", if (retry) "retry" else "failed")
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
        const val KEY_TREE_URI = "tree_uri"
        const val KEY_IMPORTED_COUNT = "imported_count"
        private const val MAX_RETRY_ATTEMPTS = 5
    }
}

object LocalMediaScanScheduler {
    private fun stableKey(sourceKey: String): String = MessageDigest.getInstance("SHA-256")
        .digest(sourceKey.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun workName(sourceKey: String) = "yuanbao_local_scan_${stableKey(sourceKey)}"
    fun sessionId(sourceKey: String) = "local:${stableKey(sourceKey)}"

    fun enqueue(context: Context, treeUri: Uri, replace: Boolean = false) {
        val workName = workName(treeUri.toString())
        val request = OneTimeWorkRequestBuilder<LocalMediaScanWorker>()
            .setInputData(workDataOf(LocalMediaScanWorker.KEY_TREE_URI to treeUri.toString()))
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(workName)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(
                workName,
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
    }

    fun cancel(context: Context, treeUri: Uri) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName(treeUri.toString()))
    }
}
