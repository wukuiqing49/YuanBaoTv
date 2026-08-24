package com.wkq.bao.feature.app

import com.wkq.bao.core.database.entity.EpisodeEntity
import com.wkq.bao.core.media.repository.PlaybackNavigationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initialization resolves the season only when the caller did not provide it`() = runTest {
        val repository = FakePlaybackNavigationRepository()
        val viewModel = PlayerViewModel(repository)

        viewModel.initialize(initialSeasonId = 0L, episodeId = repository.currentEpisode.id)
        advanceUntilIdle()

        assertEquals(repository.currentEpisode.seasonId, viewModel.uiState.value.initialSeasonId)
        assertEquals(1, repository.resolveSeasonCalls)
    }

    @Test
    fun `next episode emits playback event or terminal event`() = runTest {
        val repository = FakePlaybackNavigationRepository()
        val viewModel = PlayerViewModel(repository)

        val nextEvent = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.first() }
        viewModel.playNextEpisode(repository.currentEpisode.seriesId, repository.currentEpisode.id)
        assertEquals(PlayerEvent.PlayNext(repository.nextEpisode), nextEvent.await())

        repository.nextEpisodeResult = null
        val lastEvent = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.first() }
        viewModel.playNextEpisode(repository.currentEpisode.seriesId, repository.currentEpisode.id)
        assertEquals(PlayerEvent.LastEpisodeReached, lastEvent.await())
    }

    private class FakePlaybackNavigationRepository : PlaybackNavigationRepository {
        val currentEpisode = EpisodeEntity(id = 1L, seriesId = 2L, seasonId = 3L, episodeNumber = 1)
        val nextEpisode = EpisodeEntity(id = 4L, seriesId = 2L, seasonId = 5L, episodeNumber = 1)
        var nextEpisodeResult: EpisodeEntity? = nextEpisode
        var resolveSeasonCalls = 0

        override suspend fun resolveSeasonId(episodeId: Long): Long {
            resolveSeasonCalls++
            return currentEpisode.seasonId
        }

        override suspend fun findNextEpisode(seriesId: Long, episodeId: Long): EpisodeEntity? = nextEpisodeResult
    }
}
