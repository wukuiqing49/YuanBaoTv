package com.wkq.bao.core.database.dao

import androidx.room.*
import com.wkq.bao.core.database.entity.EpisodeEntity
import com.wkq.bao.core.database.entity.EpisodeWithSource
import com.wkq.bao.core.database.entity.MediaFileEntity
import com.wkq.bao.core.database.entity.MediaSeriesEntity
import com.wkq.bao.core.database.entity.SeasonEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {
    @Query("SELECT * FROM media_series ORDER BY updatedAt DESC")
    fun getAllSeries(): Flow<List<MediaSeriesEntity>>

    @Query("SELECT * FROM media_series WHERE EXISTS (SELECT 1 FROM media_files WHERE media_files.seriesId = media_series.id AND localUri IS NOT NULL) ORDER BY updatedAt DESC")
    fun getDownloadedSeries(): Flow<List<MediaSeriesEntity>>

    @Query("SELECT * FROM media_series ORDER BY updatedAt DESC")
    suspend fun getAllSeriesSync(): List<MediaSeriesEntity>

    @Query("SELECT * FROM media_series WHERE type = :type ORDER BY updatedAt DESC")
    fun getSeriesByType(type: String): Flow<List<MediaSeriesEntity>>

    @Query("SELECT * FROM media_series WHERE id = :seriesId LIMIT 1")
    suspend fun getSeriesById(seriesId: Long): MediaSeriesEntity?

    @Query("SELECT * FROM media_series WHERE title = :title LIMIT 1")
    suspend fun getSeriesByTitle(title: String): MediaSeriesEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeries(series: MediaSeriesEntity): Long

    @Update
    suspend fun updateSeries(series: MediaSeriesEntity)

    @Delete
    suspend fun deleteSeries(series: MediaSeriesEntity)

    // === Season ===
    @Query("SELECT * FROM seasons WHERE seriesId = :seriesId ORDER BY seasonNumber ASC")
    fun getSeasonsBySeriesId(seriesId: Long): Flow<List<SeasonEntity>>

    @Query("SELECT * FROM seasons WHERE seriesId = :seriesId ORDER BY seasonNumber ASC")
    suspend fun getSeasonsSync(seriesId: Long): List<SeasonEntity>

    @Query("SELECT * FROM seasons WHERE seriesId = :seriesId AND seasonNumber = :seasonNumber LIMIT 1")
    suspend fun getSeason(seriesId: Long, seasonNumber: Int): SeasonEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeason(season: SeasonEntity): Long

    // === Episode ===
    @Query("SELECT * FROM episodes WHERE seriesId = :seriesId AND seasonId = :seasonId ORDER BY episodeNumber ASC")
    fun getEpisodes(seriesId: Long, seasonId: Long): Flow<List<EpisodeEntity>>

    @Query("""
        SELECT episodes.*, media_files.localUri, media_files.localStorageType, media_files.nasUri
        FROM episodes
        LEFT JOIN media_files ON media_files.episodeId = episodes.id
        WHERE episodes.seriesId = :seriesId AND episodes.seasonId = :seasonId
        ORDER BY episodes.episodeNumber ASC
    """)
    fun getEpisodesWithSource(seriesId: Long, seasonId: Long): Flow<List<EpisodeWithSource>>

    @Query("SELECT * FROM episodes WHERE seriesId = :seriesId AND seasonId = :seasonId ORDER BY episodeNumber ASC")
    suspend fun getEpisodesSync(seriesId: Long, seasonId: Long): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE id = :episodeId LIMIT 1")
    suspend fun getEpisodeById(episodeId: Long): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE seasonId = :seasonId AND episodeNumber = :episodeNumber LIMIT 1")
    suspend fun getEpisodeByNumber(seasonId: Long, episodeNumber: Int): EpisodeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpisode(episode: EpisodeEntity): Long

    // === MediaFile ===
    @Query("SELECT * FROM media_files WHERE episodeId = :episodeId")
    suspend fun getMediaFiles(episodeId: Long): List<MediaFileEntity>

    @Query("SELECT * FROM media_files WHERE episodeId = :episodeId LIMIT 1")
    suspend fun getMediaFileByEpisodeId(episodeId: Long): MediaFileEntity?

    @Query("SELECT * FROM media_files WHERE id = :fileId LIMIT 1")
    suspend fun getMediaFileById(fileId: Long): MediaFileEntity?

    @Query("SELECT * FROM media_files WHERE nasUri = :nasUri LIMIT 1")
    suspend fun getMediaFileByNasUri(nasUri: String): MediaFileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMediaFile(file: MediaFileEntity): Long

    @Update
    suspend fun updateMediaFile(file: MediaFileEntity)
}
