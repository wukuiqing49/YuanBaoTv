package com.wkq.bao.core.media.resolver

import java.util.Locale

internal object SubtitleFileMatcher {
    fun mimeType(videoName: String, subtitleName: String): String? {
        val videoStem = videoName.substringBeforeLast('.').lowercase(Locale.ROOT)
        val subtitleStem = subtitleName.substringBeforeLast('.').lowercase(Locale.ROOT)
        if (videoStem.isBlank() || !(subtitleStem == videoStem ||
                listOf('.', '-', '_').any { subtitleStem.startsWith("$videoStem$it") })) return null
        return when (subtitleName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "srt" -> "application/x-subrip"
            "vtt" -> "text/vtt"
            "ass", "ssa" -> "text/x-ssa"
            else -> null
        }
    }

    fun isExactMatch(videoName: String, subtitleName: String): Boolean =
        videoName.substringBeforeLast('.').equals(subtitleName.substringBeforeLast('.'), true)

    fun language(videoName: String, subtitleName: String): String? {
        if (mimeType(videoName, subtitleName) == null) return null
        val suffix = subtitleName.substringBeforeLast('.').drop(videoName.substringBeforeLast('.').length)
        val tokens = suffix.lowercase(Locale.ROOT).split('.', '-', '_')
        return tokens.firstNotNullOfOrNull { token ->
            when (token) {
                "zh", "zho", "chi", "chs", "cht", "sc", "tc" -> "zh"
                "en", "eng" -> "en"
                "ja", "jpn" -> "ja"
                "ko", "kor" -> "ko"
                "fr", "fra", "fre" -> "fr"
                "de", "deu", "ger" -> "de"
                "es", "spa" -> "es"
                else -> null
            }
        }
    }
}
