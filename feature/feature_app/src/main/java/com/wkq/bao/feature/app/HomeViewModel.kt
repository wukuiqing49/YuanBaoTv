package com.wkq.bao.feature.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wkq.bao.core.database.entity.ContinueWatchingItem
import com.wkq.bao.core.database.entity.EpisodeEntity
import com.wkq.bao.core.database.entity.MediaSeriesEntity
import com.wkq.bao.core.media.repository.MediaBrowseRepository
import com.wkq.bao.core.media.repository.RoomMediaBrowseRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val loading: Boolean = false,
    val continueWatching: List<ContinueWatchingItem> = emptyList(),
    val cartoons: List<MediaSeriesEntity> = emptyList(),
    val movies: List<MediaSeriesEntity> = emptyList(),
    val series: List<MediaSeriesEntity> = emptyList(),
    val animation: List<MediaSeriesEntity> = emptyList(),
    val featured: MediaSeriesEntity? = null,
    val featuredProgress: ContinueWatchingItem? = null
)

sealed interface HomeEvent {
    data class Play(val seriesId: Long, val episode: EpisodeEntity) : HomeEvent
    data class OpenDetail(val seriesId: Long) : HomeEvent
}

class HomeViewModel(
    private val repository: MediaBrowseRepository
) : ViewModel() {
    val offlineSeriesIds = repository.offlineSeriesIds
    private val previewSeriesId = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)

    fun previewSeries(seriesId: Long) {
        previewSeriesId.value = seriesId
    }

    val uiState: StateFlow<HomeUiState> = combine(
        repository.continueWatching,
        repository.allSeries,
        previewSeriesId
    ) { continueWatching, allSeries, previewId ->
        // 重建索引期间旧副本仍可能可播，同一展示集合只保留最近一条历史。
        val visibleContinueWatching = continueWatching.sortedWith(
            compareByDescending<ContinueWatchingItem> { it.history.lastPlayedAt }
                .thenByDescending { it.history.seriesId }
        ).distinctBy { com.wkq.bao.feature.app.utils.MediaLabels.homeCollectionKey(it.seriesTitle) }.take(8)
        val featuredProgress = visibleContinueWatching.firstOrNull()
        val featured = allSeries.firstOrNull { it.id == previewId }
            ?: allSeries.firstOrNull { it.id == featuredProgress?.history?.seriesId }
            ?: allSeries.maxByOrNull(MediaSeriesEntity::updatedAt)
        val usedCollections = visibleContinueWatching.mapTo(hashSetOf()) {
            com.wkq.bao.feature.app.utils.MediaLabels.homeCollectionKey(it.seriesTitle)
        }
        fun pick(items: List<MediaSeriesEntity>): List<MediaSeriesEntity> {
            val selected = items.filterNot {
                com.wkq.bao.feature.app.utils.MediaLabels.homeCollectionKey(it.title) in usedCollections
            }.distinctBy { com.wkq.bao.feature.app.utils.MediaLabels.homeCollectionKey(it.title) }.take(8)
            selected.forEach { usedCollections += com.wkq.bao.feature.app.utils.MediaLabels.homeCollectionKey(it.title) }
            return selected
        }
        val recent = pick(allSeries.sortedByDescending(MediaSeriesEntity::createdAt))
        val movies = pick(allSeries.filter { it.type == com.wkq.bao.core.database.entity.MediaSeriesType.MOVIE })
        val series = pick(allSeries.filter { it.type == com.wkq.bao.core.database.entity.MediaSeriesType.TV })
        val animation = pick(allSeries.filter { it.type == com.wkq.bao.core.database.entity.MediaSeriesType.CARTOON })
        HomeUiState(
            loading = false,
            continueWatching = visibleContinueWatching,
            cartoons = recent,
            movies = movies,
            series = series,
            animation = animation,
            featured = featured,
            featuredProgress = visibleContinueWatching.firstOrNull { it.history.seriesId == featured?.id }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), HomeUiState())

    private val _events = MutableSharedFlow<HomeEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<HomeEvent> = _events.asSharedFlow()

    fun playFeatured() {
        val state = uiState.value
        val seriesId = state.featured?.id ?: return
        val history = state.featuredProgress?.history?.takeIf { it.seriesId == seriesId && !it.completed }
        viewModelScope.launch {
            runCatching {
                history?.let { repository.getPlayableEpisode(seriesId, it.episodeId) }
                    ?.takeIf { it.seriesId == seriesId && it.id == history.episodeId }
                    ?: repository.getPlaybackEpisode(seriesId)
            }
                .onSuccess { episode ->
                    _events.emit(
                        episode?.let { HomeEvent.Play(seriesId, it) }
                            ?: HomeEvent.OpenDetail(seriesId)
                    )
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    _events.emit(HomeEvent.OpenDetail(seriesId))
                }
        }
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val repository = RoomMediaBrowseRepository.create(context)

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HomeViewModel::class.java))
            return HomeViewModel(repository) as T
        }
    }
}
