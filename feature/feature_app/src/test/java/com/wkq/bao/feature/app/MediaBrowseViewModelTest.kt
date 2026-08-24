package com.wkq.bao.feature.app

import com.wkq.bao.core.database.entity.ContinueWatchingItem
import com.wkq.bao.core.database.entity.EpisodeEntity
import com.wkq.bao.core.database.entity.MediaSeriesEntity
import com.wkq.bao.core.database.entity.MediaSeriesType
import com.wkq.bao.core.database.entity.WatchHistoryEntity
import com.wkq.bao.core.media.repository.MediaBrowseRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
class MediaBrowseViewModelTest {
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
    fun `home state separates cartoons and plays the featured first episode`() = runTest {
        val repository = FakeMediaBrowseRepository()
        val viewModel = HomeViewModel(repository)
        val stateCollection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        assertEquals(listOf(repository.cartoon.id), viewModel.uiState.value.cartoons.map { it.id })
        assertEquals(repository.continueItem.history.episodeId, viewModel.uiState.value.continueWatching.single().history.episodeId)

        val event = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.first() }
        viewModel.playFeatured()
        assertEquals(HomeEvent.Play(repository.movie.id, repository.firstEpisode), event.await())
        stateCollection.cancel()
    }

    @Test
    fun `library state follows the selected media type`() = runTest {
        val repository = FakeMediaBrowseRepository()
        val viewModel = MediaLibraryViewModel(repository)
        val stateCollection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.series.size)
        viewModel.selectType(MediaSeriesType.CARTOON)
        advanceUntilIdle()

        assertEquals(MediaSeriesType.CARTOON, viewModel.uiState.value.selectedType)
        assertEquals(listOf(repository.cartoon.id), viewModel.uiState.value.series.map { it.id })
        stateCollection.cancel()
    }

    private class FakeMediaBrowseRepository : MediaBrowseRepository {
        val movie = MediaSeriesEntity(id = 1L, title = "Movie", type = MediaSeriesType.MOVIE)
        val cartoon = MediaSeriesEntity(id = 2L, title = "Cartoon", type = MediaSeriesType.CARTOON)
        val firstEpisode = EpisodeEntity(id = 3L, seriesId = movie.id, seasonId = 4L, title = "Movie", episodeNumber = 1)
        val continueItem = ContinueWatchingItem(
            history = WatchHistoryEntity(seriesId = movie.id, seasonId = 4L, episodeId = firstEpisode.id, positionMs = 180_000L),
            seriesTitle = movie.title,
            episodeTitle = firstEpisode.title
        )
        private val series = MutableStateFlow(listOf(movie, cartoon))

        override val continueWatching: Flow<List<ContinueWatchingItem>> = MutableStateFlow(listOf(continueItem))
        override val allSeries: Flow<List<MediaSeriesEntity>> = series

        override fun observeSeriesByType(type: String?): Flow<List<MediaSeriesEntity>> =
            series.map { allSeries -> type?.let { filter -> allSeries.filter { it.type == filter } } ?: allSeries }

        override suspend fun getFirstEpisode(seriesId: Long): EpisodeEntity? = firstEpisode.takeIf { seriesId == movie.id }
    }
}
