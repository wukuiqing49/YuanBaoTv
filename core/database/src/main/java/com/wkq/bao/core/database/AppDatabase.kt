package com.wkq.bao.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.wkq.bao.core.database.dao.DownloadDao
import com.wkq.bao.core.database.dao.FavoriteDao
import com.wkq.bao.core.database.dao.MediaDao
import com.wkq.bao.core.database.dao.NasDao
import com.wkq.bao.core.database.dao.ScanSessionDao
import com.wkq.bao.core.database.dao.WatchHistoryDao
import com.wkq.bao.core.database.entity.DownloadTaskEntity
import com.wkq.bao.core.database.entity.DownloadChunkEntity
import com.wkq.bao.core.database.entity.EpisodeEntity
import com.wkq.bao.core.database.entity.FavoriteEntity
import com.wkq.bao.core.database.entity.MediaFileEntity
import com.wkq.bao.core.database.entity.MediaLocationEntity
import com.wkq.bao.core.database.entity.MediaRemoteSourceEntity
import com.wkq.bao.core.database.entity.MediaSeriesEntity
import com.wkq.bao.core.database.entity.NasSourceEntity
import com.wkq.bao.core.database.entity.SeasonEntity
import com.wkq.bao.core.database.entity.ScanSessionEntity
import com.wkq.bao.core.database.entity.WatchHistoryEntity

@Database(
    entities = [
        NasSourceEntity::class,
        MediaSeriesEntity::class,
        SeasonEntity::class,
        EpisodeEntity::class,
        MediaFileEntity::class,
        MediaLocationEntity::class,
        MediaRemoteSourceEntity::class,
        WatchHistoryEntity::class,
        DownloadTaskEntity::class,
        DownloadChunkEntity::class,
        FavoriteEntity::class,
        ScanSessionEntity::class
    ],
    version = 12,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun nasDao(): NasDao
    abstract fun mediaDao(): MediaDao
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun downloadDao(): DownloadDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun scanSessionDao(): ScanSessionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "yuanbao_tv_database.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `favorites` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `seriesId` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_favorites_seriesId` ON `favorites` (`seriesId`)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE media_files ADD COLUMN localStorageType TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE download_tasks ADD COLUMN targetStorageType TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `media_locations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `mediaFileId` INTEGER NOT NULL, `uri` TEXT NOT NULL, `storageType` TEXT NOT NULL, `fileName` TEXT NOT NULL, `fileSize` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, FOREIGN KEY(`mediaFileId`) REFERENCES `media_files`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_media_locations_mediaFileId` ON `media_locations` (`mediaFileId`)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_media_locations_uri` ON `media_locations` (`uri`)")
                database.execSQL(
                    "INSERT OR IGNORE INTO media_locations (mediaFileId, uri, storageType, fileName, fileSize, updatedAt) SELECT id, localUri, localStorageType, fileName, fileSize, updatedAt FROM media_files WHERE localUri IS NOT NULL AND localUri != ''"
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE download_tasks ADD COLUMN sourceLastModifiedAt INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `download_chunks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `taskId` INTEGER NOT NULL, `chunkIndex` INTEGER NOT NULL, `startByte` INTEGER NOT NULL, `byteCount` INTEGER NOT NULL, `partName` TEXT NOT NULL, `sha256` TEXT NOT NULL, `status` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, FOREIGN KEY(`taskId`) REFERENCES `download_tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_download_chunks_taskId` ON `download_chunks` (`taskId`)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_download_chunks_taskId_chunkIndex` ON `download_chunks` (`taskId`, `chunkIndex`)")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `media_remote_sources` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `mediaFileId` INTEGER NOT NULL, `nasSourceId` INTEGER, `uri` TEXT NOT NULL, `fileName` TEXT NOT NULL, `fileSize` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, FOREIGN KEY(`mediaFileId`) REFERENCES `media_files`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`nasSourceId`) REFERENCES `nas_sources`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)"
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_media_remote_sources_mediaFileId` ON `media_remote_sources` (`mediaFileId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_media_remote_sources_nasSourceId` ON `media_remote_sources` (`nasSourceId`)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_media_remote_sources_mediaFileId_nasSourceId_uri` ON `media_remote_sources` (`mediaFileId`, `nasSourceId`, `uri`)")
                database.execSQL(
                    "INSERT OR IGNORE INTO media_remote_sources (mediaFileId, nasSourceId, uri, fileName, fileSize, updatedAt) SELECT id, nasSourceId, nasUri, fileName, fileSize, updatedAt FROM media_files WHERE nasSourceId IS NOT NULL AND nasUri != ''"
                )
                database.execSQL("ALTER TABLE download_tasks ADD COLUMN sourceNasId INTEGER NOT NULL DEFAULT 0")
                database.execSQL(
                    "UPDATE download_tasks SET sourceNasId = COALESCE((SELECT nasSourceId FROM media_remote_sources WHERE media_remote_sources.uri = download_tasks.sourceUri AND media_remote_sources.nasSourceId IS NOT NULL ORDER BY updatedAt DESC LIMIT 1), (SELECT nasSourceId FROM media_files WHERE media_files.episodeId = download_tasks.episodeId), 0)"
                )
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 远端来源已迁入独立表，清空旧单来源字段以避免扫描后回退到陈旧地址。
                database.execSQL(
                    "UPDATE media_files SET nasSourceId = NULL, nasUri = '' WHERE EXISTS (SELECT 1 FROM media_remote_sources WHERE media_remote_sources.mediaFileId = media_files.id)"
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE download_tasks ADD COLUMN assembledBytes INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE download_tasks ADD COLUMN errorCode TEXT NOT NULL DEFAULT ''")
            }
        }

        internal val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_download_tasks_sourceNasId` ON `download_tasks` (`sourceNasId`)")
            }
        }

        internal val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("DROP INDEX IF EXISTS `index_download_tasks_episodeId`")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_download_tasks_episodeId_targetUri` ON `download_tasks` (`episodeId`, `targetUri`)")
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `scan_sessions` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `sourceKey` TEXT NOT NULL, `workName` TEXT NOT NULL, `status` TEXT NOT NULL, `processedCount` INTEGER NOT NULL, `importedCount` INTEGER NOT NULL, `checkpoint` TEXT NOT NULL, `errorMessage` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_scan_sessions_kind_sourceKey` ON `scan_sessions` (`kind`, `sourceKey`)")
            }
        }
    }
}
