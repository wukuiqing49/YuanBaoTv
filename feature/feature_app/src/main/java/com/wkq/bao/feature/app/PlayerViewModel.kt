package com.wkq.bao.feature.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wkq.bao.core.database.entity.EpisodeEntity
import com.wkq.bao.core.media.repository.PlaybackNavigationRepository
import com.wkq.bao.core.media.repository.RoomPlaybackNavigationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlayerUiState(
    val initialSeasonId: Long? = null,
    val isMovie: Boolean = false,
    val title: String = ""
)

sealed interface PlayerEvent {
    data class PlayNext(val episode: EpisodeEntity, val hasGap: Boolean = false, val title: String = "") : PlayerEvent
    data object LastEpisodeReached : PlayerEvent
    data object PlaybackFinished : PlayerEvent
    data object NavigationFailed : PlayerEvent
}

class PlayerViewModel(
    private val repository: PlaybackNavigationRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<PlayerEvent> = _events.asSharedFlow()
    private var nextEpisodeJob: Job? = null

    fun initialize(initialSeasonId: Long, episodeId: Long, seriesId: Long = 0) {
        if (_uiState.value.initialSeasonId != null) return
        viewModelScope.launch {
            try {
                val resolvedSeasonId = initialSeasonId.takeIf { it > 0L } ?: repository.resolveSeasonId(episodeId)
                _uiState.value = PlayerUiState(initialSeasonId = resolvedSeasonId,
                    isMovie = repository.isMovie(seriesId), title = repository.episodeTitle(episodeId))
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { _events.emit(PlayerEvent.NavigationFailed) }
        }
    }

    fun setCurrentTitle(title: String) {
        _uiState.value = _uiState.value.copy(title = title)
    }

    fun playNextEpisode(seriesId: Long, episodeId: Long, allowCrossSeason: Boolean = true) {
        if (seriesId <= 0L || episodeId <= 0L || nextEpisodeJob?.isActive == true) return
        nextEpisodeJob = viewModelScope.launch {
            try {
                val event = if (repository.isMovie(seriesId)) PlayerEvent.PlaybackFinished else {
                    val next = repository.findNextEpisode(seriesId, episodeId)
                    if (next == null || (!allowCrossSeason && next.seasonId != repository.resolveSeasonId(episodeId))) {
                        PlayerEvent.LastEpisodeReached
                    } else PlayerEvent.PlayNext(next, repository.hasEpisodeGap(episodeId, next), repository.episodeTitle(next.id))
                }
                _events.emit(event)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { _events.emit(PlayerEvent.NavigationFailed) }
        }
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val repository = RoomPlaybackNavigationRepository.create(context)

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(PlayerViewModel::class.java))
            return PlayerViewModel(repository) as T
        }
    }
}
