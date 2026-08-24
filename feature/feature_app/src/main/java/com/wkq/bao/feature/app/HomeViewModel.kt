package com.wkq.bao.feature.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wkq.bao.core.database.entity.ContinueWatchingItem
import com.wkq.bao.core.database.entity.EpisodeEntity
import com.wkq.bao.core.database.entity.MediaSeriesEntity
import com.wkq.bao.core.database.entity.MediaSeriesType
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
    val continueWatching: List<ContinueWatchingItem> = emptyList(),
    val cartoons: List<MediaSeriesEntity> = emptyList(),
    val featured: MediaSeriesEntity? = null
)

sealed interface HomeEvent {
    data class Play(val seriesId: Long, val episode: EpisodeEntity) : HomeEvent
    data class OpenDetail(val seriesId: Long) : HomeEvent
}

class HomeViewModel(
    private val repository: MediaBrowseRepository
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        repository.continueWatching,
        repository.allSeries
    ) { continueWatching, allSeries ->
        HomeUiState(
            continueWatching = continueWatching,
            cartoons = allSeries.filter { it.type == MediaSeriesType.CARTOON },
            featured = allSeries.firstOrNull()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), HomeUiState())

    private val _events = MutableSharedFlow<HomeEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<HomeEvent> = _events.asSharedFlow()

    fun playFeatured() {
        val seriesId = uiState.value.featured?.id ?: return
        viewModelScope.launch {
            runCatching { repository.getFirstEpisode(seriesId) }
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
