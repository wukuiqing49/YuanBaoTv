package com.wkq.bao.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wkq.bao.core.database.entity.DownloadTaskEntity
import com.wkq.bao.core.database.entity.DownloadTaskStatus
import com.wkq.bao.core.database.entity.DownloadTaskErrorCode
import com.wkq.bao.core.database.entity.DownloadChunkEntity
import com.wkq.bao.core.database.entity.DownloadChunkStatus
import com.wkq.bao.core.database.entity.ScanSessionEntity
import com.wkq.bao.core.database.entity.ScanSessionKind
import com.wkq.bao.core.database.entity.ScanSessionStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadIndexRecoveryTest {
    private lateinit var database: AppDatabase
    private val tree = "content://storage/tree/USB"

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun persistedChunksKeepIndependentStatesAcrossStorageReconnect() = runBlocking {
        val dao = database.downloadDao()
        val blockBytes = 8L * 1024 * 1024
        val id = dao.insertTask(task(-60, 0).copy(status = DownloadTaskStatus.DOWNLOADING))
        dao.insertChunks(listOf(
            DownloadChunkEntity(taskId = id, chunkIndex = 0, startByte = 0,
                byteCount = blockBytes, partName = "first.part"),
            DownloadChunkEntity(taskId = id, chunkIndex = 1, startByte = blockBytes,
                byteCount = blockBytes, partName = "second.part")
        ))
        val persisted = dao.getChunks(id)
        assertTrue(persisted.all { it.id > 0 })
        assertEquals(2, persisted.map { it.id }.toSet().size)
        dao.updateChunk(persisted.first().copy(status = DownloadChunkStatus.ASSEMBLED, sha256 = "verified"))
        dao.updateAssembledBytesWhileDownloading(id, blockBytes)
        dao.waitForStorage(id)
        dao.transitionTaskStatus(id, DownloadTaskStatus.WAITING_STORAGE, DownloadTaskStatus.WAITING)
        dao.claimWaitingTask(id)
        assertEquals(blockBytes, dao.getTaskById(id)!!.assembledBytes)
        val restored = database.downloadDao().getChunks(id)
        assertEquals(DownloadChunkStatus.ASSEMBLED, restored[0].status)
        assertEquals("verified", restored[0].sha256)
        assertEquals(DownloadChunkStatus.WAITING, restored[1].status)
    }

    @Test
    fun failedTaskCanBeDeferredWithoutBlockingOtherWaitingTasks() = runBlocking {
        val dao = database.downloadDao()
        val failedId = dao.insertTask(task(-50, 0).copy(status = DownloadTaskStatus.WAITING, createdAt = 1))
        val nextId = dao.insertTask(task(-51, 0).copy(status = DownloadTaskStatus.WAITING, createdAt = 2))
        assertEquals(failedId, dao.getNextTaskByStatus(DownloadTaskStatus.WAITING)?.id)
        assertEquals(1, dao.claimWaitingTask(failedId))
        assertEquals(1, dao.failDownloadingTask(failedId, "offline", DownloadTaskErrorCode.NETWORK))
        assertEquals(nextId, dao.getNextTaskByStatus(DownloadTaskStatus.WAITING)?.id)
        assertEquals(1, dao.transitionTaskStatus(failedId, DownloadTaskStatus.FAILED, DownloadTaskStatus.PAUSED))
        assertEquals(0, dao.transitionTaskStatus(failedId, DownloadTaskStatus.FAILED, DownloadTaskStatus.WAITING))
        assertEquals(DownloadTaskStatus.PAUSED, dao.getTaskById(failedId)?.status)
    }

    @Test
    fun completedFilesRemainPendingAfterOtherDownloadsFail() = runBlocking {
        val dao = database.downloadDao()
        dao.insertTask(task(-1, 10))
        dao.insertTask(task(-2, 20))
        dao.insertTask(task(-3, 0).copy(status = DownloadTaskStatus.FAILED))
        dao.insertTask(task(4, 30).copy(targetUri = "content://storage/tree/OTHER"))

        assertEquals(listOf(tree), dao.getRawDownloadTreesPendingIndex())
        assertEquals(20L, dao.getLatestRawDownloadCompletion(tree))
        assertNull(dao.getLatestRawDownloadCompletion("content://storage/tree/OTHER"))
        // 重建查询入口，不依赖下载 Worker 的内存目录集合。
        assertEquals(listOf(tree), database.downloadDao().getRawDownloadTreesPendingIndex())
    }

    @Test
    fun unpluggedTaskKeepsProgressAndCannotOverrideUserPause() = runBlocking {
        val dao = database.downloadDao()
        val id = dao.insertTask(task(-10, 0).copy(status = DownloadTaskStatus.DOWNLOADING,
            downloadedBytes = 123, assembledBytes = 100, targetLibraryId = "library-a"))
        assertEquals(1, dao.waitForStorage(id))
        assertEquals(123L, dao.getTaskById(id)!!.downloadedBytes)
        assertEquals("library-a", dao.getTaskById(id)!!.targetLibraryId)
        assertEquals(0, dao.failDownloadingTask(id, "late network error", DownloadTaskErrorCode.NETWORK))
        assertEquals(1, dao.transitionTaskStatus(id, DownloadTaskStatus.WAITING_STORAGE, DownloadTaskStatus.PAUSED))
        assertEquals(0, dao.transitionTaskStatus(id, DownloadTaskStatus.WAITING_STORAGE, DownloadTaskStatus.WAITING))
        assertEquals(DownloadTaskStatus.PAUSED, dao.getTaskById(id)!!.status)
    }

    @Test
    fun indexedVideoAlsoRescansItsDownloadedCompanions() = runBlocking {
        database.downloadDao().insertTask(task(25, 90).copy(targetLibraryId = "library-a"))
        assertEquals(listOf(tree), database.downloadDao().getRawDownloadTreesPendingIndex())
    }

    @Test
    fun onlySuccessfulScanCoveringAllCompletionsClearsPendingTree() = runBlocking {
        val dao = database.downloadDao()
        dao.insertTask(task(-1, 10))
        val scan = ScanSessionEntity(
            id = "local:usb", kind = ScanSessionKind.LOCAL, sourceKey = tree,
            workName = "scan_usb", startedAt = 15, status = ScanSessionStatus.RUNNING
        )
        database.scanSessionDao().upsert(scan)
        assertEquals(listOf(tree), dao.getRawDownloadTreesPendingIndex())

        database.scanSessionDao().upsert(scan.copy(status = ScanSessionStatus.SUCCEEDED))
        assertEquals(emptyList<String>(), dao.getRawDownloadTreesPendingIndex())

        dao.insertTask(task(-2, 20))
        assertEquals(listOf(tree), dao.getRawDownloadTreesPendingIndex())
        database.scanSessionDao().upsert(scan.copy(startedAt = 25, status = ScanSessionStatus.FAILED))
        assertEquals(listOf(tree), dao.getRawDownloadTreesPendingIndex())
        database.scanSessionDao().upsert(scan.copy(startedAt = 25, status = ScanSessionStatus.CANCELLED))
        assertEquals(listOf(tree), dao.getRawDownloadTreesPendingIndex())
        database.scanSessionDao().upsert(scan.copy(startedAt = 25, status = ScanSessionStatus.SUCCEEDED))
        assertEquals(emptyList<String>(), dao.getRawDownloadTreesPendingIndex())
    }

    @Test
    fun progressAndErrorsCannotOverwriteUserActionsOrSuccessfulTasks() = runBlocking {
        val dao = database.downloadDao()
        val id = dao.insertTask(task(-1, 0).copy(status = DownloadTaskStatus.WAITING))
        assertEquals(1, dao.claimWaitingTask(id))
        assertEquals(1, dao.updateProgressWhileDownloading(id, 20, 100, 1))
        assertEquals(1, dao.transitionTaskStatus(id, DownloadTaskStatus.DOWNLOADING, DownloadTaskStatus.PAUSED))
        assertEquals(0, dao.updateProgressWhileDownloading(id, 30, 100, 1))
        assertEquals(0, dao.updateAssembledBytesWhileDownloading(id, 30))
        assertEquals(0, dao.failDownloadingTask(id, "offline", DownloadTaskErrorCode.NETWORK))
        assertEquals(DownloadTaskStatus.PAUSED, dao.getTaskById(id)?.status)
        assertEquals(20L, dao.getTaskById(id)?.downloadedBytes)
        assertEquals(1, dao.cancelPendingTask(id))
        assertEquals(0, dao.transitionTaskStatus(id, DownloadTaskStatus.PAUSED, DownloadTaskStatus.WAITING))
        assertEquals(0, dao.failDownloadingTask(id, "offline", DownloadTaskErrorCode.NETWORK))
        assertEquals(DownloadTaskStatus.CANCELLED, dao.getTaskById(id)?.status)
        val completedId = dao.insertTask(task(-2, 10))
        assertEquals(0, dao.cancelPendingTask(completedId))
        assertEquals(0, dao.failDownloadingTask(completedId, "late failure", DownloadTaskErrorCode.UNKNOWN))
        assertEquals(DownloadTaskStatus.SUCCESS, dao.getTaskById(completedId)?.status)
    }

    private fun task(episodeId: Long, finishedAt: Long) = DownloadTaskEntity(
        seriesId = 0, seasonId = 0, episodeId = episodeId, sourceUri = "smb://nas/media/$episodeId.mp4",
        targetUri = tree, status = DownloadTaskStatus.SUCCESS, finishedAt = finishedAt
    )
}
