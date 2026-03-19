package com.torsten.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.torsten.app.data.db.entity.ArtistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ArtistDao {

    @Query("SELECT * FROM artists ORDER BY name ASC")
    fun observeAll(): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM artists WHERE id = :id")
    fun observeById(id: String): Flow<ArtistEntity?>

    @Query("SELECT * FROM artists ORDER BY name ASC")
    suspend fun getAll(): List<ArtistEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(artists: List<ArtistEntity>)

    @Query("UPDATE artists SET artistImageUrl = :url WHERE id = :id")
    suspend fun updateArtistImageUrl(id: String, url: String?)

    @Query("DELETE FROM artists")
    suspend fun deleteAll()
}
