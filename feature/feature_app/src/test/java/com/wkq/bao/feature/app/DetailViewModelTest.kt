package com.wkq.bao.feature.app

import com.wkq.bao.core.database.entity.EpisodeEntity
import com.wkq.bao.core.database.entity.EpisodeWithSource
import com.wkq.bao.core.database.entity.MediaSeriesEntity
import com.wkq.bao.core.database.entity.MediaSeriesType
import com.wkq.bao.core.database.entity.SeasonEntity
import com.wkq.bao.core.media.repository.EnqueueDownloadsResult
import com.wkq.bao.core.media.repository.MediaDetailRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModelTest {
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
    fun `series initialization selects first season and observes episodes`() = runTest {
        val repository = FakeDetailRepository()
        val viewModel = DetailViewModel(repository)

        viewModel.initialize(repository.series.id)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.loading)
        assertEquals(repository.series.id, state.series?.id)
        assertEquals(repository.season.id, state.selectedSeasonId)
        assertEquals(repository.episode.id, state.episodes.single().episode.id)
    }

    @Test
    fun `play and download results are emitted as one shot events`() = runTest {
        val repository = FakeDetailRepository()
        val viewModel = DetailViewModel(repository)
        viewModel.initialize(repository.series.id)
        advanceUntilIdle()

        val playEvent = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.first() }
        viewModel.playFirstEpisode()
        assertEquals(DetailEvent.Play(repository.episode), playEvent.await())

        val downloadEvent = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.first() }
        viewModel.enqueueCurrentSelection()
        assertEquals(
            DetailEvent.DownloadsQueued(EnqueueDownloadsResult.SeasonQueued(1)),
            downloadEvent.await()
        )
        assertFalse(viewModel.uiState.value.actionInProgress)
    }

    private class FakeDetailRepository : MediaDetailRepository {
        val series = MediaSeriesEntity(id = 1L, title = "Series", type = MediaSeriesType.TV)
        val season = SeasonEntity(id = 2L, seriesId = series.id, seasonNumber = 1)
        val episode = EpisodeEntity(id = 3L, seriesId = series.id, seasonId = season.id, episodeNumber = 1)
        private val seasons = MutableStateFlow(listOf(season))
        private val episodes = MutableStateFlow(listOf(EpisodeWithSource(episode, null, null, "smb://source/episode")))
        private var favorite = false

        override suspend fun getSeries(seriesId: Long): MediaSeriesEntity? = series.takeIf { it.id == seriesId }
        override fun observeSeasons(seriesId: Long): Flow<List<SeasonEntity>> = seasons
        override fun observeEpisodes(seriesId: Long, seasonId: Long): Flow<List<EpisodeWithSource>> = episodes
        override suspend fun getFirstPlayableEpisode(seriesId: Long, seasonId: Long, isMovie: Boolean): EpisodeEntity = episode
        override suspend fun isFavorite(seriesId: Long): Boolean = favorite
        override suspend fun toggleFavorite(seriesId: Long): Boolean {
            favorite = !favorite
            return favorite
        }

        override suspend fun enqueueDownloads(
            seriesId: Long,
            seasonId: Long,
            isMovie: Boolean
        ): EnqueueDownloadsResult = EnqueueDownloadsResult.SeasonQueued(1)
    }
}
