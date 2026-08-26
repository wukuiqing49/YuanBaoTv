package com.wkq.bao.core.media.repository

import com.wkq.bao.core.nas.browser.NasFileEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NasMediaFileClassifierTest {

    @Test
    fun `recognizes supported video files`() {
        assertTrue(NasMediaFileClassifier.isVideo(file("Show.S01E01.mkv")))
        assertTrue(NasMediaFileClassifier.isVideo(file("movie.mp4")))
        assertFalse(NasMediaFileClassifier.isVideo(file("poster.jpg")))
    }

    @Test
    fun `only matches danmaku beside its video`() {
        val video = file("Show.S01E01.mkv", "shows/Show.S01E01.mkv")

        assertTrue(NasMediaFileClassifier.belongsToVideo(file("Show.S01E01.xml", "shows/Show.S01E01.xml"), video))
        assertTrue(NasMediaFileClassifier.belongsToVideo(file("Show.S01E01.zh.ass", "shows/Show.S01E01.zh.ass"), video))
        assertFalse(NasMediaFileClassifier.belongsToVideo(file("Show.S01E02.xml", "shows/Show.S01E02.xml"), video))
        assertFalse(NasMediaFileClassifier.belongsToVideo(file("Show.S01E01.xml", "other/Show.S01E01.xml"), video))
    }

    private fun file(name: String, path: String = name): NasFileEntry = NasFileEntry(
        name = name,
        path = path,
        isDirectory = false,
        size = 1L,
        lastModifiedAt = 1L
    )
}
