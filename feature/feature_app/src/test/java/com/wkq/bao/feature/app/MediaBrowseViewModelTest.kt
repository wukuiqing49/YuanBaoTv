package com.wkq.bao.feature.app

import com.wkq.bao.core.database.entity.ContinueWatchingItem
import com.wkq.bao.core.database.entity.EpisodeEntity
import com.wkq.bao.core.database.entity.MediaSeriesEntity
import com.wkq.bao.core.database.entity.MediaSeriesType
import com.wkq.bao.core.database.entity.WatchHistoryEntity
import com.wkq.bao.core.media.repository.MediaBrowseRepository
import androidx.lifecycle.SavedStateHandle
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
    fun `home state exposes all featured media and prioritizes continue watching`() = runTest {
        val repository = FakeMediaBrowseRepository()
        val viewModel = HomeViewModel(repository)
        val stateCollection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        assertEquals(listOf(repository.movie.id, repository.cartoon.id), viewModel.uiState.value.cartoons.map { it.id })
        assertEquals(repository.movie.id, viewModel.uiState.value.featured?.id)
        assertEquals(repository.continueItem.history.episodeId, viewModel.uiState.value.continueWatching.single().history.episodeId)

        val event = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.first() }
        viewModel.playFeatured()
        assertEquals(HomeEvent.Play(repository.movie.id, repository.firstEpisode), event.await())
        stateCollection.cancel()
    }

    @Test
    fun `featured playback resumes the actual historical episode`() = runTest {
        val repository = FakeMediaBrowseRepository()
        val fifth = repository.firstEpisode.copy(id = 50, episodeNumber = 5)
        repository.playableEpisode = fifth
        repository.continueWatching.value = listOf(repository.continueItem.copy(
            history = repository.continueItem.history.copy(episodeId = fifth.id)))
        val viewModel = HomeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()
        val event = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.first() }
        viewModel.playFeatured()
        assertEquals(HomeEvent.Play(repository.movie.id, fifth), event.await())
    }

    @Test
    fun `missing or foreign historical episode falls back to the first playable episode`() = runTest {
        for (invalid in listOf(null, EpisodeEntity(id = 50, seriesId = 99, seasonId = 4, episodeNumber = 5))) {
            val repository = FakeMediaBrowseRepository()
            repository.playableEpisode = invalid
            repository.continueWatching.value = listOf(repository.continueItem.copy(
                history = repository.continueItem.history.copy(episodeId = 50)))
            val viewModel = HomeViewModel(repository)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
            advanceUntilIdle()
            val event = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.first() }
            viewModel.playFeatured()
            assertEquals(HomeEvent.Play(repository.movie.id, repository.firstEpisode), event.await())
        }
    }

    @Test
    fun `library state follows the selected media type`() = runTest {
        val repository = FakeMediaBrowseRepository()
        val viewModel = MediaLibraryViewModel(repository, dispatcher)
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

    @Test
    fun `search category and favorites intersect and react to favorite changes`() = runTest {
        val repository = FakeMediaBrowseRepository()
        repository.series.value = listOf(repository.movie.copy(originalTitle = "Original Movie"), repository.cartoon)
        val viewModel = MediaLibraryViewModel(repository, dispatcher)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.search("  ORIGINAL  ")
        advanceUntilIdle()
        assertEquals(listOf(repository.movie.id), viewModel.uiState.value.series.map { it.id })
        viewModel.showFavorites(true)
        advanceUntilIdle()
        assertEquals(emptyList<MediaSeriesEntity>(), viewModel.uiState.value.series)
        repository.favoriteSeriesIds.value = setOf(repository.movie.id)
        advanceUntilIdle()
        assertEquals(listOf(repository.movie.id), viewModel.uiState.value.series.map { it.id })
        viewModel.selectType(MediaSeriesType.CARTOON)
        advanceUntilIdle()
        assertEquals(emptyList<MediaSeriesEntity>(), viewModel.uiState.value.series)
        viewModel.clearFilters()
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.series.size)
        assertEquals(false, viewModel.uiState.value.hasFilters)
    }

    @Test
    fun `sort uses added date title or year with unknown years last`() = runTest {
        val repository = FakeMediaBrowseRepository()
        repository.series.value = listOf(
            repository.movie.copy(id = 1, title = "beta", year = "2024", createdAt = 10, updatedAt = 100),
            repository.movie.copy(id = 2, title = "Alpha", year = "2000", createdAt = 30, updatedAt = 1),
            repository.movie.copy(id = 3, title = "Gamma", year = "unknown", createdAt = 20)
        )
        val viewModel = MediaLibraryViewModel(repository, dispatcher)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(listOf(2L, 3L, 1L), viewModel.uiState.value.series.map { it.id })
        viewModel.selectSort(LibrarySort.TITLE)
        advanceUntilIdle()
        assertEquals(listOf(2L, 1L, 3L), viewModel.uiState.value.series.map { it.id })
        viewModel.selectSort(LibrarySort.YEAR)
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L, 3L), viewModel.uiState.value.series.map { it.id })
        viewModel.search("missing")
        viewModel.clearFilters()
        advanceUntilIdle()
        assertEquals(LibrarySort.YEAR, viewModel.uiState.value.sort)
    }

    @Test
    fun `library filters survive reconstruction and invalid sort falls back`() = runTest {
        val savedState = SavedStateHandle()
        val repository = FakeMediaBrowseRepository()
        val original = MediaLibraryViewModel(repository, dispatcher, savedState)
        original.search("Movie")
        original.selectType(MediaSeriesType.MOVIE)
        original.showFavorites(true)
        original.selectSort(LibrarySort.YEAR)
        val restored = MediaLibraryViewModel(repository, dispatcher, savedState)
        assertEquals("Movie", restored.uiState.value.query)
        assertEquals(MediaSeriesType.MOVIE, restored.uiState.value.selectedType)
        assertEquals(true, restored.uiState.value.favoritesOnly)
        assertEquals(LibrarySort.YEAR, restored.uiState.value.sort)
        restored.clearFilters()
        val cleared = MediaLibraryViewModel(repository, dispatcher, savedState)
        assertEquals(false, cleared.uiState.value.hasFilters)
        assertEquals(LibrarySort.YEAR, cleared.uiState.value.sort)
        savedState["library_sort"] = "removed_sort"
        assertEquals(LibrarySort.RECENT, MediaLibraryViewModel(repository, dispatcher, savedState).uiState.value.sort)
    }

    @Test
    fun `large library expands in bounded windows and filtering resets the window`() = runTest {
        val repository = FakeMediaBrowseRepository()
        repository.series.value = (1..5000).map { index -> repository.movie.copy(id = index.toLong(),
            title = "Movie $index", createdAt = index.toLong()) }
        val savedState = SavedStateHandle()
        val viewModel = MediaLibraryViewModel(repository, dispatcher, savedState)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(5000, viewModel.uiState.value.totalCount)
        assertEquals(60, viewModel.uiState.value.series.size)
        assertEquals(5000L, viewModel.uiState.value.series.first().id)
        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(120, viewModel.uiState.value.series.size)
        assertEquals(120, savedState.get<Int>("library_visible_limit"))
        viewModel.search("Movie 5000")
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.totalCount)
        assertEquals(false, viewModel.uiState.value.hasMore)
        viewModel.clearFilters()
        advanceUntilIdle()
        assertEquals(60, viewModel.uiState.value.series.size)
    }

    @Test
    fun `home categories react to type changes and recently added ignores metadata edits`() = runTest {
        val repository = FakeMediaBrowseRepository()
        val olderEdited = repository.movie.copy(createdAt = 1, updatedAt = 999)
        val newer = repository.cartoon.copy(createdAt = 20, updatedAt = 20)
        val tv = repository.movie.copy(id = 30, type = MediaSeriesType.TV, createdAt = 10)
        val local = repository.movie.copy(id = 40, type = MediaSeriesType.LOCAL, createdAt = 5)
        repository.series.value = listOf(olderEdited, newer, tv, local)
        val viewModel = HomeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(listOf(2L, 30L, 40L, 1L), viewModel.uiState.value.cartoons.map { it.id })
        assertEquals(listOf(1L), viewModel.uiState.value.movies.map { it.id })
        assertEquals(listOf(30L), viewModel.uiState.value.series.map { it.id })
        assertEquals(listOf(2L), viewModel.uiState.value.animation.map { it.id })
        repository.series.value = listOf(newer.copy(type = MediaSeriesType.MOVIE))
        advanceUntilIdle()
        assertEquals(listOf(2L), viewModel.uiState.value.movies.map { it.id })
        assertEquals(true, viewModel.uiState.value.animation.isEmpty())
        assertEquals(true, viewModel.uiState.value.series.isEmpty())
    }

    private class FakeMediaBrowseRepository : MediaBrowseRepository {
        val movie = MediaSeriesEntity(id = 1L, title = "Movie", type = MediaSeriesType.MOVIE, createdAt = 20, updatedAt = 20)
        val cartoon = MediaSeriesEntity(id = 2L, title = "Cartoon", type = MediaSeriesType.CARTOON, createdAt = 10, updatedAt = 10)
        val firstEpisode = EpisodeEntity(id = 3L, seriesId = movie.id, seasonId = 4L, title = "Movie", episodeNumber = 1)
        val continueItem = ContinueWatchingItem(
            history = WatchHistoryEntity(seriesId = movie.id, seasonId = 4L, episodeId = firstEpisode.id, positionMs = 180_000L),
            seriesTitle = movie.title,
            episodeTitle = firstEpisode.title
        )
        val series = MutableStateFlow(listOf(movie, cartoon))
        override val favoriteSeriesIds = MutableStateFlow<Set<Long>>(emptySet())

        var playableEpisode: EpisodeEntity? = firstEpisode
        override val continueWatching = MutableStateFlow(listOf(continueItem))
        override val allSeries: Flow<List<MediaSeriesEntity>> = series

        override fun observeSeriesByType(type: String?): Flow<List<MediaSeriesEntity>> =
            series.map { allSeries -> type?.let { filter -> allSeries.filter { it.type == filter } } ?: allSeries }

        override suspend fun getFirstEpisode(seriesId: Long): EpisodeEntity? = firstEpisode.takeIf { seriesId == movie.id }
        override suspend fun getPlayableEpisode(seriesId: Long, episodeId: Long): EpisodeEntity? = playableEpisode
    }
}
