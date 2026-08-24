package com.wkq.bao.core.media.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DownloadStorageRequirementTest {
    @Test
    fun `requires one next chunk and reserve while streaming assembly`() {
        val total = 2L * 1024 * 1024 * 1024
        val completed = 512L * 1024 * 1024

        val required = DownloadStorageRequirement.requiredFreeBytes(total, completed)

        assertEquals(DownloadStorageRequirement.MAX_PART_BYTES + DownloadStorageRequirement.SAFETY_RESERVE_BYTES, required)
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
}
