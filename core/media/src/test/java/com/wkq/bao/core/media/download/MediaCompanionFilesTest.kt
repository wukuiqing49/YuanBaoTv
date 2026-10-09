package com.wkq.bao.core.media.download

import org.junit.Assert.*
import org.junit.Test

class MediaCompanionFilesTest {
    @Test fun languageSubtitlesAndArtworkAreMatched() {
        listOf("Movie.zh.srt", "Movie_EN.ass", "Movie.nfo", "Movie-poster.jpg", "poster.jpg", "fanart.png")
            .forEach { assertTrue(it, MediaCompanionFiles.matches("Movie.mkv", it)) }
    }

    @Test fun otherEpisodesAndUnsafePathsAreExcluded() {
        listOf("Show.S01E02.srt", "Show.S01E010.srt", "../poster.jpg", "folder/poster.jpg", "Show.S01E01.exe")
            .forEach { assertFalse(it, MediaCompanionFiles.matches("Show.S01E01.mkv", it)) }
    }

    @Test fun matchingIsCaseInsensitiveAndKeepsUnicode() {
        assertTrue(MediaCompanionFiles.matches("电影.MKV", "电影.ZH.SRT"))
        assertFalse(MediaCompanionFiles.matches("电影.mkv", "其他电影.srt"))
    }

    @Test fun renamedVideoKeepsMatchingSubtitleAndSharedArtworkName() {
        assertEquals("safe.zh.srt", MediaCompanionFiles.localName("original.mkv", "safe.mkv", "original.zh.srt"))
        assertEquals("poster.jpg", MediaCompanionFiles.localName("original.mkv", "safe.mkv", "poster.jpg"))
    }
}
