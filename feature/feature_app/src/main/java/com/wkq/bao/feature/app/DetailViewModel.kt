package com.wkq.bao.feature.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wkq.bao.core.database.entity.EpisodeEntity
import com.wkq.bao.core.database.entity.EpisodeWithSource
import com.wkq.bao.core.database.entity.MediaSeriesEntity
import com.wkq.bao.core.database.entity.MediaSeriesType
import com.wkq.bao.core.database.entity.SeasonEntity
import com.wkq.bao.core.media.repository.EnqueueDownloadsResult
import com.wkq.bao.core.media.repository.DownloadTarget
import com.wkq.bao.core.media.repository.MediaDetailRepository
import com.wkq.bao.core.media.repository.RoomMediaDetailRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DetailUiState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val loadFailed: Boolean = false,
    val series: MediaSeriesEntity? = null,
    val isMovie: Boolean = false,
    val isFavorite: Boolean = false,
    val seasons: List<SeasonEntity> = emptyList(),
    val selectedSeasonId: Long = 0L,
    val episodes: List<EpisodeWithSource> = emptyList(),
    val actionInProgress: Boolean = false
)

sealed interface DetailEvent {
    data class Play(val episode: EpisodeEntity) : DetailEvent
    data object NoPlayableEpisode : DetailEvent
    data class DownloadsQueued(val result: EnqueueDownloadsResult) : DetailEvent
    data object ActionFailed : DetailEvent
}

class DetailViewModel(
    private val repository: MediaDetailRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<DetailEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<DetailEvent> = _events.asSharedFlow()

    private var seriesId = 0L
    private var initializeJob: Job? = null
    private var seasonsJob: Job? = null
    private var episodesJob: Job? = null

    fun initialize(targetSeriesId: Long) {
        if (seriesId != 0L || targetSeriesId <= 0L) return
        seriesId = targetSeriesId
        loadSeries()
    }

    fun retry() {
        if (seriesId <= 0L || _uiState.value.loading) return
        seasonsJob?.cancel()
        episodesJob?.cancel()
        loadSeries()
    }

    private fun loadSeries() {
        initializeJob?.cancel()
        _uiState.value = DetailUiState(loading = true)
        initializeJob = viewModelScope.launch {
            runCatching {
                val series = repository.getSeries(seriesId)
                if (series == null) {
                    _uiState.update { it.copy(loading = false, missing = true) }
                    return@runCatching
                }
                val isMovie = MediaSeriesType.isMovie(series.type)
                _uiState.update {
                    it.copy(
                        loading = false,
                        series = series,
                        isMovie = isMovie,
                        isFavorite = repository.isFavorite(seriesId)
                    )
                }
                if (isMovie) {
                    _uiState.update { it.copy(seasons = emptyList(), selectedSeasonId = 0L, episodes = emptyList()) }
                } else {
                    observeSeasons()
                }
            }.onFailure {
                if (it is CancellationException) throw it
                _uiState.update { it.copy(loading = false, loadFailed = true) }
            }
        }
    }

    fun selectSeason(seasonId: Long) {
        if (seriesId <= 0L || seasonId <= 0L || _uiState.value.isMovie) return
        _uiState.update { it.copy(selectedSeasonId = seasonId) }
        episodesJob?.cancel()
        episodesJob = viewModelScope.launch {
            repository.observeEpisodes(seriesId, seasonId).collectLatest { episodes ->
                _uiState.update { current ->
                    if (current.selectedSeasonId == seasonId) current.copy(episodes = episodes) else current
                }
            }
        }
    }

    fun playFirstEpisode() {
        val state = _uiState.value
        if (state.series == null) return
        viewModelScope.launch {
            runCatching {
                repository.getFirstPlayableEpisode(seriesId, state.selectedSeasonId, state.isMovie)
            }.onSuccess { episode ->
                _events.emit(episode?.let(DetailEvent::Play) ?: DetailEvent.NoPlayableEpisode)
            }.onFailure {
                if (it is CancellationException) throw it
                _events.emit(DetailEvent.ActionFailed)
            }
        }
    }

    fun toggleFavorite() {
        if (seriesId <= 0L) return
        viewModelScope.launch {
            runCatching { repository.toggleFavorite(seriesId) }
                .onSuccess { favorite -> _uiState.update { it.copy(isFavorite = favorite) } }
                .onFailure {
                    if (it is CancellationException) throw it
                    _events.emit(DetailEvent.ActionFailed)
                }
        }
    }

    fun enqueueCurrentSelection(downloadTarget: DownloadTarget) {
        val state = _uiState.value
        if (state.series == null || state.actionInProgress || (!state.isMovie && state.selectedSeasonId <= 0L)) return
        _uiState.update { it.copy(actionInProgress = true) }
        viewModelScope.launch {
            runCatching {
                repository.enqueueDownloads(seriesId, state.selectedSeasonId, state.isMovie, downloadTarget)
            }
                .onSuccess { _events.emit(DetailEvent.DownloadsQueued(it)) }
                .onFailure {
                    if (it is CancellationException) throw it
                    _events.emit(DetailEvent.ActionFailed)
                }
            _uiState.update { it.copy(actionInProgress = false) }
        }
    }

    fun enqueueEpisode(episodeId: Long, downloadTarget: DownloadTarget) {
        val state = _uiState.value
        if (state.series == null || state.actionInProgress || state.episodes.none { it.episode.id == episodeId }) return
        _uiState.update { it.copy(actionInProgress = true) }
        viewModelScope.launch {
            runCatching { repository.enqueueEpisode(seriesId, episodeId, downloadTarget) }
                .onSuccess { _events.emit(DetailEvent.DownloadsQueued(it)) }
                .onFailure {
                    if (it is CancellationException) throw it
                    _events.emit(DetailEvent.ActionFailed)
                }
            _uiState.update { it.copy(actionInProgress = false) }
        }
    }

    private fun observeSeasons() {
        seasonsJob?.cancel()
        seasonsJob = viewModelScope.launch {
            repository.observeSeasons(seriesId).collectLatest { seasons ->
                val currentSelection = _uiState.value.selectedSeasonId
                val selectedSeasonId = currentSelection.takeIf { selected -> seasons.any { it.id == selected } }
                    ?: seasons.firstOrNull()?.id
                    ?: 0L
                _uiState.update { it.copy(seasons = seasons, selectedSeasonId = selectedSeasonId) }
                if (selectedSeasonId == 0L) {
                    episodesJob?.cancel()
                    _uiState.update { it.copy(episodes = emptyList()) }
                } else if (selectedSeasonId != currentSelection) {
                    selectSeason(selectedSeasonId)
                }
            }
        }
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val repository = RoomMediaDetailRepository.create(context)

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DetailViewModel::class.java))
            return DetailViewModel(repository) as T
        }
    }
}
