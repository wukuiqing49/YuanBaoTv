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
import com.wkq.bao.core.database.dao.WatchHistoryDao
import com.wkq.bao.core.database.entity.DownloadTaskEntity
import com.wkq.bao.core.database.entity.EpisodeEntity
import com.wkq.bao.core.database.entity.FavoriteEntity
import com.wkq.bao.core.database.entity.MediaFileEntity
import com.wkq.bao.core.database.entity.MediaSeriesEntity
import com.wkq.bao.core.database.entity.NasSourceEntity
import com.wkq.bao.core.database.entity.SeasonEntity
import com.wkq.bao.core.database.entity.WatchHistoryEntity

@Database(
    entities = [
        NasSourceEntity::class,
        MediaSeriesEntity::class,
        SeasonEntity::class,
        EpisodeEntity::class,
        MediaFileEntity::class,
        WatchHistoryEntity::class,
        DownloadTaskEntity::class,
        FavoriteEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun nasDao(): NasDao
    abstract fun mediaDao(): MediaDao
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun downloadDao(): DownloadDao
    abstract fun favoriteDao(): FavoriteDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "yuanbao_tv_database.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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
    }
}
