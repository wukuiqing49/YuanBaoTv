package com.wkq.bao.core.media.router

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 统一应用指令模型 (供 Deep Link / 小爱同学 / 局域网 Web 遥控 / 快捷指令调用)
 */
sealed class AppCommand {
    data class PlayEpisode(val seriesId: Long, val episodeId: Long) : AppCommand()
    data class OpenSeries(val seriesId: Long) : AppCommand()
    data class Search(val query: String) : AppCommand()
    object ContinueWatching : AppCommand()
    object OpenDownloads : AppCommand()
    object OpenSettings : AppCommand()
}

/**
 * 统一 Command 路由器
 */
object AppCommandRouter {

    fun parse(uri: Uri): AppCommand? {
        val host = uri.host ?: return null
        return when (host) {
            "play" -> {
                val seriesId = uri.getQueryParameter("seriesId")?.toLongOrNull() ?: 0L
                val episodeId = uri.getQueryParameter("episodeId")?.toLongOrNull()
                    ?: uri.pathSegments.lastOrNull()?.toLongOrNull()
                    ?: 0L
                AppCommand.PlayEpisode(seriesId, episodeId)
            }
            "series" -> {
                val seriesId = uri.getQueryParameter("seriesId")?.toLongOrNull()
                    ?: uri.pathSegments.lastOrNull()?.toLongOrNull()
                    ?: 0L
                AppCommand.OpenSeries(seriesId)
            }
            "search" -> {
                val query = uri.getQueryParameter("q") ?: ""
                AppCommand.Search(query)
            }
            "continue" -> AppCommand.ContinueWatching
            "downloads" -> AppCommand.OpenDownloads
            "settings" -> AppCommand.OpenSettings
            else -> null
        }
    }
}
