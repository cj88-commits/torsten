package com.torsten.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.torsten.app.data.db.entity.ArtistMbidCacheEntity

@Dao
interface ArtistMbidCacheDao {

    @Query("SELECT * FROM artist_mbid_cache WHERE artistId = :artistId")
    suspend fun getByArtistId(artistId: String): ArtistMbidCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ArtistMbidCacheEntity)
}
