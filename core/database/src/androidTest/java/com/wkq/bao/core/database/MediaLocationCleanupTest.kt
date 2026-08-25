package com.wkq.bao.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wkq.bao.core.database.entity.NasSourceEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaLocationCleanupTest {
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
    fun deleteNotSeenInTreeOnlyRemovesStaleLocationsFromSelectedTree() = runBlocking {
        val db = database.openHelper.writableDatabase
        db.execSQL("INSERT INTO media_series (id, title, originalTitle, type, posterUri, backdropUri, description, year, genre, totalSeasons, createdAt, updatedAt) VALUES (1, 'Series', '', 'MOVIE', '', '', '', '', '', 1, 1, 1)")
        db.execSQL("INSERT INTO seasons (id, seriesId, seasonNumber, title, posterUri, episodeCount) VALUES (1, 1, 1, '', '', 1)")
        db.execSQL("INSERT INTO episodes (id, seriesId, seasonId, episodeNumber, title, description, durationMs, thumbnailUri, airDate) VALUES (1, 1, 1, 1, '', '', 0, '', '')")
        db.execSQL("INSERT INTO media_files (id, episodeId, seriesId, nasSourceId, nasUri, localUri, localStorageType, fileName, fileSize, mimeType, checksum, downloadStatus, createdAt, updatedAt) VALUES (1, 1, 1, NULL, '', NULL, '', 'episode.mp4', 1, 'video/mp4', '', 'NONE', 1, 1)")

        val selectedTree = "content://com.android.externalstorage.documents/tree/AAAA%3A"
        val otherTree = "content://com.android.externalstorage.documents/tree/BBBB%3A"
        db.execSQL("INSERT INTO media_locations (mediaFileId, uri, storageType, fileName, fileSize, updatedAt) VALUES (1, '$selectedTree/document/AAAA%3AMovies%2Fold.mp4', 'USB', 'old.mp4', 1, 10)")
        db.execSQL("INSERT INTO media_locations (mediaFileId, uri, storageType, fileName, fileSize, updatedAt) VALUES (1, '$selectedTree/document/AAAA%3AMovies%2Fcurrent.mp4', 'USB', 'current.mp4', 1, 20)")
        db.execSQL("INSERT INTO media_locations (mediaFileId, uri, storageType, fileName, fileSize, updatedAt) VALUES (1, '$otherTree/document/BBBB%3AMovies%2Fold.mp4', 'USB', 'other.mp4', 1, 10)")
        db.execSQL("INSERT INTO media_locations (mediaFileId, uri, storageType, fileName, fileSize, updatedAt) VALUES (1, 'content://media/external/video/media/42', 'LOCAL', 'local.mp4', 1, 10)")

        val deleted = database.mediaDao().deleteMediaLocationsNotSeenInTree(selectedTree, 20)

        assertEquals(1, deleted)
        db.query("SELECT uri FROM media_locations ORDER BY uri").use { cursor ->
            val remainingUris = buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
            assertEquals(3, remainingUris.size)
            assertEquals(false, remainingUris.any { it.endsWith("old.mp4") && it.contains("AAAA%3A") })
            assertEquals(true, remainingUris.any { it.endsWith("current.mp4") })
            assertEquals(true, remainingUris.any { it.contains("BBBB%3A") })
            assertEquals(true, remainingUris.any { it.startsWith("content://media/") })
        }
    }

    @Test
    fun enabledSmbSourceLookupNormalizesHostAndShareAndExcludesDisabledSources() = runBlocking {
        database.nasDao().insertSource(
            NasSourceEntity(
                name = "Primary NAS",
                host = "Nas.Local",
                shareName = "/Media/",
                enabled = true
            )
        )
        database.nasDao().insertSource(
            NasSourceEntity(
                name = "Disabled NAS",
                host = "nas.local",
                shareName = "Archive",
                enabled = false
            )
        )

        val source = database.nasDao().getEnabledSmbSourceByAddress("nas.local", "Media")

        assertEquals("Primary NAS", source?.name)
        assertEquals(null, database.nasDao().getEnabledSmbSourceByAddress("nas.local", "Archive"))
    }
}
