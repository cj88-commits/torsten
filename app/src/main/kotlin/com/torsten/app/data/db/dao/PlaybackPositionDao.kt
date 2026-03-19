package com.torsten.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.torsten.app.data.db.entity.PlaybackPositionEntity

@Dao
interface PlaybackPositionDao {

    @Query("SELECT * FROM playback_positions WHERE albumId = :albumId")
    suspend fun getForAlbum(albumId: String): PlaybackPositionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(position: PlaybackPositionEntity)

    @Query("SELECT * FROM playback_positions ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getMostRecent(): PlaybackPositionEntity?

    @Query("DELETE FROM playback_positions WHERE albumId = :albumId")
    suspend fun deleteForAlbum(albumId: String)
}
