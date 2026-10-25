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
    private val PATTERN_SEASON_E = Pattern.compile("(?i)(.*?)[.\\s_-]+season[.\\s_-]*(\\d{1,2})[.\\s_-]+(?:episode|ep|e)[.\\s_-]*(\\d{1,3})(?![0-9])(.*)")
    // 01x02
    private val PATTERN_X = Pattern.compile("(?i)(.*?)[.\\s_-]*(?<![0-9])(\\d{1,2})x(\\d{1,3})(?![0-9])(.*)", Pattern.CASE_INSENSITIVE)
    // 中文：第1季 第2集
    private val PATTERN_ZH = Pattern.compile("(.*?)[.\\s_-]*第\\s*([0-9一二三四五六七八九十两]{1,3})\\s*季[.\\s_-]*第\\s*(\\d{1,3})\\s*[集话話](.*)")
    // 中文仅单集：第2集
    private val PATTERN_ZH_EP_ONLY = Pattern.compile("(.*?)[.\\s_-]*第\\s*(\\d{1,3})\\s*[集话話](.*)")

    /** 只有明确的季目录才将纯数字文件识别为集，避免把年份电影误识别。 */
    fun parse(fileNameWithExt: String, parentPath: String): ParsedMediaInfo {
        val directories = parentPath.replace('\\', '/').trim('/').split('/')
        val folder = directories.lastOrNull().orEmpty()
        val seasonMatch = Regex("(?i)^(?:season[ ._-]*|s)([0-9]{1,2})$|^第([0-9一二三四五六七八九十两]{1,3})季$").matchEntire(folder)
        val season = seasonMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.let(::seasonNumber)
        val base = parse(fileNameWithExt)
        val stem = fileNameWithExt.substringBeforeLast('.')
        val ep = Regex("(?i)^(.*?)(?:[ ._-]+|^)(?:episode|ep?|第)[ ._-]*([0-9]{1,3})(?:[集话話])?(?:[ ._-]+(.*))?$").matchEntire(stem)
        val numeric = if (season != null) Regex("^([0-9]{1,3})(?:[ ._-]+(.*))?$").matchEntire(stem) else null
        val folderTitle = if (season != null) directories.dropLast(1).lastOrNull().orEmpty() else folder
        if (base.mediaType == MediaSeriesType.TV) {
            val title = base.seriesTitle.takeUnless { it == stem || it.isBlank() } ?: folderTitle
            val explicitSeason = PATTERN_S_E.matcher(stem).find() || PATTERN_SEASON_E.matcher(stem).find() || PATTERN_X.matcher(stem).find() || PATTERN_ZH.matcher(stem).find()
            return base.copy(seriesTitle = cleanTitle(title), seasonNumber = if (explicitSeason) base.seasonNumber else season ?: base.seasonNumber)
        }
        val number = ep?.groupValues?.get(2)?.toIntOrNull() ?: numeric?.groupValues?.get(1)?.toIntOrNull()
        if (number != null && (folderTitle.isNotBlank() || !ep?.groupValues?.get(1).isNullOrBlank())) {
            val title = ep?.groupValues?.get(1)?.let { cleanTitle(it) }?.takeIf { it.isNotBlank() } ?: cleanTitle(folderTitle)
            val rawEp = ep?.groupValues?.getOrNull(3).orEmpty().ifBlank { numeric?.groupValues?.getOrNull(2).orEmpty() }
            return ParsedMediaInfo(title, season ?: 1, number, cleanTitle(rawEp, allowEmpty = true), MediaSeriesType.TV)
        }
        return base
    }

    fun parse(fileNameWithExt: String): ParsedMediaInfo {
        val fileName = fileNameWithExt.substringBeforeLast(".")

        // 部分片库使用 Season.4.E02，必须保留季号，不能退回默认第一季。
        val matcherSeason = PATTERN_SEASON_E.matcher(fileName)
        if (matcherSeason.find()) {
            return ParsedMediaInfo(cleanTitle(matcherSeason.group(1).orEmpty()).ifBlank { fileName },
                matcherSeason.group(2)?.toIntOrNull() ?: 1,
                matcherSeason.group(3)?.toIntOrNull() ?: 1,
                cleanTitle(matcherSeason.group(4).orEmpty(), allowEmpty = true), MediaSeriesType.TV)
        }

        // 1. 尝试 S01E02
        val matcherSE = PATTERN_S_E.matcher(fileName)
        if (matcherSE.find()) {
            val title = cleanTitle(matcherSE.group(1) ?: "")
            val season = matcherSE.group(2)?.toIntOrNull() ?: 1
            val episode = matcherSE.group(3)?.toIntOrNull() ?: 1
            val epTitle = cleanTitle(matcherSE.group(4) ?: "", allowEmpty = true)
            return ParsedMediaInfo(title.ifEmpty { fileName }, season, episode, epTitle, MediaSeriesType.TV)
        }

        // 2. 尝试 中文：第1季 第2集
        val matcherZH = PATTERN_ZH.matcher(fileName)
        if (matcherZH.find()) {
            val title = cleanTitle(matcherZH.group(1) ?: "")
            val season = matcherZH.group(2)?.let(::seasonNumber) ?: 1
            val episode = matcherZH.group(3)?.toIntOrNull() ?: 1
            val epTitle = cleanTitle(matcherZH.group(4) ?: "", allowEmpty = true)
            return ParsedMediaInfo(title.ifEmpty { fileName }, season, episode, epTitle, MediaSeriesType.TV)
        }

        // 3. 尝试 01x02
        val matcherX = PATTERN_X.matcher(fileName)
        if (matcherX.find()) {
            val title = cleanTitle(matcherX.group(1) ?: "")
            val season = matcherX.group(2)?.toIntOrNull() ?: 1
            val episode = matcherX.group(3)?.toIntOrNull() ?: 1
            val epTitle = cleanTitle(matcherX.group(4) ?: "", allowEmpty = true)
            return ParsedMediaInfo(title.ifEmpty { fileName }, season, episode, epTitle, MediaSeriesType.TV)
        }

        // 4. 尝试 中文单集
        val matcherZHEp = PATTERN_ZH_EP_ONLY.matcher(fileName)
        if (matcherZHEp.find()) {
            val title = cleanTitle(matcherZHEp.group(1) ?: "")
            val episode = matcherZHEp.group(2)?.toIntOrNull() ?: 1
            val epTitle = cleanTitle(matcherZHEp.group(3) ?: "", allowEmpty = true)
            return ParsedMediaInfo(title.ifEmpty { fileName }, 1, episode, epTitle, MediaSeriesType.TV)
        }

        // 5. 尝试 单集标记 EP01 / E01 (如 斗破苍穹.EP05.1080p)
        val matcherEP = Pattern.compile("(?i)(.*?)[.\\s_-]+(?:ep|e)(\\d{1,3})(?![0-9])(.*)").matcher(fileName)
        if (matcherEP.find()) {
            val title = cleanTitle(matcherEP.group(1) ?: "")
            if (title.isNotBlank() && !title.startsWith("season", ignoreCase = true)) {
                val episode = matcherEP.group(2)?.toIntOrNull() ?: 1
                val epTitle = cleanTitle(matcherEP.group(3) ?: "", allowEmpty = true)
                return ParsedMediaInfo(title, 1, episode, epTitle, MediaSeriesType.TV)
            }
        }

        // 6. 尝试 动漫中括号集数 (如 [DBD-Raws][进击的巨人][76][1080P] 或 进击的巨人.[01].mkv)
        val bracketEpMatch = Regex("""\[(\d{1,3})\]""").find(fileName)
        if (bracketEpMatch != null) {
            val episode = bracketEpMatch.groupValues[1].toIntOrNull() ?: 1
            val prefix = fileName.substring(0, bracketEpMatch.range.first).trim()
            val bracketContents = Regex("""\[([^\]]+)\]""").findAll(prefix).map { it.groupValues[1] }.toList()
            val candidateTitle = if (bracketContents.isNotEmpty()) bracketContents.last() else prefix
            val title = cleanTitle(candidateTitle)
            if (title.isNotBlank() && title.lowercase() !in setOf("1080p", "720p", "2160p", "4k")) {
                val epTitle = cleanTitle(fileName.substring(bracketEpMatch.range.last + 1), allowEmpty = true)
                return ParsedMediaInfo(title, 1, episode, epTitle, MediaSeriesType.TV)
            }
        }

        // 默认作为第 1 季第 1 集
        val ambiguous = Regex("(?i)^(?:[0-9]{1,3}|(?:ep?|episode)[ ._-]*[0-9]{1,3})$").matches(fileName)
        return ParsedMediaInfo(cleanTitle(fileName), 1, 1,
            mediaType = if (ambiguous) MediaSeriesType.LOCAL else MediaSeriesType.MOVIE)
    }

    private fun seasonNumber(value: String): Int? {
        value.toIntOrNull()?.let { return it }
        val digits = mapOf('一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        if (value == "十") return 10
        if ('十' in value) {
            val parts = value.split('十')
            if (parts.size != 2 || parts.any { it.length > 1 }) return null
            val tens = if (parts[0].isEmpty()) 1 else digits[parts[0].single()] ?: return null
            val units = if (parts[1].isEmpty()) 0 else digits[parts[1].single()] ?: return null
            return tens * 10 + units
        }
        return value.singleOrNull()?.let(digits::get)
    }

    private fun cleanTitle(raw: String, allowEmpty: Boolean = false): String {
        var title = raw
        // 去除前置方括号/花括号发布组与广告标签 (例如 [阳光电影 www.ygdy8.com] 或 【电影天堂】)
        title = title.replace(Regex("""^[\[【][^\]】]+[\]】]"""), "")
        title = title.replace(Regex("""[\[【][^\]】]*(?:www|com|net|org|cn|dy|bt|电影|影视|字幕组|发布|论坛|分享|vip)[^\]】]*[\]】]""", RegexOption.IGNORE_CASE), "")
        // 去除网址
        title = title.replace(Regex("""(?i)\b(?:https?://)?(?:www\.)?[a-zA-Z0-9-]+\.(?:com|cn|net|org|cc|me|tv|xyz|vip)\b"""), "")
        // 去除压制组常见后缀 (例如 -OurTV, -CHD, -WiKi)
        title = title.replace(Regex("""(?i)[-_.][a-zA-Z0-9]+$"""), "")
        // 将点和下划线替换为空格
        title = title.replace(".", " ").replace("_", " ")
        // 去除分辨率与特殊格式标记
        title = title.replace(Regex("""(?i)(?<![0-9])(?:1920x1080|3840x2160|1280x720)(?![0-9])"""), "")
        title = title.replace(Regex("""(?i)\bmp[0-9]hd[0-9]*(?:v[0-9]+)?\b"""), "")
        title = title.replace(Regex("""[（(](?:超高清|高清|蓝光|SDR|HDR|4K|1080P)[^）)]*[）)]""", RegexOption.IGNORE_CASE), "")
        // 去除压制参数、画质规格与音轨
        title = title.replace(Regex("""(?i)\b(1080p|720p|2160p|4k|hd|uhd|fhd|bd|tc|x264|x265|hevc|web-dl|webrip|bluray|bdrip|remux|h264|h265|aac|ac3|dts|atmos|hdr10\+?|hdr|sdr|10bit|dovi|truehd|flac|dts-hd)\b"""), "")
        // 去除字幕组音轨与版本标记
        title = title.replace(Regex("""(?i)\b(?:国语中字|中文字幕|双语字幕|中英双字|中英字幕|国粤双语|简繁中字|官译中字|纯净版|未删减版|杜比视界|杜比全景声|高码版)\b"""), "")
        title = title.replace(Regex("""^[-_\s]+|[-_\s]+$"""), "").trim()
        title = title.replace(Regex("""\s+"""), " ").trim()
        if (allowEmpty && (title.isBlank() || title == "-" || title == "_")) {
            return ""
        }
        return title.ifBlank { raw.substringBeforeLast('.').trim() }
    }
}
