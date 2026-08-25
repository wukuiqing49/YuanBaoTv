package com.wkq.bao.feature.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wkq.bao.core.database.entity.MediaSeriesEntity
import com.wkq.bao.core.media.repository.MediaBrowseRepository
import com.wkq.bao.core.media.repository.RoomMediaBrowseRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class MediaLibraryUiState(
    val loading: Boolean = true,
    val selectedType: String? = null,
    val series: List<MediaSeriesEntity> = emptyList()
)

@OptIn(ExperimentalCoroutinesApi::class)
class MediaLibraryViewModel(
    private val repository: MediaBrowseRepository
) : ViewModel() {

    private val selectedType = MutableStateFlow<String?>(null)

    val uiState: StateFlow<MediaLibraryUiState> = selectedType
        .flatMapLatest { type ->
            repository.observeSeriesByType(type).map { series ->
                MediaLibraryUiState(loading = false, selectedType = type, series = series)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), MediaLibraryUiState())

    fun selectType(type: String?) {
        selectedType.value = type
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val repository = RoomMediaBrowseRepository.create(context)

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(MediaLibraryViewModel::class.java))
            return MediaLibraryViewModel(repository) as T
        }
    }
}
