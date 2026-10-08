package com.wkq.bao.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migrationFrom10To11CreatesNasSourceTaskIndex() {
        migrationHelper.createDatabase(TEST_DATABASE_NAME, 10).close()

        val migratedDatabase = migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE_NAME,
            11,
            true,
            AppDatabase.MIGRATION_10_11
        )
        migratedDatabase.query("PRAGMA index_list(`download_tasks`)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            val hasSourceIndex = generateSequence {
                if (cursor.moveToNext()) cursor.getString(nameColumn) else null
            }.any { it == "index_download_tasks_sourceNasId" }
            assertTrue("迁移后必须存在 NAS 来源任务索引", hasSourceIndex)
        }
        migratedDatabase.close()
    }

    @Test
    fun migrationFrom11To12CreatesScanSessionsAndTargetSpecificDownloads() {
        migrationHelper.createDatabase(TEST_DATABASE_NAME, 11).close()

        val migratedDatabase = migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE_NAME,
            12,
            true,
            AppDatabase.MIGRATION_11_12
        )
        val indexNames = migratedDatabase.query("PRAGMA index_list(`download_tasks`)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(nameColumn))
            }
        }
        assertFalse(indexNames.contains("index_download_tasks_episodeId"))
        assertTrue(indexNames.contains("index_download_tasks_episodeId_targetUri"))

        migratedDatabase.query("SELECT COUNT(*) FROM scan_sessions").use { cursor ->
            assertTrue(cursor.moveToFirst())
        }
        migratedDatabase.close()
    }

    @Test
    fun migrationFrom10To12RunsSupportedChain() {
        migrationHelper.createDatabase(TEST_DATABASE_NAME, 10).close()

        val migratedDatabase = migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE_NAME,
            12,
            true,
            AppDatabase.MIGRATION_10_11,
            AppDatabase.MIGRATION_11_12
        )
        migratedDatabase.query("SELECT COUNT(*) FROM scan_sessions").use { cursor ->
            assertTrue(cursor.moveToFirst())
        }
        migratedDatabase.close()
    }

    private companion object {
        const val TEST_DATABASE_NAME = "migration-test"
    }

    @Test
    fun migrationFrom12To13PreservesExistingMedia() {
        migrationHelper.createDatabase(TEST_DATABASE_NAME, 12).apply {
            execSQL("INSERT INTO media_series (id, title, originalTitle, type, posterUri, backdropUri, description, year, genre, totalSeasons, createdAt, updatedAt) VALUES (1, 'Original', '', 'MOVIE', 'file://poster', '', '', '2024', '', 1, 1, 1)")
            close()
        }
        val migrated = migrationHelper.runMigrationsAndValidate(TEST_DATABASE_NAME, 13, true, AppDatabase.MIGRATION_12_13)
        migrated.query("SELECT title, posterUri, scanTitle, metadataEdited FROM media_series WHERE id=1").use {
            assertTrue(it.moveToFirst())
            org.junit.Assert.assertEquals("Original", it.getString(0))
            org.junit.Assert.assertEquals("file://poster", it.getString(1))
            org.junit.Assert.assertEquals("", it.getString(2))
            org.junit.Assert.assertEquals(0, it.getInt(3))
        }
        migrated.close()
    }
}
