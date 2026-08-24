package com.wkq.bao.feature.app

import com.wkq.bao.core.database.entity.DownloadTaskEntity
import com.wkq.bao.core.database.entity.DownloadTaskStatus
import com.wkq.bao.core.database.entity.MediaSeriesEntity
import com.wkq.bao.core.media.repository.DownloadsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelTest {
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
    fun `state exposes only actionable tasks and downloaded series`() = runTest {
        val waiting = task(1L, DownloadTaskStatus.WAITING)
        val completed = task(2L, DownloadTaskStatus.SUCCESS)
        val repository = FakeDownloadsRepository(
            initialTasks = listOf(waiting, completed),
            initialSeries = listOf(MediaSeriesEntity(id = 9L, title = "Movie"))
        )
        val viewModel = DownloadsViewModel(repository)
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        advanceUntilIdle()

        assertEquals(listOf(waiting), viewModel.uiState.value.tasks)
        assertEquals(9L, viewModel.uiState.value.downloadedSeries.single().id)
        assertTrue(!viewModel.uiState.value.keepScreenOn)
        collection.cancel()
    }

    @Test
    fun `actions are delegated by stable task id`() = runTest {
        val repository = FakeDownloadsRepository(emptyList(), emptyList())
        val viewModel = DownloadsViewModel(repository)

        viewModel.togglePauseResume(7L)
        viewModel.cancel(8L)
        advanceUntilIdle()

        assertEquals(listOf(7L), repository.toggledIds)
        assertEquals(listOf(8L), repository.cancelledIds)
    }

    private fun task(id: Long, status: String) = DownloadTaskEntity(
        id = id,
        seriesId = 1L,
        seasonId = 1L,
        episodeId = id,
        sourceUri = "smb://source/$id",
        status = status
    )

    private class FakeDownloadsRepository(
        initialTasks: List<DownloadTaskEntity>,
        initialSeries: List<MediaSeriesEntity>
    ) : DownloadsRepository {
        override val tasks: Flow<List<DownloadTaskEntity>> = MutableStateFlow(initialTasks)
        override val downloadedSeries: Flow<List<MediaSeriesEntity>> = MutableStateFlow(initialSeries)
        val toggledIds = mutableListOf<Long>()
        val cancelledIds = mutableListOf<Long>()

        override suspend fun togglePauseResume(taskId: Long) {
            toggledIds += taskId
        }

        override suspend fun cancel(taskId: Long) {
            cancelledIds += taskId
        }
    }
}
