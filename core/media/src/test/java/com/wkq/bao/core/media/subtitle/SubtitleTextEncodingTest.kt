package com.wkq.bao.core.media.subtitle

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleTextEncodingTest {
    @Test fun preservesUtf8AndChinese() {
        val text = "字幕 Subtitle"
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertEquals(text, String(SubtitleTextEncoding.utf8(bytes, 0, bytes.size), Charsets.UTF_8))
    }
    @Test fun convertsUtf16WithBomAndGb18030() {
        val text = "中文字幕"
        for (charset in listOf(Charsets.UTF_16, charset("GB18030"))) {
            val bytes = text.toByteArray(charset)
            assertEquals(text, String(SubtitleTextEncoding.utf8(bytes, 0, bytes.size), Charsets.UTF_8))
        }
    }
    @Test fun readsOnlyRequestedSlice() {
        val bytes = "prefix字幕suffix".toByteArray(Charsets.UTF_8)
        assertEquals("字幕", String(SubtitleTextEncoding.utf8(bytes, 6, 6), Charsets.UTF_8))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsOversizedSubtitle() {
        SubtitleTextEncoding.utf8(byteArrayOf(), 0, 8 * 1024 * 1024 + 1)
    }
}
