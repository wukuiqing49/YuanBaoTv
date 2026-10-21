package com.wkq.bao.core.media.parser

import com.wkq.bao.core.database.entity.MediaSeriesType
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaFileNameParserTest {
    @Test fun `numeric episodes inherit show and season directory`() {
        val result = MediaFileNameParser.parse("03.mkv", "/TV/Example/Season 02")
        assertEquals("Example", result.seriesTitle)
        assertEquals(2, result.seasonNumber)
        assertEquals(3, result.episodeNumber)
        assertEquals(MediaSeriesType.TV, result.mediaType)
    }
    @Test fun `episode only name inherits show directory`() {
        val result = MediaFileNameParser.parse("EP01.mp4", "/TV/Example")
        assertEquals("Example", result.seriesTitle)
        assertEquals(MediaSeriesType.TV, result.mediaType)
    }
    @Test fun `season episode without title inherits show`() {
        val result = MediaFileNameParser.parse("S02E04.mkv", "/TV/Example/Season 02")
        assertEquals("Example", result.seriesTitle)
        assertEquals(4, result.episodeNumber)
    }
    @Test fun `ambiguous numeric file is not silently classified as movie`() {
        assertEquals(MediaSeriesType.LOCAL, MediaFileNameParser.parse("01.mkv", "/Videos").mediaType)
    }
    @Test fun `explicit filename season wins over stale folder name`() {
        assertEquals(3, MediaFileNameParser.parse("Example.S03E01.mkv", "/TV/Example/Season 02").seasonNumber)
    }
    @Test fun `year title is not a numeric episode`() {
        assertEquals(MediaSeriesType.MOVIE, MediaFileNameParser.parse("1917.mkv", "/Movies").mediaType)
    }


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
