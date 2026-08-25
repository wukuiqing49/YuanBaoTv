package com.wkq.bao.core.database.dao

import androidx.room.*
import com.wkq.bao.core.database.entity.EpisodeEntity
import com.wkq.bao.core.database.entity.EpisodeWithSource
import com.wkq.bao.core.database.entity.MediaFileEntity
import com.wkq.bao.core.database.entity.MediaLocationEntity
import com.wkq.bao.core.database.entity.MediaRemoteSourceEntity
import com.wkq.bao.core.database.entity.MediaSeriesEntity
import com.wkq.bao.core.database.entity.SeasonEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {
    /** 清理早期版本写入的通用图库图片，避免把无关图片误展示为真实影视海报。 */
    @Query("""
        UPDATE media_series
        SET posterUri = '', backdropUri = '', updatedAt = :updatedAt
        WHERE posterUri LIKE 'https://images.unsplash.com/%'
           OR backdropUri LIKE 'https://images.unsplash.com/%'
    """)
    suspend fun clearLegacyStockArtwork(updatedAt: Long)

    @Query("SELECT * FROM media_series ORDER BY updatedAt DESC")
    fun getAllSeries(): Flow<List<MediaSeriesEntity>>

    @Query("SELECT * FROM media_series WHERE EXISTS (SELECT 1 FROM media_files INNER JOIN media_locations ON media_locations.mediaFileId = media_files.id WHERE media_files.seriesId = media_series.id) ORDER BY updatedAt DESC")
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

    @Update
    suspend fun updateSeason(season: SeasonEntity)

    // === Episode ===
    @Query("SELECT * FROM episodes WHERE seriesId = :seriesId AND seasonId = :seasonId ORDER BY episodeNumber ASC")
    fun getEpisodes(seriesId: Long, seasonId: Long): Flow<List<EpisodeEntity>>

    @Query("""
        SELECT episodes.*, COALESCE((SELECT uri FROM media_locations WHERE mediaFileId = media_files.id ORDER BY updatedAt DESC LIMIT 1), media_files.localUri) AS localUri, COALESCE((SELECT storageType FROM media_locations WHERE mediaFileId = media_files.id ORDER BY updatedAt DESC LIMIT 1), media_files.localStorageType) AS localStorageType, COALESCE((SELECT uri FROM media_remote_sources WHERE mediaFileId = media_files.id ORDER BY updatedAt DESC LIMIT 1), media_files.nasUri) AS nasUri, media_series.backdropUri AS seriesBackdropUri
        FROM episodes
        LEFT JOIN media_files ON media_files.episodeId = episodes.id
        INNER JOIN media_series ON media_series.id = episodes.seriesId
        WHERE episodes.seriesId = :seriesId AND episodes.seasonId = :seasonId
        ORDER BY episodes.episodeNumber ASC
    """)
    fun getEpisodesWithSource(seriesId: Long, seasonId: Long): Flow<List<EpisodeWithSource>>

    @Query("SELECT * FROM episodes WHERE seriesId = :seriesId AND seasonId = :seasonId ORDER BY episodeNumber ASC")
    suspend fun getEpisodesSync(seriesId: Long, seasonId: Long): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE seriesId = :seriesId ORDER BY seasonId ASC, episodeNumber ASC")
    suspend fun getEpisodesForSeriesSync(seriesId: Long): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE seriesId = :seriesId ORDER BY seasonId ASC, episodeNumber ASC LIMIT 1")
    suspend fun getFirstEpisode(seriesId: Long): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE id = :episodeId LIMIT 1")
    suspend fun getEpisodeById(episodeId: Long): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE seasonId = :seasonId AND episodeNumber = :episodeNumber LIMIT 1")
    suspend fun getEpisodeByNumber(seasonId: Long, episodeNumber: Int): EpisodeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpisode(episode: EpisodeEntity): Long

    @Update
    suspend fun updateEpisode(episode: EpisodeEntity)

    // === MediaFile ===
    @Query("SELECT * FROM media_files WHERE episodeId = :episodeId")
    suspend fun getMediaFiles(episodeId: Long): List<MediaFileEntity>

    @Query("SELECT * FROM media_files WHERE episodeId = :episodeId LIMIT 1")
    suspend fun getMediaFileByEpisodeId(episodeId: Long): MediaFileEntity?

    @Query("SELECT * FROM media_files WHERE id = :fileId LIMIT 1")
    suspend fun getMediaFileById(fileId: Long): MediaFileEntity?

    @Query("SELECT * FROM media_files WHERE nasUri = :nasUri LIMIT 1")
    suspend fun getMediaFileByNasUri(nasUri: String): MediaFileEntity?

    @Query("SELECT * FROM media_locations WHERE mediaFileId = :mediaFileId ORDER BY updatedAt DESC")
    suspend fun getMediaLocations(mediaFileId: Long): List<MediaLocationEntity>

    @Query("SELECT * FROM media_remote_sources WHERE mediaFileId = :mediaFileId ORDER BY updatedAt DESC, id DESC")
    suspend fun getMediaRemoteSources(mediaFileId: Long): List<MediaRemoteSourceEntity>

    @Query("SELECT * FROM media_remote_sources WHERE mediaFileId = :mediaFileId AND nasSourceId = :nasSourceId AND uri = :uri LIMIT 1")
    suspend fun getMediaRemoteSource(mediaFileId: Long, nasSourceId: Long, uri: String): MediaRemoteSourceEntity?

    /** 一次完整扫描后删除该 NAS 中已不存在的路径，不影响本地副本。 */
    @Query("DELETE FROM media_remote_sources WHERE nasSourceId = :nasSourceId AND updatedAt < :scanStartedAt")
    suspend fun deleteMediaRemoteSourcesNotSeenSince(nasSourceId: Long, scanStartedAt: Long): Int

    @Query("DELETE FROM media_remote_sources WHERE nasSourceId = :nasSourceId")
    suspend fun deleteMediaRemoteSourcesByNasSourceId(nasSourceId: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM media_locations WHERE mediaFileId = :mediaFileId)")
    suspend fun hasMediaLocations(mediaFileId: Long): Boolean

    @Query("SELECT * FROM media_locations WHERE uri = :uri LIMIT 1")
    suspend fun getMediaLocationByUri(uri: String): MediaLocationEntity?

    /** 完整扫描后只清理当前 SAF tree 中未再次发现的副本，不影响其他本地或外接设备。 */
    @Query("DELETE FROM media_locations WHERE substr(uri, 1, length(:treeUri) + 10) = :treeUri || '/document/' AND updatedAt < :scanStartedAt")
    suspend fun deleteMediaLocationsNotSeenInTree(treeUri: String, scanStartedAt: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMediaFile(file: MediaFileEntity): Long

    @Update
    suspend fun updateMediaFile(file: MediaFileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMediaLocation(location: MediaLocationEntity): Long

    @Update
    suspend fun updateMediaLocation(location: MediaLocationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMediaRemoteSource(source: MediaRemoteSourceEntity): Long

    @Update
    suspend fun updateMediaRemoteSource(source: MediaRemoteSourceEntity)
}
