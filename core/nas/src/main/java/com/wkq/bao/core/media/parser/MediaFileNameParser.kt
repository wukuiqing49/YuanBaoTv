package com.wkq.bao.core.media.parser

import com.wkq.bao.core.database.entity.MediaSeriesType
import java.util.regex.Pattern

/**
 * 媒体文件名正则解析器
 * 支持识别 S01E02、1x02、第1季第2集 等模式
 */
object MediaFileNameParser {

    data class ParsedMediaInfo(
        val seriesTitle: String,
        val seasonNumber: Int,
        val episodeNumber: Int,
        val episodeTitle: String = "",
        val mediaType: String = MediaSeriesType.MOVIE
    )

    // S01E02 或 s1e2
    private val PATTERN_S_E = Pattern.compile("(?i)(.*?)[.\\s_-]*s(\\d{1,2})[.\\s_-]*e(\\d{1,3})(.*)", Pattern.CASE_INSENSITIVE)
    // 01x02
    private val PATTERN_X = Pattern.compile("(?i)(.*?)[.\\s_-]*(\\d{1,2})x(\\d{1,3})(.*)", Pattern.CASE_INSENSITIVE)
    // 中文：第1季 第2集
    private val PATTERN_ZH = Pattern.compile("(.*?)[.\\s_-]*第\\s*(\\d{1,2})\\s*季[.\\s_-]*第\\s*(\\d{1,3})\\s*集(.*)")
    // 中文仅单集：第2集
    private val PATTERN_ZH_EP_ONLY = Pattern.compile("(.*?)[.\\s_-]*第\\s*(\\d{1,3})\\s*集(.*)")

    /** 只有明确的季目录才将纯数字文件识别为集，避免把年份电影误识别。 */
    fun parse(fileNameWithExt: String, parentPath: String): ParsedMediaInfo {
        val directories = parentPath.replace('\\', '/').trim('/').split('/')
        val folder = directories.lastOrNull().orEmpty()
        val seasonMatch = Regex("(?i)^(?:season[ ._-]*|s)([0-9]{1,2})$|^第([0-9]{1,2})季$").matchEntire(folder)
        val season = seasonMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()
        val base = parse(fileNameWithExt)
        val stem = fileNameWithExt.substringBeforeLast('.')
        val ep = Regex("(?i)^(.*?)(?:[ ._-]+|^)(?:episode|ep?|第)[ ._-]*([0-9]{1,3})(?:集)?(?:[ ._-]+(.*))?$").matchEntire(stem)
        val numeric = if (season != null) Regex("^([0-9]{1,3})(?:[ ._-]+(.*))?$").matchEntire(stem) else null
        val folderTitle = if (season != null) directories.dropLast(1).lastOrNull().orEmpty() else folder
        if (base.mediaType == MediaSeriesType.TV) {
            val title = base.seriesTitle.takeUnless { it == stem || it.isBlank() } ?: folderTitle
            val explicitSeason = PATTERN_S_E.matcher(stem).find() || PATTERN_X.matcher(stem).find() || PATTERN_ZH.matcher(stem).find()
            return base.copy(seriesTitle = cleanTitle(title), seasonNumber = if (explicitSeason) base.seasonNumber else season ?: base.seasonNumber)
        }
        val number = ep?.groupValues?.get(2)?.toIntOrNull() ?: numeric?.groupValues?.get(1)?.toIntOrNull()
        if (number != null && (folderTitle.isNotBlank() || !ep?.groupValues?.get(1).isNullOrBlank())) {
            val title = ep?.groupValues?.get(1)?.let(::cleanTitle)?.takeIf { it.isNotBlank() } ?: cleanTitle(folderTitle)
            return ParsedMediaInfo(title, season ?: 1, number,
                ep?.groupValues?.getOrNull(3).orEmpty().ifBlank { numeric?.groupValues?.getOrNull(2).orEmpty() }, MediaSeriesType.TV)
        }
        return base
    }

    fun parse(fileNameWithExt: String): ParsedMediaInfo {
        val fileName = fileNameWithExt.substringBeforeLast(".")

        // 1. 尝试 S01E02
        val matcherSE = PATTERN_S_E.matcher(fileName)
        if (matcherSE.find()) {
            val title = cleanTitle(matcherSE.group(1) ?: "")
            val season = matcherSE.group(2)?.toIntOrNull() ?: 1
            val episode = matcherSE.group(3)?.toIntOrNull() ?: 1
            val epTitle = cleanTitle(matcherSE.group(4) ?: "")
            return ParsedMediaInfo(title.ifEmpty { fileName }, season, episode, epTitle, MediaSeriesType.TV)
        }

        // 2. 尝试 中文：第1季 第2集
        val matcherZH = PATTERN_ZH.matcher(fileName)
        if (matcherZH.find()) {
            val title = cleanTitle(matcherZH.group(1) ?: "")
            val season = matcherZH.group(2)?.toIntOrNull() ?: 1
            val episode = matcherZH.group(3)?.toIntOrNull() ?: 1
            val epTitle = cleanTitle(matcherZH.group(4) ?: "")
            return ParsedMediaInfo(title.ifEmpty { fileName }, season, episode, epTitle, MediaSeriesType.TV)
        }

        // 3. 尝试 01x02
        val matcherX = PATTERN_X.matcher(fileName)
        if (matcherX.find()) {
            val title = cleanTitle(matcherX.group(1) ?: "")
            val season = matcherX.group(2)?.toIntOrNull() ?: 1
            val episode = matcherX.group(3)?.toIntOrNull() ?: 1
            val epTitle = cleanTitle(matcherX.group(4) ?: "")
            return ParsedMediaInfo(title.ifEmpty { fileName }, season, episode, epTitle, MediaSeriesType.TV)
        }

        // 4. 尝试 中文单集
        val matcherZHEp = PATTERN_ZH_EP_ONLY.matcher(fileName)
        if (matcherZHEp.find()) {
            val title = cleanTitle(matcherZHEp.group(1) ?: "")
            val episode = matcherZHEp.group(2)?.toIntOrNull() ?: 1
            val epTitle = cleanTitle(matcherZHEp.group(3) ?: "")
            return ParsedMediaInfo(title.ifEmpty { fileName }, 1, episode, epTitle, MediaSeriesType.TV)
        }

        // 默认作为第 1 季第 1 集
        val ambiguous = Regex("(?i)^(?:[0-9]{1,3}|(?:ep?|episode)[ ._-]*[0-9]{1,3})$").matches(fileName)
        return ParsedMediaInfo(cleanTitle(fileName), 1, 1,
            mediaType = if (ambiguous) MediaSeriesType.LOCAL else MediaSeriesType.MOVIE)
    }

    private fun cleanTitle(raw: String): String {
        return raw.replace(".", " ")
            .replace("_", " ")
            .replace(Regex("(?i)(1080p|720p|2160p|4k|x264|x265|hevc|web-dl|aac|h264|h265)"), "")
            .trim()
    }
}
