package com.wkq.bao.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.wkq.bao.core.database.dao.DownloadDao
import com.wkq.bao.core.database.dao.MediaDao
import com.wkq.bao.core.database.dao.NasDao
import com.wkq.bao.core.database.dao.WatchHistoryDao
import com.wkq.bao.core.database.entity.DownloadTaskEntity
import com.wkq.bao.core.database.entity.EpisodeEntity
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
        DownloadTaskEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun nasDao(): NasDao
    abstract fun mediaDao(): MediaDao
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "yuanbao_tv_database.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
