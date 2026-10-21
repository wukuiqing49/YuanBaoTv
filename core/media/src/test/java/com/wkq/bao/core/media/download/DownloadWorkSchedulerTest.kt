package com.wkq.bao.core.media.download

import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadWorkSchedulerTest {

    @Test
    fun queueKeepsSingleActiveWorker() {
        assertEquals(ExistingWorkPolicy.KEEP, DownloadWorkScheduler.existingWorkPolicy)
    }
}
