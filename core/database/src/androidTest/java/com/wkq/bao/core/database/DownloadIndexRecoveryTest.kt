package com.wkq.bao.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wkq.bao.core.database.entity.DownloadTaskEntity
import com.wkq.bao.core.database.entity.DownloadTaskStatus
import com.wkq.bao.core.database.entity.DownloadTaskErrorCode
import com.wkq.bao.core.database.entity.ScanSessionEntity
import com.wkq.bao.core.database.entity.ScanSessionKind
import com.wkq.bao.core.database.entity.ScanSessionStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
