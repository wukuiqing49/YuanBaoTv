package com.wkq.bao.feature.app

/** 子页面通过宿主统一切换，避免 Fragment 之间直接耦合。 */
interface MainPageNavigator {
    fun showPage(page: Int)

    companion object {
        const val HOME = 0
        const val LIBRARY = 1
        const val DOWNLOADS = 2
        const val NAS = 3
    }
}
