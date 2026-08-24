package com.wkq.bao.core.media.repository

import android.content.Context
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.database.entity.NasSourceEntity
import com.wkq.bao.core.database.entity.ScanSessionEntity
import com.wkq.bao.core.media.download.NasSourceRemovalCoordinator
import com.wkq.bao.core.media.download.NasSourceRemovalResult
import com.wkq.bao.core.nas.scanner.NasScanController
import com.wkq.bao.core.media.smb.SmbClientManager
import kotlinx.coroutines.flow.Flow

/** NAS 设置页使用的来源管理命令，集中隔离页面与持久化实现。 */
interface NasSettingsRepository {
    val sources: Flow<List<NasSourceEntity>>

    suspend fun save(source: NasSourceEntity)
    suspend fun setEnabled(source: NasSourceEntity, enabled: Boolean)
    suspend fun testConnection(source: NasSourceEntity): Result<String>
    fun observeScan(sourceId: Long): Flow<ScanSessionEntity?>
    suspend fun enqueueScan(sourceId: Long, retry: Boolean = false)
    suspend fun cancelScan(sourceId: Long)
    suspend fun remove(sourceId: Long): NasSourceRemovalResult
}

class RoomNasSettingsRepository private constructor(
    private val appContext: Context,
    private val database: AppDatabase
) : NasSettingsRepository {
    private val scanController = NasScanController(appContext)

    override val sources: Flow<List<NasSourceEntity>> = database.nasDao().getAllSources()

    override suspend fun save(source: NasSourceEntity) {
        database.nasDao().insertSource(source)
    }

    override suspend fun setEnabled(source: NasSourceEntity, enabled: Boolean) {
        database.nasDao().updateSource(source.copy(enabled = enabled))
    }

    override suspend fun testConnection(source: NasSourceEntity): Result<String> =
        SmbClientManager.testConnection(source)

    override fun observeScan(sourceId: Long): Flow<ScanSessionEntity?> = scanController.observe(sourceId)

    override suspend fun enqueueScan(sourceId: Long, retry: Boolean) = scanController.enqueue(sourceId, retry)

    override suspend fun cancelScan(sourceId: Long) = scanController.cancel(sourceId)

    override suspend fun remove(sourceId: Long): NasSourceRemovalResult =
        NasSourceRemovalCoordinator(appContext).remove(sourceId)

    companion object {
        fun create(context: Context): RoomNasSettingsRepository {
            val appContext = context.applicationContext
            return RoomNasSettingsRepository(appContext, AppDatabase.getInstance(appContext))
        }
    }
}
