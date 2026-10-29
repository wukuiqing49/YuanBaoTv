package com.wkq.bao.feature.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import com.wkq.bao.core.database.entity.MediaSeriesEntity
import com.wkq.bao.core.media.repository.MediaBrowseRepository
import com.wkq.bao.core.media.repository.RoomMediaBrowseRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import com.wkq.bao.core.database.MediaLibraryQuery
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn

data class MediaLibraryUiState(
    val loading: Boolean = true,
    val selectedType: String? = null,
    val series: List<MediaSeriesEntity> = emptyList(),
    val query: String = "",
    val favoritesOnly: Boolean = false,
    val sort: LibrarySort = LibrarySort.RECENT,
    val totalCount: Int = series.size
) {
    val hasFilters: Boolean get() = selectedType != null || query.isNotBlank() || favoritesOnly
    val hasMore: Boolean get() = series.size < totalCount
}

enum class LibrarySort { RECENT, TITLE, YEAR }

private data class LibraryFilters(
    val type: String? = null,
    val query: String = "",
    val favoritesOnly: Boolean = false,
    val sort: LibrarySort = LibrarySort.RECENT
)

class MediaLibraryViewModel(
    private val repository: MediaBrowseRepository,
    private val filterDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle()
) : ViewModel() {
    val offlineSeriesIds = repository.offlineSeriesIds
    private val visibleLimit = MutableStateFlow((savedStateHandle.get<Int>("library_visible_limit") ?: 60).coerceIn(60, 100000))

    private val filters = MutableStateFlow(LibraryFilters(
        type = savedStateHandle["library_type"],
        query = savedStateHandle["library_query"] ?: "",
        favoritesOnly = savedStateHandle["library_favorites"] ?: false,
        sort = LibrarySort.entries.firstOrNull { it.name == savedStateHandle.get<String>("library_sort") }
            ?: LibrarySort.RECENT
    ))

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<MediaLibraryUiState> = combine(filters, visibleLimit) { selection, limit -> selection to limit }
        .flatMapLatest { (selection, limit) ->
            repository.observeLibrary(MediaLibraryQuery(selection.type, selection.query, selection.favoritesOnly, selection.sort.name), limit)
                .map { page -> MediaLibraryUiState(
                    loading = false, selectedType = selection.type, series = page.items,
                    query = selection.query, favoritesOnly = selection.favoritesOnly,
                    sort = selection.sort, totalCount = page.total
                ) }
        }
        .flowOn(filterDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), MediaLibraryUiState(
            selectedType = filters.value.type, query = filters.value.query,
            favoritesOnly = filters.value.favoritesOnly, sort = filters.value.sort
        ))

    fun selectType(type: String?) {
        updateFilters { it.copy(type = type) }
    }

    fun search(query: String) { updateFilters { it.copy(query = query) } }

    fun showFavorites(onlyFavorites: Boolean) { updateFilters { it.copy(favoritesOnly = onlyFavorites) } }

    fun selectSort(sort: LibrarySort) { updateFilters { it.copy(sort = sort) } }

    fun clearFilters() { updateFilters { it.copy(type = null, query = "", favoritesOnly = false) } }

    fun loadMore() {
        val state = uiState.value
        if (state.loading || !state.hasMore || visibleLimit.value > state.series.size) return
        visibleLimit.value = minOf(state.totalCount, visibleLimit.value + 60)
        savedStateHandle["library_visible_limit"] = visibleLimit.value
    }

    private fun updateFilters(transform: (LibraryFilters) -> LibraryFilters) {
        val next = transform(filters.value)
        if (next == filters.value) return
        filters.value = next
        visibleLimit.value = 60
        savedStateHandle["library_visible_limit"] = 60
        val selection = filters.value
        savedStateHandle["library_type"] = selection.type
        savedStateHandle["library_query"] = selection.query
        savedStateHandle["library_favorites"] = selection.favoritesOnly
        savedStateHandle["library_sort"] = selection.sort.name
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val repository = RoomMediaBrowseRepository.create(context)

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(MediaLibraryViewModel::class.java))
            return MediaLibraryViewModel(repository) as T
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            require(modelClass.isAssignableFrom(MediaLibraryViewModel::class.java))
            return MediaLibraryViewModel(repository, savedStateHandle = extras.createSavedStateHandle()) as T
        }
    }
}
