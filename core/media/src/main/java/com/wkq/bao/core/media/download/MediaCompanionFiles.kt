package com.wkq.bao.core.media.download

import java.util.Locale

internal object MediaCompanionFiles {
    fun localName(video: String, localVideo: String, companion: String): String {
        val originalStem = video.substringBeforeLast('.')
        return if (companion.startsWith(originalStem, ignoreCase = true) &&
            companion.getOrNull(originalStem.length) in listOf('.', '-', '_')) {
            localVideo.substringBeforeLast('.') + companion.substring(originalStem.length)
        } else companion
    }
    fun matches(video: String, candidate: String): Boolean {
        if (candidate.any { it == '/' || it == '\\' } || candidate in setOf(".", "..")) return false
        val name = candidate.lowercase(Locale.ROOT)
        val stem = video.substringBeforeLast('.').lowercase(Locale.ROOT)
        val ext = name.substringAfterLast('.', "")
        val base = name.substringBeforeLast('.')
        if (ext in setOf("jpg", "jpeg", "png", "webp", "nfo") &&
            base in setOf("poster", "folder", "cover", "fanart", "backdrop", "movie", "tvshow")) return true
        return ext in setOf("srt", "vtt", "ass", "ssa", "nfo", "jpg", "jpeg", "png", "webp", "xml", "json") &&
            (base == stem || listOf('.', '-', '_').any { base.startsWith("$stem$it") })
    }
}
