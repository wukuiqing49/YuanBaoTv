package com.wkq.bao.feature.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wkq.bao.core.database.entity.EpisodeEntity
import com.wkq.bao.core.media.repository.PlaybackNavigationRepository
import com.wkq.bao.core.media.repository.RoomPlaybackNavigationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlayerUiState(
    val initialSeasonId: Long? = null
)

sealed interface PlayerEvent {
    data class PlayNext(val episode: EpisodeEntity) : PlayerEvent
    data object LastEpisodeReached : PlayerEvent
}

class PlayerViewModel(
    private val repository: PlaybackNavigationRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<PlayerEvent> = _events.asSharedFlow()

    fun initialize(initialSeasonId: Long, episodeId: Long) {
        if (_uiState.value.initialSeasonId != null) return
        viewModelScope.launch {
            val resolvedSeasonId = initialSeasonId.takeIf { it > 0L }
                ?: repository.resolveSeasonId(episodeId)
            _uiState.value = PlayerUiState(initialSeasonId = resolvedSeasonId)
        }
    }

    fun playNextEpisode(seriesId: Long, episodeId: Long) {
        if (seriesId <= 0L || episodeId <= 0L) return
        viewModelScope.launch {
            runCatching { repository.findNextEpisode(seriesId, episodeId) }
                .onSuccess { episode ->
                    _events.emit(episode?.let(PlayerEvent::PlayNext) ?: PlayerEvent.LastEpisodeReached)
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    _events.emit(PlayerEvent.LastEpisodeReached)
                }
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
