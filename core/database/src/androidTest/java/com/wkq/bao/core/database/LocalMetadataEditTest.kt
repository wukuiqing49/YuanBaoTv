package com.wkq.bao.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wkq.bao.core.database.entity.MediaSeriesEntity
import com.wkq.bao.core.database.entity.SeasonEntity
import com.wkq.bao.core.database.entity.EpisodeEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalMetadataEditTest {
    @Test fun rejectsMovieConversionWithMultipleEpisodesWithoutChangingMetadata() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            val dao = database.mediaDao()
            val id = dao.insertSeries(MediaSeriesEntity(title = "Series", type = "TV"))
            val season = dao.insertSeason(SeasonEntity(seriesId = id, seasonNumber = 1))
            dao.insertEpisode(EpisodeEntity(seriesId = id, seasonId = season, episodeNumber = 1))
            dao.insertEpisode(EpisodeEntity(seriesId = id, seasonId = season, episodeNumber = 2))
            val failure = runCatching { dao.editSeriesMetadata(id, "Movie", "2024", "", "", "MOVIE", null) }.exceptionOrNull()
            assertEquals(true, failure is IllegalArgumentException)
            assertEquals("Series", dao.getSeriesById(id)?.title)
            assertEquals("TV", dao.getSeriesById(id)?.type)
        } finally { database.close() }
    }
    @Test fun editedMetadataSurvivesStaleScanAndCanBeFoundByOriginalName() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            val dao = database.mediaDao()
            val original = MediaSeriesEntity(title = "Filename title", scanTitle = "Filename title", posterUri = "file://old", type = "MOVIE")
            val id = dao.insertSeries(original)
            dao.editSeriesMetadata(id, "Correct title", "2024", "Drama", "", "MOVIE", "content://new-poster")
            dao.updateScannedSeries(original.copy(id = id, title = "Stale title", totalSeasons = 2))
            val edited = dao.getSeriesByTitle("Filename title")!!
            assertEquals(id, edited.id)
            assertEquals("Correct title", edited.title)
            assertEquals("content://new-poster", edited.posterUri)
            assertEquals("", edited.description)
            assertEquals(2, edited.totalSeasons)
            assertEquals(true, edited.metadataEdited)
        } finally { database.close() }
    }
}
