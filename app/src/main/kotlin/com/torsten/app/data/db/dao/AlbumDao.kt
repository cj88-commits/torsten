package com.torsten.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.torsten.app.data.db.entity.AlbumEntity
import com.torsten.app.data.db.entity.DownloadState
import kotlinx.coroutines.flow.Flow

/** Projection used to map one cover-art ID per artist from the albums table. */
data class ArtistCoverArtRow(val artistId: String, val coverArtId: String?)

@Dao
interface AlbumDao {

    /** One cover-art ID per artist — MAX() picks any non-null value if one exists. */
    @Query("SELECT artistId, MAX(coverArtId) AS coverArtId FROM albums GROUP BY artistId")
    fun observeArtistCoverArts(): Flow<List<ArtistCoverArtRow>>

    @Query("SELECT * FROM albums ORDER BY artistName ASC, title ASC")
    fun observeAll(): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE artistId = :artistId ORDER BY year ASC, title ASC")
    fun observeByArtist(artistId: String): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE id = :id")
    fun observeById(id: String): Flow<AlbumEntity?>

    @Query("SELECT * FROM albums")
    suspend fun getAll(): List<AlbumEntity>

    @Query("SELECT * FROM albums WHERE id = :id")
    suspend fun getById(id: String): AlbumEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(albums: List<AlbumEntity>)

    @Query("DELETE FROM albums")
    suspend fun deleteAll()

    @Query("UPDATE albums SET downloadState = :state, downloadProgress = :progress WHERE id = :albumId")
    suspend fun updateDownloadState(albumId: String, state: DownloadState, progress: Int)

    @Query("UPDATE albums SET downloadState = :state, downloadProgress = :progress, downloadedAt = :downloadedAt WHERE id = :albumId")
    suspend fun updateDownloadComplete(albumId: String, state: DownloadState, progress: Int, downloadedAt: Long)

    @Query("UPDATE albums SET starred = :starred WHERE id = :albumId")
    suspend fun updateStarred(albumId: String, starred: Boolean)

    @Query("UPDATE albums SET downloadState = 'NONE', downloadProgress = 0, downloadedAt = NULL")
    suspend fun resetAllDownloadStates()
}
