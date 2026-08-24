package com.wkq.bao.core.media.download

import com.wkq.bao.core.database.entity.DownloadTaskErrorCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadTaskErrorCodeTest {
    @Test
    fun `retries only transient transport and removable storage errors`() {
        assertTrue(DownloadTaskErrorCode.isRetryable(DownloadTaskErrorCode.NETWORK))
        assertTrue(DownloadTaskErrorCode.isRetryable(DownloadTaskErrorCode.STORAGE_UNAVAILABLE))
    }

    @Test
    fun `does not retry configuration capacity or source integrity errors`() {
        listOf(
            DownloadTaskErrorCode.SOURCE_UNAVAILABLE,
            DownloadTaskErrorCode.SOURCE_CHANGED,
            DownloadTaskErrorCode.STORAGE_CAPACITY,
            DownloadTaskErrorCode.STORAGE_ACCESS,
            DownloadTaskErrorCode.TARGET_EXISTS,
            DownloadTaskErrorCode.UNKNOWN
        ).forEach { code -> assertFalse(DownloadTaskErrorCode.isRetryable(code)) }
    }
}
