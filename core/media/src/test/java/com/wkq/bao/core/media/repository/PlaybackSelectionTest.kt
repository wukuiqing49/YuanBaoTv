package com.wkq.bao.core.media.repository

import com.wkq.bao.core.database.entity.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackSelectionTest {
    private val seasons = listOf(SeasonEntity(id = 20, seriesId = 1, seasonNumber = 1), SeasonEntity(id = 10, seriesId = 1, seasonNumber = 2))
    private val episodes = listOf(EpisodeEntity(id = 1, seriesId = 1, seasonId = 20, episodeNumber = 1),
        EpisodeEntity(id = 2, seriesId = 1, seasonId = 20, episodeNumber = 2),
        EpisodeEntity(id = 3, seriesId = 1, seasonId = 10, episodeNumber = 1))
    private fun history(id: Long, completed: Boolean = false) = WatchHistoryEntity(seriesId = 1, seasonId = 20,
        episodeId = id, positionMs = 30000, completed = completed)

    @Test fun `fresh playback follows season order rather than database ids`() {
        assertEquals(1L, choosePlaybackTarget(episodes.reversed(), seasons.reversed(), null, false)?.episode?.id)
    }
    @Test fun `unfinished episode resumes and exposes season number`() {
        val target = choosePlaybackTarget(episodes, seasons, history(3), false)!!
        assertEquals(3L, target.episode.id)
        assertTrue(target.resume)
        assertEquals(2, target.seasonNumber)
    }
    @Test fun `completed episode advances across season`() {
        val target = choosePlaybackTarget(episodes, seasons, history(2, true), false)!!
        assertEquals(3L, target.episode.id)
        assertFalse(target.resume)
    }
    @Test fun `completed movie restarts without advancing`() {
        assertEquals(1L, choosePlaybackTarget(episodes.take(1), seasons, history(1, true), true)?.episode?.id)
    }
    @Test fun `unavailable history falls back and empty library stays empty`() {
        assertEquals(1L, choosePlaybackTarget(episodes, seasons, history(999), false)?.episode?.id)
        assertNull(choosePlaybackTarget(emptyList(), seasons, history(1), false))
    }
    @Test fun `finished series stays at last episode without resuming`() {
        val target = choosePlaybackTarget(episodes, seasons, history(3, true), false)!!
        assertEquals(3L, target.episode.id)
        assertFalse(target.resume)
    }
}
