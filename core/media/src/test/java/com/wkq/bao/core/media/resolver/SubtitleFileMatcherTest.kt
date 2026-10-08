package com.wkq.bao.core.media.resolver

import org.junit.Assert.*
import org.junit.Test

class SubtitleFileMatcherTest {
    @Test fun acceptsExactAndLanguageSuffixes() {
        assertEquals("application/x-subrip", SubtitleFileMatcher.mimeType("Movie.mkv", "MOVIE.SRT"))
        assertEquals("text/vtt", SubtitleFileMatcher.mimeType("Movie.mkv", "Movie.zh.vtt"))
        assertEquals("text/x-ssa", SubtitleFileMatcher.mimeType("Movie.mkv", "Movie-en.ass"))
        assertEquals("text/x-ssa", SubtitleFileMatcher.mimeType("Movie.mkv", "Movie_zh.ssa"))
    }

    @Test fun rejectsOtherEpisodesAndNonSubtitles() {
        assertNull(SubtitleFileMatcher.mimeType("Show.S01E01.mkv", "Show.S01E02.srt"))
        assertNull(SubtitleFileMatcher.mimeType("Movie.mkv", "Movie2.srt"))
        assertNull(SubtitleFileMatcher.mimeType("Movie.mkv", "Movie.xml"))
        assertNull(SubtitleFileMatcher.mimeType("Movie.mkv", "Other.srt"))
        assertNull(SubtitleFileMatcher.mimeType(".mkv", ".srt"))
    }

    @Test fun prefersExactNamesToLanguageVariants() {
        assertTrue(SubtitleFileMatcher.isExactMatch("Movie.mkv", "movie.srt"))
        assertFalse(SubtitleFileMatcher.isExactMatch("Movie.mkv", "movie.zh.srt"))
    }

    @Test fun recognizesLanguageSuffixWithoutUsingWordsInVideoTitle() {
        assertEquals("zh", SubtitleFileMatcher.language("Movie.mkv", "Movie.zh-CN.srt"))
        assertEquals("en", SubtitleFileMatcher.language("Movie.mkv", "Movie_eng.ass"))
        assertEquals("ja", SubtitleFileMatcher.language("Movie.mkv", "Movie.jpn.ssa"))
        assertNull(SubtitleFileMatcher.language("Movie.eng.mkv", "Movie.eng.srt"))
        assertNull(SubtitleFileMatcher.language("Movie.mkv", "Movie.commentary.srt"))
    }
}
