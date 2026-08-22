package com.wkq.bao.core.database

import android.content.Context
import com.wkq.bao.core.database.entity.EpisodeEntity
import com.wkq.bao.core.database.entity.MediaFileEntity
import com.wkq.bao.core.database.entity.MediaSeriesEntity
import com.wkq.bao.core.database.entity.SeasonEntity
import com.wkq.bao.core.database.entity.WatchHistoryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 演示数据预装载器 (确保冷启动在断网/无 NAS 时仍有完整精美数据)
 */
object SampleDataPreloader {

    private const val SAMPLE_STREAM_URL = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
    private const val BACKDROP_SAMPLE_1 = "https://images.unsplash.com/photo-1534447677768-be436bb09401?w=1280&q=80"
    private const val BACKDROP_SAMPLE_2 = "https://images.unsplash.com/photo-1518791841217-8f162f1e1131?w=1280&q=80"

    fun preloadSampleData(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.getInstance(context)
            val existingSeries = db.mediaDao().getAllSeries().first()
            if (existingSeries.isNotEmpty()) return@launch

            // 1. 预置 5 部动画剧集
            val seriesList = listOf(
                MediaSeriesEntity(
                    id = 1,
                    title = "汪汪队立大功",
                    type = "CARTOON",
                    genre = "益智 / 冒险",
                    totalSeasons = 7,
                    year = "2023",
                    description = "精通科技的10岁男孩莱德救了6条小狗，并将它们训练成了高本领的狗狗巡逻队。无论遇到什么困难，他们总能团结协作化解危机。",
                    backdropUri = BACKDROP_SAMPLE_1
                ),
                MediaSeriesEntity(
                    id = 2,
                    title = "小猪佩奇",
                    type = "CARTOON",
                    genre = "亲子 / 幽默",
                    totalSeasons = 9,
                    year = "2022",
                    description = "小猪佩奇是一个可爱的四岁小猪，她和爸爸猪、妈妈猪和弟弟乔治生活在一起，充满温馨与欢笑。",
                    backdropUri = BACKDROP_SAMPLE_2
                ),
                MediaSeriesEntity(id = 3, title = "海底小纵队", type = "CARTOON", genre = "科普 / 探险", totalSeasons = 5, year = "2021"),
                MediaSeriesEntity(id = 4, title = "超级飞侠", type = "CARTOON", genre = "地理 / 友谊", totalSeasons = 12, year = "2023"),
                MediaSeriesEntity(id = 5, title = "熊出没", type = "CARTOON", genre = "幽默 / 自然", totalSeasons = 10, year = "2024")
            )
            seriesList.forEach { db.mediaDao().insertSeries(it) }

            // 2. 预置季与集数
            val seasons = listOf(
                SeasonEntity(id = 1, seriesId = 1, seasonNumber = 1, title = "第 1 季"),
                SeasonEntity(id = 2, seriesId = 1, seasonNumber = 2, title = "第 2 季"),
                SeasonEntity(id = 3, seriesId = 1, seasonNumber = 3, title = "第 3 季")
            )
            seasons.forEach { db.mediaDao().insertSeason(it) }

            val episodes = listOf(
                EpisodeEntity(id = 1, seriesId = 1, seasonId = 1, episodeNumber = 1, title = "狗狗拯救海象", durationMs = 720000),
                EpisodeEntity(id = 2, seriesId = 1, seasonId = 1, episodeNumber = 2, title = "狗狗巡逻队出发", durationMs = 750000),
                EpisodeEntity(id = 3, seriesId = 1, seasonId = 1, episodeNumber = 3, title = "小猫咪大麻烦", durationMs = 800000),
                EpisodeEntity(id = 4, seriesId = 1, seasonId = 1, episodeNumber = 4, title = "热气球大冒险", durationMs = 710000),
                EpisodeEntity(id = 5, seriesId = 1, seasonId = 1, episodeNumber = 5, title = "超级狗狗大冲刺", durationMs = 900000)
            )
            episodes.forEach { db.mediaDao().insertEpisode(it) }

            // 3. 预置媒体文件 (指向演示流)
            episodes.forEach { ep ->
                val file = MediaFileEntity(
                    id = ep.id,
                    episodeId = ep.id,
                    seriesId = ep.seriesId,
                    nasUri = SAMPLE_STREAM_URL,
                    localUri = null,
                    fileName = "${ep.title}.mp4",
                    fileSize = 1024 * 1024 * 120L,
                    mimeType = "video/mp4",
                    downloadStatus = "NONE"
                )
                db.mediaDao().insertMediaFile(file)
            }

            // 4. 预置继续观看历史记录
            val history1 = WatchHistoryEntity(
                id = 1,
                seriesId = 1,
                seasonId = 1,
                episodeId = 5,
                positionMs = 450000,
                durationMs = 900000,
                completed = false,
                lastPlayedAt = System.currentTimeMillis()
            )
            val history2 = WatchHistoryEntity(
                id = 2,
                seriesId = 1,
                seasonId = 1,
                episodeId = 3,
                positionMs = 520000,
                durationMs = 800000,
                completed = false,
                lastPlayedAt = System.currentTimeMillis() - 86400000
            )
            db.watchHistoryDao().saveHistory(history1)
            db.watchHistoryDao().saveHistory(history2)
        }
    }
}
