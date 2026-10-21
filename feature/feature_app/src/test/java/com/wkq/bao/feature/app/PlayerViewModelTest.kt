package com.wkq.bao.feature.app

import com.wkq.bao.core.database.entity.EpisodeEntity
import com.wkq.bao.core.media.repository.PlaybackNavigationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
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

    @Test
    fun `repeated next episode requests share the pending lookup`() = runTest {
        val repository = FakePlaybackNavigationRepository()
        val gate = CompletableDeferred<Unit>()
        repository.nextEpisodeGate = gate
        val viewModel = PlayerViewModel(repository)
        val event = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.first() }

        repeat(3) { viewModel.playNextEpisode(repository.currentEpisode.seriesId, repository.currentEpisode.id) }
        assertEquals(1, repository.nextEpisodeCalls)
        gate.complete(Unit)
        assertEquals(PlayerEvent.PlayNext(repository.nextEpisode), event.await())

        repository.nextEpisodeGate = null
        val secondEvent = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.first() }
        viewModel.playNextEpisode(repository.currentEpisode.seriesId, repository.currentEpisode.id)
        assertEquals(PlayerEvent.PlayNext(repository.nextEpisode), secondEvent.await())
        assertEquals(2, repository.nextEpisodeCalls)
    }

    @Test fun `movie completion does not query next episode`() = runTest {
        val repository = FakePlaybackNavigationRepository().apply { movie = true }
        val vm = PlayerViewModel(repository)
        val event = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { vm.events.first() }
        vm.playNextEpisode(2, 1)
        assertEquals(PlayerEvent.PlaybackFinished, event.await())
        assertEquals(0, repository.nextEpisodeCalls)
    }

    @Test fun `query failure is distinct from series completion`() = runTest {
        val repository = FakePlaybackNavigationRepository().apply { fail = true }
        val vm = PlayerViewModel(repository)
        val event = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { vm.events.first() }
        vm.playNextEpisode(2, 1)
        assertEquals(PlayerEvent.NavigationFailed, event.await())
    }

    @Test fun `season playback stops before next season`() = runTest {
        val repository = FakePlaybackNavigationRepository()
        val vm = PlayerViewModel(repository)
        val event = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { vm.events.first() }
        vm.playNextEpisode(2, 1, allowCrossSeason = false)
        assertEquals(PlayerEvent.LastEpisodeReached, event.await())
    }

    @Test fun `gap event does not change current title before confirmation`() = runTest {
        val repository = FakePlaybackNavigationRepository().apply { gap = true; nextTitle = "Show S02E01" }
        val vm = PlayerViewModel(repository)
        vm.setCurrentTitle("Show S01E01")
        val event = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { vm.events.first() }
        vm.playNextEpisode(2, 1)
        assertEquals(PlayerEvent.PlayNext(repository.nextEpisode, true, "Show S02E01"), event.await())
        assertEquals("Show S01E01", vm.uiState.value.title)
    }

    private class FakePlaybackNavigationRepository : PlaybackNavigationRepository {
        var movie = false
        var fail = false
        var gap = false
        var nextTitle = ""
        override suspend fun isMovie(seriesId: Long) = movie
        override suspend fun episodeTitle(episodeId: Long) = nextTitle
        override suspend fun hasEpisodeGap(currentId: Long, next: EpisodeEntity) = gap
        val currentEpisode = EpisodeEntity(id = 1L, seriesId = 2L, seasonId = 3L, episodeNumber = 1)
        val nextEpisode = EpisodeEntity(id = 4L, seriesId = 2L, seasonId = 5L, episodeNumber = 1)
        var nextEpisodeResult: EpisodeEntity? = nextEpisode
        var resolveSeasonCalls = 0
        var nextEpisodeCalls = 0
        var nextEpisodeGate: CompletableDeferred<Unit>? = null

        override suspend fun resolveSeasonId(episodeId: Long): Long {
            resolveSeasonCalls++
            return currentEpisode.seasonId
        }

        override suspend fun findNextEpisode(seriesId: Long, episodeId: Long): EpisodeEntity? {
            if (fail) error("Database unavailable")
            nextEpisodeCalls++
            nextEpisodeGate?.await()
            return nextEpisodeResult
        }
    }
}
