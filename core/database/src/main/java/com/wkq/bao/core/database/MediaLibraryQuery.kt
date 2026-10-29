package com.wkq.bao.core.database

import androidx.sqlite.db.SimpleSQLiteQuery
import com.wkq.bao.core.database.entity.MediaSeriesEntity

/** 参数全部绑定；排序仅使用固定白名单，展示分页不会载入整个片库。 */
data class MediaLibraryQuery(
    val type: String? = null,
    val text: String = "",
    val favoritesOnly: Boolean = false,
    val sort: String = "RECENT"
) {
    private val where: String get() = """
        FROM media_series s WHERE
        (? IS NULL OR s.type = ?) AND
        (? = '' OR instr(lower(s.title), lower(?)) > 0 OR instr(lower(s.originalTitle), lower(?)) > 0) AND
        (? = 0 OR EXISTS (SELECT 1 FROM favorites f WHERE f.seriesId = s.id)) AND
        EXISTS (SELECT 1 FROM media_files m WHERE m.seriesId = s.id AND (
            EXISTS (SELECT 1 FROM media_locations l WHERE l.mediaFileId = m.id) OR
            EXISTS (SELECT 1 FROM media_remote_sources r INNER JOIN nas_sources n ON n.id = r.nasSourceId
                WHERE r.mediaFileId = m.id AND n.enabled = 1)))
    """.trimIndent()
    private fun args(): Array<Any?> = arrayOf(type, type, text.trim(), text.trim(), text.trim(), if (favoritesOnly) 1 else 0)
    fun countSql() = SimpleSQLiteQuery("SELECT COUNT(*) $where", args())
    fun pageSql(limit: Int): SimpleSQLiteQuery {
        val order = when (sort) {
            "TITLE" -> "lower(s.title), s.id"
            "YEAR" -> "CAST(s.year AS INTEGER) DESC, s.title, s.id"
            else -> "s.createdAt DESC, s.id"
        }
        return SimpleSQLiteQuery("SELECT s.* $where ORDER BY $order LIMIT ?", args() + limit.coerceIn(1, 100000))
    }
}

data class MediaLibraryPage(val items: List<MediaSeriesEntity>, val total: Int)
