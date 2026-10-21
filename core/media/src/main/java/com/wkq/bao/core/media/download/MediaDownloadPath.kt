package com.wkq.bao.core.media.download

import com.wkq.bao.core.database.entity.MediaSeriesEntity

internal object MediaDownloadPath {
    fun category(series: MediaSeriesEntity): String = if (series.type == "MOVIE") "Movies" else "TVShows"
    fun folder(series: MediaSeriesEntity): String = DownloadFileName.sanitize(
        series.title + series.year.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
    ).let { name -> name.takeWhileIndexedBytes(200) }.trim().ifBlank { "media" } + " [${series.id}]"
    private fun String.takeWhileIndexedBytes(limit: Int): String {
        var result = this
        while (result.toByteArray(Charsets.UTF_8).size > limit) result = result.dropLast(1).dropLastWhile { Character.isHighSurrogate(it) }
        return result
    }
    fun season(number: Int) = "Season ${number.toString().padStart(2, '0')}"
    fun fileName(series: MediaSeriesEntity, season: Int, episode: Int, original: String): String {
        if (series.type == "MOVIE") return DownloadFileName.sanitize(original)
        val extension = original.substringAfterLast('.', "mp4").takeIf { it.matches(Regex("[a-zA-Z0-9]{1,10}")) } ?: "mp4"
        val title = DownloadFileName.sanitize(series.scanTitle.ifBlank { series.title }).takeWhileIndexedBytes(180)
        return DownloadFileName.sanitize("$title.S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}.$extension")
    }
}
