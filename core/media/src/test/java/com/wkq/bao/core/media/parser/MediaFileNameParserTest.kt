package com.wkq.bao.core.media.parser

import com.wkq.bao.core.database.entity.MediaSeriesType
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaFileNameParserTest {

    @Test
    fun `season and episode naming is classified as tv`() {
        val parsed = MediaFileNameParser.parse("Example.Show.S02E03.1080p.mkv")

        assertEquals(MediaSeriesType.TV, parsed.mediaType)
        assertEquals(2, parsed.seasonNumber)
        assertEquals(3, parsed.episodeNumber)
    }

    @Test
    fun `unnumbered video is classified as movie`() {
        val parsed = MediaFileNameParser.parse("A.Feature.Film.2025.2160p.mkv")

        assertEquals(MediaSeriesType.MOVIE, parsed.mediaType)
        assertEquals(1, parsed.seasonNumber)
        assertEquals(1, parsed.episodeNumber)
    }
}
