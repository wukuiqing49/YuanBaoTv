package com.wkq.bao.core.media.parser

import com.wkq.bao.core.database.entity.MediaSeriesType
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaFileNameParserTest {
    @Test fun `long season marker keeps explicit season and episode`() {
        val result = MediaFileNameParser.parse("小猪佩奇第四季.Peppa.Pig.Season.4.E02.4K.WEB-DL.mp4", "/TV/Season 1")
        assertEquals(4, result.seasonNumber)
        assertEquals(2, result.episodeNumber)
        assertEquals(MediaSeriesType.TV, result.mediaType)
    }
    @Test fun `resolution is not a season episode marker`() {
        listOf("3840x2160", "1920x1080", "1280x720").forEach { resolution ->
            val result = MediaFileNameParser.parse("一分钱_${resolution}_mp4hd3v2.mp4")
            assertEquals(MediaSeriesType.MOVIE, result.mediaType)
            assertEquals(1, result.seasonNumber)
            assertEquals(false, result.seriesTitle.contains(resolution))
            assertEquals("一分钱", result.seriesTitle)
        }
        val episode = MediaFileNameParser.parse("Example.01x02.1920x1080.mkv")
        assertEquals(1, episode.seasonNumber)
        assertEquals(2, episode.episodeNumber)
    }

    @Test fun `Chinese season and hua episode markers are parsed together`() {
        val result = MediaFileNameParser.parse("熊出没之怪兽计划第二季第52话 (超高清SDR).mp4")
        assertEquals("熊出没之怪兽计划", result.seriesTitle)
        assertEquals(2, result.seasonNumber)
        assertEquals(52, result.episodeNumber)
        assertEquals(MediaSeriesType.TV, result.mediaType)
        assertEquals(12, MediaFileNameParser.parse("第03話.mkv", "/动画/Example/第十二季").seasonNumber)
        assertEquals(3, MediaFileNameParser.parse("第03話.mkv", "/动画/Example/第十二季").episodeNumber)
    }

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
    @Test
    fun `ad prefix and encoding tags are stripped cleanly`() {
        val result = MediaFileNameParser.parse("[电影天堂www.dy2018.com]流浪地球2.HD.1080p.国语中字.mkv")
        assertEquals("流浪地球2", result.seriesTitle)
        assertEquals(MediaSeriesType.MOVIE, result.mediaType)

        val result2 = MediaFileNameParser.parse("【4K超高清】星际穿越.2014.BluRay.REMUX.HEVC.Atmos-CHD.mkv")
        assertEquals("星际穿越 2014", result2.seriesTitle)
    }

    @Test
    fun `episode release tag is stripped and does not contaminate episode title`() {
        val result = MediaFileNameParser.parse("小猪佩奇.S04E01-OurTV.mp4")
        assertEquals("小猪佩奇", result.seriesTitle)
        assertEquals(4, result.seasonNumber)
        assertEquals(1, result.episodeNumber)
        assertEquals("", result.episodeTitle)

        val resultWithTitle = MediaFileNameParser.parse("Peppa.Pig.S04E05.The.Golden.Boots-OurTV.mp4")
        assertEquals("The Golden Boots", resultWithTitle.episodeTitle)
    }

    @Test
    fun `ep and anime bracket episodes are parsed cleanly`() {
        val epResult = MediaFileNameParser.parse("斗破苍穹.EP05.1080p.mp4")
        assertEquals("斗破苍穹", epResult.seriesTitle)
        assertEquals(1, epResult.seasonNumber)
        assertEquals(5, epResult.episodeNumber)
        assertEquals(MediaSeriesType.TV, epResult.mediaType)

        val animeResult = MediaFileNameParser.parse("[DBD-Raws][进击的巨人][76][1080P][BDRip].mkv")
        assertEquals("进击的巨人", animeResult.seriesTitle)
        assertEquals(76, animeResult.episodeNumber)
        assertEquals(MediaSeriesType.TV, animeResult.mediaType)
    }
}
