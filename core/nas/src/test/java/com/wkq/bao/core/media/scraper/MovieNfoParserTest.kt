package com.wkq.bao.core.media.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MovieNfoParserTest {
    @Test
    fun `parses movie display fields`() {
        val nfo = """<movie><title>影片</title><originaltitle>Movie</originaltitle><year>2024</year><genre>剧情</genre><genre>悬疑</genre><plot>电影简介</plot></movie>"""
        val metadata = MovieNfoParser.parse(nfo.byteInputStream())
        assertEquals("影片", metadata?.title)
        assertEquals("Movie", metadata?.originalTitle)
        assertEquals("2024", metadata?.year)
        assertEquals("剧情 / 悬疑", metadata?.genre)
        assertEquals("电影简介", metadata?.description)
    }

    @Test
    fun `supports unicode BOM and rejects encoded doctype`() {
        val nfo = "<movie><title>影片</title></movie>"
        assertEquals("影片", MovieNfoParser.parse(nfo.toByteArray(Charsets.UTF_16).inputStream())?.title)
        assertEquals("影片", MovieNfoParser.parse(("\uFEFF" + nfo).byteInputStream())?.title)
        val malicious = "<!DOCTYPE movie [<!ENTITY x SYSTEM 'https://example.com/'>]><movie><title>&x;</title></movie>"
        assertNull(MovieNfoParser.parse(malicious.toByteArray(Charsets.UTF_16).inputStream()))
    }

    @Test
    fun `rejects external entities and oversized files`() {
        assertNull(MovieNfoParser.parse("""<!DOCTYPE movie [<!ENTITY x SYSTEM "file:///etc/passwd">]><movie><plot>&x;</plot></movie>""".byteInputStream()))
        assertNull(MovieNfoParser.parse(ByteArray(256 * 1024 + 1).inputStream()))
    }
}
