package com.wkq.bao.feature.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class SplashViewModel : ViewModel() {
    suspend fun resolveInitialPage(): Int = MainPageNavigator.HOME

    class Factory(context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SplashViewModel::class.java))
            return SplashViewModel() as T
        }
    }
}
