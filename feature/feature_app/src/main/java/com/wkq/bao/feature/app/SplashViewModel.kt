package com.wkq.bao.feature.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.wkq.bao.core.media.repository.NasSettingsRepository
import com.wkq.bao.core.media.repository.RoomNasSettingsRepository
import kotlinx.coroutines.flow.first

class SplashViewModel(private val repository: NasSettingsRepository) : ViewModel() {
    suspend fun resolveInitialPage(): Int =
        if (repository.sources.first().isEmpty()) MainPageNavigator.NAS else MainPageNavigator.HOME

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val repository = RoomNasSettingsRepository.create(context)

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SplashViewModel::class.java))
            return SplashViewModel(repository) as T
        }
    }
}
