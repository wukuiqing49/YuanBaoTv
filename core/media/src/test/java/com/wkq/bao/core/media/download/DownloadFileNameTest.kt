package com.wkq.bao.core.media.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFileNameTest {
    @Test fun `preserves normal names and replaces unsafe separators`() {
        assertEquals("Movie.zh.srt", DownloadFileName.sanitize("Movie.zh.srt"))
        assertEquals("a_b_c.mp4", DownloadFileName.sanitize("a/b:c.mp4"))
    }

    @Test fun `long video and subtitle names retain their extensions`() {
        for (extension in listOf("mp4", "mkv", "srt", "nfo")) {
            val name = DownloadFileName.sanitize("影片".repeat(120) + ".$extension")
            assertTrue(name.length <= 160)
            assertTrue(name.toByteArray(Charsets.UTF_8).size <= 240)
            assertTrue(name.endsWith(".$extension"))
        }
    }

    @Test fun `same prefix remains distinct and deterministic after shortening`() {
        val prefix = "a".repeat(200)
        val first = DownloadFileName.sanitize(prefix + "first.mp4")
        assertNotEquals(first, DownloadFileName.sanitize(prefix + "second.mp4"))
        assertEquals(first, DownloadFileName.sanitize(prefix + "first.mp4"))
    }

    @Test fun `unicode shortening respects byte limits without breaking surrogate pairs`() {
        val name = DownloadFileName.sanitize("影片".repeat(60) + ".mp4")
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 240)
        val emoji = DownloadFileName.sanitize("\uD83D\uDE00".repeat(100) + ".mkv")
        assertEquals(emoji, String(emoji.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        assertTrue(emoji.toByteArray(Charsets.UTF_8).size <= 240)
        assertTrue(emoji.endsWith(".mkv"))
    }
}
