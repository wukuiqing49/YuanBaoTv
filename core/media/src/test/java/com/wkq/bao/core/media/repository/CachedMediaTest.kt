package com.wkq.bao.core.media.repository

import com.wkq.bao.core.database.entity.CachedMediaRow
import org.junit.Assert.*
import org.junit.Test

class CachedMediaTest {
    private fun row(episode: Long, season: Long, uri: String, size: Long = 100) =
        CachedMediaRow(1, "Series", "TV", "", season, season.toInt(), episode, episode.toInt(), episode, uri, size, "USB_DRIVE")
    @Test fun `multiple copies count once per episode and once per physical uri`() {
        val result = groupCachedMedia(listOf(row(1, 1, "a"), row(1, 1, "b"), row(1, 1, "a"), row(2, 2, "c"))).single()
        assertEquals(2, result.seasonCount)
        assertEquals(2, result.episodeCount)
        assertEquals(300L, result.totalBytes)
    }
    @Test fun `empty lists and unknown byte sizes remain valid`() {
        assertTrue(groupCachedMedia(emptyList()).isEmpty())
        assertEquals(0L, groupCachedMedia(listOf(row(1, 1, "a", -1))).single().totalBytes)
    }
}
