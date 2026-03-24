package com.torsten.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.torsten.app.data.db.entity.ArtistTopTracksCacheEntity

@Dao
interface ArtistTopTracksCacheDao {

    @Query("SELECT * FROM artist_top_tracks_cache WHERE artistId = :artistId")
    suspend fun getByArtistId(artistId: String): ArtistTopTracksCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ArtistTopTracksCacheEntity)
}
