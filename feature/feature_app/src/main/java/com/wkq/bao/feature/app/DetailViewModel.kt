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
import com.wkq.bao.core.media.repository.LocalMetadataUpdate
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
    val episodesLoading: Boolean = false,
    val cachedOnly: Boolean = false,
    val cachedCopies: List<com.wkq.bao.core.database.entity.CachedMediaRow> = emptyList(),
    val playbackTarget: com.wkq.bao.core.media.repository.PlaybackTarget? = null,
    val actionInProgress: Boolean = false
)

sealed interface DetailEvent {
    data class Play(val episode: EpisodeEntity) : DetailEvent
    data object NoPlayableEpisode : DetailEvent
    data class DownloadsQueued(val result: EnqueueDownloadsResult) : DetailEvent
    data object ActionFailed : DetailEvent
    data object MetadataSaved : DetailEvent
    data object CopyRemoved : DetailEvent
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
    private var copiesJob: Job? = null
    private var cachedOnly = false

    fun saveMetadata(update: LocalMetadataUpdate): Boolean {
        if (_uiState.value.series == null || _uiState.value.actionInProgress) return false
        _uiState.update { it.copy(actionInProgress = true) }
        viewModelScope.launch {
            try {
                val updated = repository.editMetadata(seriesId, update)
                seasonsJob?.cancel()
                episodesJob?.cancel()
                _uiState.update { it.copy(series = updated, isMovie = MediaSeriesType.isMovie(updated.type),
                    seasons = emptyList(), selectedSeasonId = 0L, episodes = emptyList()) }
                if (!MediaSeriesType.isMovie(updated.type)) observeSeasons()
                refreshPlaybackTarget()
                _events.emit(DetailEvent.MetadataSaved)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _events.emit(DetailEvent.ActionFailed)
            } finally {
                _uiState.update { it.copy(actionInProgress = false) }
            }
        }
        return true
    }

    fun initialize(targetSeriesId: Long, localOnly: Boolean = false) {
        if (seriesId != 0L || targetSeriesId <= 0L) return
        seriesId = targetSeriesId
        cachedOnly = localOnly
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
        _uiState.value = DetailUiState(loading = true, cachedOnly = cachedOnly)
        initializeJob = viewModelScope.launch {
            runCatching {
                val series = repository.getSeries(seriesId)
                if (series == null) {
                    _uiState.update { it.copy(loading = false, missing = true) }
                    return@runCatching
                }
                copiesJob?.cancel()
                copiesJob = viewModelScope.launch {
                    repository.cachedCopies.collectLatest { copies ->
                        _uiState.update { it.copy(cachedCopies = copies.filter { copy -> copy.seriesId == seriesId }) }
                    }
                }
                val isMovie = MediaSeriesType.isMovie(series.type)
                _uiState.update {
                    it.copy(
                        loading = false,
                        series = series,
                        isMovie = isMovie,
                        playbackTarget = repository.playbackTarget(seriesId),
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
        _uiState.update { it.copy(selectedSeasonId = seasonId, episodes = emptyList(), episodesLoading = true) }
        episodesJob?.cancel()
        episodesJob = viewModelScope.launch {
            try {
                repository.observeEpisodes(seriesId, seasonId).collectLatest { episodes ->
                    _uiState.update { current ->
                        if (current.selectedSeasonId == seasonId) current.copy(episodes = if (cachedOnly) episodes.filter { !it.localUri.isNullOrBlank() } else episodes, episodesLoading = false) else current
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                _uiState.update { if (it.selectedSeasonId == seasonId) it.copy(episodesLoading = false) else it }
                _events.emit(DetailEvent.ActionFailed)
            }
        }
    }

    fun playFirstEpisode() {
        val state = _uiState.value
        if (state.series == null) return
        viewModelScope.launch {
            runCatching {
                if (state.cachedOnly) {
                    val target = repository.playbackTarget(seriesId)?.episode
                    target?.takeIf { episode -> state.cachedCopies.any { it.episodeId == episode.id } }
                        ?: state.cachedCopies.sortedWith(compareBy<com.wkq.bao.core.database.entity.CachedMediaRow> { it.seasonNumber }.thenBy { it.episodeNumber })
                            .firstOrNull()?.let { repository.localEpisode(seriesId, it.episodeId) }
                } else repository.playbackTarget(seriesId)?.episode
            }.onSuccess { episode ->
                _events.emit(episode?.let(DetailEvent::Play) ?: DetailEvent.NoPlayableEpisode)
            }.onFailure {
                if (it is CancellationException) throw it
                _events.emit(DetailEvent.ActionFailed)
            }
        }
    }

    fun refreshPlaybackTarget() {
        if (seriesId <= 0L) return
        viewModelScope.launch {
            try {
                val target = repository.playbackTarget(seriesId)
                _uiState.update { it.copy(playbackTarget = target) }
                if (!cachedOnly && _uiState.value.selectedSeasonId == 0L && target != null) selectSeason(target.episode.seasonId)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { _events.emit(DetailEvent.ActionFailed) }
        }
    }

    fun enqueueSelection(episodeIds: List<Long>, target: DownloadTarget) {
        if (_uiState.value.actionInProgress || episodeIds.isEmpty()) return
        _uiState.update { it.copy(actionInProgress = true) }
        viewModelScope.launch {
            try { _events.emit(DetailEvent.DownloadsQueued(repository.enqueueSelection(seriesId, episodeIds, target))) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { _events.emit(DetailEvent.ActionFailed) }
            finally { _uiState.update { it.copy(actionInProgress = false) } }
        }
    }

    fun removeCopy(episodeId: Long, uri: String) {
        if (_uiState.value.actionInProgress || _uiState.value.cachedCopies.none { it.episodeId == episodeId && it.uri == uri }) return
        _uiState.update { it.copy(actionInProgress = true) }
        viewModelScope.launch {
            try {
                _events.emit(if (repository.removeCopy(seriesId, episodeId, uri)) DetailEvent.CopyRemoved else DetailEvent.ActionFailed)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { _events.emit(DetailEvent.ActionFailed) }
            finally { _uiState.update { it.copy(actionInProgress = false) } }
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
            kotlinx.coroutines.flow.combine(repository.observeSeasons(seriesId), repository.cachedCopies) { seasons, copies ->
                if (cachedOnly) seasons.filter { season -> copies.any { it.seriesId == seriesId && it.seasonId == season.id } } else seasons
            }.collectLatest { seasons ->
                val currentSelection = _uiState.value.selectedSeasonId
                val selectedSeasonId = currentSelection.takeIf { selected -> seasons.any { it.id == selected } }
                    ?: _uiState.value.playbackTarget?.episode?.seasonId?.takeIf { id -> seasons.any { it.id == id } }
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
