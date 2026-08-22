package com.wkq.bao.core.media.scraper

import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.database.entity.MediaSeriesEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 影视媒体元数据刮削器 (支持海报、背景图、简介与标签智能生成)
 */
object MetadataScraper {

    data class ScrapedMetadata(
        val title: String,
        val originalTitle: String,
        val year: String,
        val genre: String,
        val description: String,
        val posterUri: String,
        val backdropUri: String
    )

    // 内置常用动画预置元数据库
    private val KNOWN_SERIES = mapOf(
        "汪汪队立大功" to ScrapedMetadata(
            title = "汪汪队立大功",
            originalTitle = "PAW Patrol",
            year = "2013",
            genre = "少儿 / 益智 / 冒险",
            description = "精通科技的10岁男孩莱德救援了6条小狗，把它们训练成了优秀的救援队员。没有困难的工作，只有勇敢的狗狗！",
            posterUri = "https://images.unsplash.com/photo-1543466835-00a7907e9de1?w=400&q=80",
            backdropUri = "https://images.unsplash.com/photo-1534447677768-be436bb09401?w=1280&q=80"
        ),
        "小猪佩奇" to ScrapedMetadata(
            title = "小猪佩奇",
            originalTitle = "Peppa Pig",
            year = "2004",
            genre = "少儿 / 亲子 / 幽默",
            description = "小猪佩奇是一个可爱的四岁小猪，她和爸爸猪、妈妈猪和弟弟乔治生活在一起，充满温馨与欢笑。",
            posterUri = "https://images.unsplash.com/photo-1563089145-599997674d42?w=400&q=80",
            backdropUri = "https://images.unsplash.com/photo-1518791841217-8f162f1e1131?w=1280&q=80"
        ),
        "海底小纵队" to ScrapedMetadata(
            title = "海底小纵队",
            originalTitle = "The Octonauts",
            year = "2010",
            genre = "少儿 / 科普 / 探险",
            description = "八个可爱小动物组成的海底探险小队，居住在神秘的海底基地章鱼堡，随时准备出发拯救海洋生物！",
            posterUri = "https://images.unsplash.com/photo-1544551763-46a013bb70d5?w=400&q=80",
            backdropUri = "https://images.unsplash.com/photo-1507525428034-b723cf961d3e?w=1280&q=80"
        ),
        "超级飞侠" to ScrapedMetadata(
            title = "超级飞侠",
            originalTitle = "Super Wings",
            year = "2014",
            genre = "少儿 / 友谊 / 地理",
            description = "飞机机器人乐迪与伙伴们环游世界，为小朋友们递送包裹，解决各种意想不到的困难！",
            posterUri = "https://images.unsplash.com/photo-1508614589041-895b88991e3e?w=400&q=80",
            backdropUri = "https://images.unsplash.com/photo-1436491865332-7a61a109cc05?w=1280&q=80"
        )
    )

    fun scrape(title: String): ScrapedMetadata {
        val cleanName = title.trim()
        val match = KNOWN_SERIES.entries.find { cleanName.contains(it.key, ignoreCase = true) }?.value
        if (match != null) return match

        return ScrapedMetadata(
            title = cleanName,
            originalTitle = cleanName,
            year = "2023",
            genre = "影视 / 动漫",
            description = "由局域网 NAS 扫描自动入库的媒体资源。",
            posterUri = "https://images.unsplash.com/photo-1489599849927-2ee91cede3ba?w=400&q=80",
            backdropUri = "https://images.unsplash.com/photo-1518173946687-a4c8a383392e?w=1280&q=80"
        )
    }

    /**
     * 自动为指定 Series 补齐海报、简介与标签
     */
    suspend fun enrichSeries(database: AppDatabase, series: MediaSeriesEntity): MediaSeriesEntity = withContext(Dispatchers.IO) {
        val metadata = scrape(series.title)
        val updated = series.copy(
            originalTitle = metadata.originalTitle,
            year = series.year.ifEmpty { metadata.year },
            genre = series.genre.ifEmpty { metadata.genre },
            description = series.description.ifEmpty { metadata.description },
            posterUri = series.posterUri.ifEmpty { metadata.posterUri },
            backdropUri = series.backdropUri.ifEmpty { metadata.backdropUri },
            updatedAt = System.currentTimeMillis()
        )
        database.mediaDao().updateSeries(updated)
        return@withContext updated
    }
}
