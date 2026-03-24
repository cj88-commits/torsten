package com.torsten.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.torsten.app.data.db.entity.SongEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {

    @Query("SELECT * FROM songs WHERE id = :id")
    fun observeById(id: String): Flow<SongEntity?>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getById(id: String): SongEntity?

    @Query("SELECT * FROM songs WHERE albumId = :albumId ORDER BY discNumber ASC, trackNumber ASC")
    fun observeByAlbum(albumId: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE albumId = :albumId ORDER BY discNumber ASC, trackNumber ASC")
    suspend fun getByAlbum(albumId: String): List<SongEntity>

    @Query("SELECT * FROM songs WHERE artistId = :artistId")
    suspend fun getByArtistId(artistId: String): List<SongEntity>

    @Query("""
        SELECT s.* FROM songs s
        INNER JOIN albums a ON s.albumId = a.id
        WHERE lower(a.artistName) = lower(:artistName)
    """)
    suspend fun getSongsByArtistName(artistName: String): List<SongEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(songs: List<SongEntity>)

    @Query("DELETE FROM songs WHERE albumId = :albumId")
    suspend fun deleteByAlbum(albumId: String)

    @Query("UPDATE songs SET localFilePath = :path WHERE id = :songId")
    suspend fun updateLocalFilePath(songId: String, path: String)

    @Query("UPDATE songs SET localFilePath = NULL WHERE albumId = :albumId")
    suspend fun clearLocalFilePathsForAlbum(albumId: String)

    @Query("UPDATE songs SET localFilePath = NULL WHERE id = :songId")
    suspend fun clearLocalFilePath(songId: String)

    @Query("UPDATE songs SET starred = :starred WHERE id = :songId")
    suspend fun updateStarred(songId: String, starred: Boolean)

    @Query("SELECT starred FROM songs WHERE id = :songId")
    fun observeStarredById(songId: String): Flow<Boolean>

    @Query("UPDATE songs SET localFilePath = NULL")
    suspend fun clearAllLocalFilePaths()
}
