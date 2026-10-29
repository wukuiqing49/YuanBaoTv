package com.wkq.bao.core.database

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wkq.bao.core.database.entity.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaLibraryPagingTest {
    @Test fun largeLibraryQueriesStayBoundedAndIntersectFilters() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            val dao = db.mediaDao()
            db.withTransaction {
                for (index in 1..10000) {
                    val series = dao.insertSeries(MediaSeriesEntity(title = if (index == 1) "100%_影片" else "Film $index",
                        type = "MOVIE", year = "2024", createdAt = index.toLong()))
                    val season = dao.insertSeason(SeasonEntity(seriesId = series, seasonNumber = 1))
                    val episode = dao.insertEpisode(EpisodeEntity(seriesId = series, seasonId = season, episodeNumber = 1))
                    val file = dao.insertMediaFile(MediaFileEntity(episodeId = episode, seriesId = series))
                    dao.insertMediaLocation(MediaLocationEntity(mediaFileId = file, uri = "content://test/$index", storageType = "USB_DRIVE", fileName = "$index.mp4"))
                }
            }
            val start = android.os.SystemClock.elapsedRealtime()
            val page = dao.loadLibraryPage(MediaLibraryQuery(), 60)
            val elapsed = android.os.SystemClock.elapsedRealtime() - start
            android.util.Log.i("YuanBaoBenchmark", "library_10000_first60_ms=$elapsed")
            assertEquals(10000, page.total)
            assertEquals(60, page.items.size)
            assertEquals("Film 10000", page.items.first().title)
            assertEquals(120, dao.loadLibraryPage(MediaLibraryQuery(), 120).items.size)
            assertEquals(1, dao.loadLibraryPage(MediaLibraryQuery(text = "%_"), 60).total)
            assertEquals(0, dao.loadLibraryPage(MediaLibraryQuery(type = "TV"), 60).total)
            assertEquals(0, dao.loadLibraryPage(MediaLibraryQuery(favoritesOnly = true), 60).total)
            assertEquals(1, dao.loadLibraryPage(MediaLibraryQuery(text = "FILM 10000"), 60).total)
        } finally { db.close() }
    }
}
