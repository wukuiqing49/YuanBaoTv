package com.wkq.bao.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wkq.bao.core.database.entity.EpisodeEntity
import com.wkq.bao.core.database.entity.MediaFileEntity
import com.wkq.bao.core.database.entity.MediaLocationEntity
import com.wkq.bao.core.database.entity.MediaSeriesEntity
import com.wkq.bao.core.database.entity.SeasonEntity
import com.wkq.bao.core.database.entity.WatchHistoryEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalOnlyMediaQueryTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun libraryAndHistoryOnlyExposeEpisodesWithLocalCopies() = runBlocking {
        val mediaDao = database.mediaDao()
        val seriesId = mediaDao.insertSeries(MediaSeriesEntity(title = "Series"))
        val seasonId = mediaDao.insertSeason(SeasonEntity(seriesId = seriesId, seasonNumber = 1))
        val remoteOnly = EpisodeEntity(seriesId = seriesId, seasonId = seasonId, episodeNumber = 1)
        val downloaded = EpisodeEntity(seriesId = seriesId, seasonId = seasonId, episodeNumber = 2)
        val remoteOnlyId = mediaDao.insertEpisode(remoteOnly)
        val downloadedId = mediaDao.insertEpisode(downloaded)
        val remoteFileId = mediaDao.insertMediaFile(
            MediaFileEntity(episodeId = remoteOnlyId, seriesId = seriesId, nasUri = "smb://nas/remote.mp4")
        )
        val downloadedFileId = mediaDao.insertMediaFile(
            MediaFileEntity(episodeId = downloadedId, seriesId = seriesId, nasUri = "smb://nas/local.mp4")
        )
        mediaDao.insertMediaLocation(
            MediaLocationEntity(
                mediaFileId = downloadedFileId,
                uri = "content://storage/document/usb%3ADownloads%2Flocal.mp4",
                storageType = "USB_DRIVE",
                fileName = "local.mp4"
            )
        )
        database.watchHistoryDao().saveHistory(
            WatchHistoryEntity(seriesId = seriesId, seasonId = seasonId, episodeId = remoteOnlyId, positionMs = 180_000L)
        )
        database.watchHistoryDao().saveHistory(
            WatchHistoryEntity(seriesId = seriesId, seasonId = seasonId, episodeId = downloadedId, positionMs = 180_000L)
        )

        assertEquals(listOf(seriesId), mediaDao.getDownloadedSeries().first().map { it.id })
        assertEquals(listOf(seasonId), mediaDao.getDownloadedSeasonsBySeriesId(seriesId).first().map { it.id })
        val visibleEpisodes = mediaDao.getEpisodesWithSource(seriesId, seasonId).first()
        assertEquals(listOf(downloadedId), visibleEpisodes.map { it.episode.id })
        assertEquals("content://storage/document/usb%3ADownloads%2Flocal.mp4", visibleEpisodes.single().localUri)
        assertNull(visibleEpisodes.single().nasUri)
        assertEquals(downloadedId, mediaDao.getFirstDownloadedEpisode(seriesId)?.id)
        assertNull(mediaDao.getDownloadedEpisodeByNumber(seasonId, remoteOnly.episodeNumber))
        assertEquals(downloadedId, mediaDao.getDownloadedEpisodeByNumber(seasonId, downloaded.episodeNumber)?.id)
        assertEquals(listOf(downloadedId), database.watchHistoryDao().getContinueWatchingList().first().map { it.history.episodeId })

        // 保留该局部变量可确保测试数据包含完整的仅 NAS 媒体文件记录。
        assertEquals(remoteOnlyId, mediaDao.getMediaFileById(remoteFileId)?.episodeId)
    }
}
