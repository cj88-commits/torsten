package com.torsten.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.torsten.app.data.db.entity.PlaylistTrackEntity
import com.torsten.app.data.db.entity.SongEntity

@Dao
interface PlaylistTrackDao {

    /**
     * Returns the cached songs for a playlist in track order, joined from the songs table.
     * Songs not present in the local library (no matching row in songs) are excluded.
     */
    @Query("""
        SELECT s.* FROM songs s
        INNER JOIN playlist_tracks pt ON s.id = pt.songId
        WHERE pt.playlistId = :playlistId
        ORDER BY pt.trackOrder
    """)
    suspend fun getTracksForPlaylist(playlistId: String): List<SongEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTracks(tracks: List<PlaylistTrackEntity>)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun deleteTracksForPlaylist(playlistId: String)
}
