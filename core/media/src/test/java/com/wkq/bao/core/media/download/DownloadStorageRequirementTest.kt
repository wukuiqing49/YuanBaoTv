package com.wkq.bao.core.media.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DownloadStorageRequirementTest {
    @Test
    fun `requires remaining file plus assembly overhead and reserve`() {
        val total = 2L * 1024 * 1024 * 1024
        val completed = 512L * 1024 * 1024

        val required = DownloadStorageRequirement.requiredFreeBytes(total, completed)

        assertEquals(total - completed + DownloadStorageRequirement.MAX_PART_BYTES + DownloadStorageRequirement.SAFETY_RESERVE_BYTES, required)
    }

    @Test
    fun `requires only reserve after all chunks are assembled`() {
        val total = 128L

        assertEquals(
            DownloadStorageRequirement.SAFETY_RESERVE_BYTES,
            DownloadStorageRequirement.requiredFreeBytes(total, total)
        )
    }

    @Test
    fun `rejects invalid progress values`() {
        assertThrows(IllegalArgumentException::class.java) {
            DownloadStorageRequirement.requiredFreeBytes(100L, 101L)
        }
    }

    @Test
    fun `small final chunk requires room for both part and assembled copy`() {
        assertEquals(100L + DownloadStorageRequirement.SAFETY_RESERVE_BYTES,
            DownloadStorageRequirement.requiredFreeBytes(100L, 50L))
    }

    @Test
    fun `overflow saturates rather than accepting insufficient storage`() {
        assertEquals(Long.MAX_VALUE, DownloadStorageRequirement.requiredFreeBytes(Long.MAX_VALUE, 0L))
    }
}
