package com.wkq.bao.feature.app

import com.wkq.bao.feature.app.utils.MediaLabels
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaLabelsTest {
    @Test
    fun `home titles retain Chinese names and group split episodes`() {
        assertEquals("小猪佩奇", MediaLabels.displayTitle("小猪佩奇 Peppa Pig"))
        assertEquals("哆啦A梦", MediaLabels.displayTitle("哆啦A梦"))
        assertEquals("动画第二季", MediaLabels.homeCollectionKey("动画第二季 第12话 (超高清SDR)"))
        assertEquals("The Office", MediaLabels.displayTitle("The Office"))
    }

    @Test
    fun `missing metadata leaves no placeholder or duplicate category`() {
        assertEquals(listOf("电影"), MediaLabels.metadataValues("-", "电影", "", " 电影 "))
        assertEquals(emptyList<String>(), MediaLabels.metadataValues(" ", "--", "UNKNOWN"))
    }

    @Test
    fun `metadata keeps year and genre in display order`() {
        assertEquals(listOf("2024", "Adventure"), MediaLabels.metadataValues(" 2024 ", "Adventure", "adventure"))
    }
}
