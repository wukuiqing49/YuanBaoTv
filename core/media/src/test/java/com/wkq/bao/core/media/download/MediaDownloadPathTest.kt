package com.wkq.bao.core.media.download

import com.wkq.bao.core.database.entity.MediaSeriesEntity
import org.junit.Assert.*
import org.junit.Test

class MediaDownloadPathTest {
    @Test fun `series folders preserve identity and stay within filesystem byte limits`() {
        val series = MediaSeriesEntity(id = Long.MAX_VALUE, title = "剧😀".repeat(150), year = "2026", type = "TV")
        val folder = MediaDownloadPath.folder(series)
        assertTrue(folder.endsWith(" [${series.id}]"))
        assertTrue(folder.toByteArray(Charsets.UTF_8).size <= 240)
        assertEquals("TVShows", MediaDownloadPath.category(series))
    }
    @Test fun `episode naming preserves season episode and source identity`() {
        val series = MediaSeriesEntity(id = 42, title = "Edited title", scanTitle = "Original", type = "TV")
        assertEquals("Original.S02E03.mkv", MediaDownloadPath.fileName(series, 2, 3, "remote.mkv"))
        assertEquals("Season 02", MediaDownloadPath.season(2))
        val long = MediaDownloadPath.fileName(series.copy(scanTitle = "剧".repeat(200)), 12, 23, "remote.mp4")
        assertTrue(long.endsWith(".S12E23.mp4"))
        assertTrue(long.toByteArray(Charsets.UTF_8).size <= 240)
    }
    @Test fun `movies retain original names with safe path characters`() {
        val movie = MediaSeriesEntity(id = 3, title = "Movie", type = "MOVIE")
        assertEquals("Movies", MediaDownloadPath.category(movie))
        assertEquals("movie_name.mp4", MediaDownloadPath.fileName(movie, 1, 1, "movie:name.mp4"))
    }
}
