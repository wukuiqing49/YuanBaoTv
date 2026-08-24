package com.wkq.bao.core.media.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadChunkPlannerTest {
    @Test
    fun `creates contiguous chunks and preserves the exact total length`() {
        val total = DownloadChunkPlanner.CHUNK_SIZE_BYTES * 2 + 17L

        val chunks = DownloadChunkPlanner.create(taskId = 42L, totalBytes = total)

        assertEquals(3, chunks.size)
        assertEquals(0L, chunks[0].startByte)
        assertEquals(DownloadChunkPlanner.CHUNK_SIZE_BYTES, chunks[1].startByte)
        assertEquals(DownloadChunkPlanner.CHUNK_SIZE_BYTES * 2, chunks[2].startByte)
        assertEquals(17L, chunks[2].byteCount)
        assertEquals(total, chunks.sumOf { it.byteCount })
        assertTrue(chunks.zipWithNext().all { (first, second) -> first.startByte + first.byteCount == second.startByte })
    }

    @Test
    fun `uses a task isolated part file name`() {
        val chunk = DownloadChunkPlanner.create(taskId = 9L, totalBytes = 1L).single()

        assertEquals("chunk_9_0.part", chunk.partName)
    }
}
