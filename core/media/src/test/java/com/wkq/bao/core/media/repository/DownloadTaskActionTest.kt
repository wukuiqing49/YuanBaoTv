package com.wkq.bao.core.media.repository

import com.wkq.bao.core.database.entity.DownloadTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadTaskActionTest {

    @Test
    fun `active task is paused`() {
        assertEquals(DownloadTaskStatus.PAUSED, DownloadTaskAction.nextToggleStatus(DownloadTaskStatus.WAITING))
        assertEquals(DownloadTaskStatus.PAUSED, DownloadTaskAction.nextToggleStatus(DownloadTaskStatus.DOWNLOADING))
    }

    @Test
    fun `paused or failed task is resumed`() {
        assertEquals(DownloadTaskStatus.WAITING, DownloadTaskAction.nextToggleStatus(DownloadTaskStatus.PAUSED))
        assertEquals(DownloadTaskStatus.WAITING, DownloadTaskAction.nextToggleStatus(DownloadTaskStatus.FAILED))
    }

    @Test
    fun `terminal task cannot be toggled`() {
        assertNull(DownloadTaskAction.nextToggleStatus(DownloadTaskStatus.SUCCESS))
        assertNull(DownloadTaskAction.nextToggleStatus(DownloadTaskStatus.CANCELLED))
    }
}
