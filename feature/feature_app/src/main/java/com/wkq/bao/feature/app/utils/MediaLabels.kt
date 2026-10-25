package com.wkq.bao.feature.app.utils

import android.content.Context
import com.wkq.bao.core.database.entity.MediaSeriesType

object MediaLabels {
    /** 展示名优先中文；保留原始标题供详情、搜索和索引使用。 */
    fun displayTitle(title: String): String {
        val trimmed = title.trim()
        val bilingual = Regex("(?<=[\\u3400-\\u9FFF])\\s+(?=[A-Za-z])").split(trimmed, limit = 2)
        val name = bilingual.first()
            .replace(Regex("\\s*第\\s*\\d+\\s*[集话話].*$"), "")
            .replace(Regex("[（(](?:超高清|高清|蓝光|SDR|HDR|4K|1080P|720P)[^）)]*[）)]", RegexOption.IGNORE_CASE), "")
            .trim()
        return name.ifBlank { trimmed }
    }

    /** 首页只合并展示同一剧集的分集条目，不修改数据库或媒体库内容。 */
    fun homeCollectionKey(title: String): String = displayTitle(title)
        .replace(Regex("\\s*第\\s*\\d+\\s*[集话話].*$"), "")
        .trim().lowercase(java.util.Locale.ROOT)

    /** 过滤未知值并去重，避免缺失信息被渲染为占位分隔符或重复类型。 */
    fun metadataValues(vararg values: String): List<String> = values.map(String::trim)
        .filter { it.isNotEmpty() && it != "-" && it != "--" && !it.equals("unknown", true) }
        .distinctBy { it.lowercase(java.util.Locale.ROOT) }

    fun metadata(context: Context, year: String, genre: String, type: String, seasons: Int): String {
        val seasonLabel = if (!MediaSeriesType.isMovie(type) && seasons > 0) {
            context.getString(com.wkq.bao.feature.res.R.string.label_seasons_count, seasons)
        } else ""
        val category = if (genre.isBlank() && seasonLabel.isNotEmpty()) "" else genreOrType(context, genre, type)
        return metadataValues(year, seasonLabel, category)
            .joinToString(context.getString(com.wkq.bao.feature.res.R.string.media_metadata_separator))
    }

    fun genreOrType(context: Context, genre: String, type: String): String = genre.ifBlank {
        context.getString(
            when (type) {
                MediaSeriesType.MOVIE -> com.wkq.bao.feature.res.R.string.nav_movie
                MediaSeriesType.TV -> com.wkq.bao.feature.res.R.string.nav_tv
                MediaSeriesType.CARTOON -> com.wkq.bao.feature.res.R.string.nav_cartoon
                else -> com.wkq.bao.feature.res.R.string.nav_all_media
            }
        )
    }
}
