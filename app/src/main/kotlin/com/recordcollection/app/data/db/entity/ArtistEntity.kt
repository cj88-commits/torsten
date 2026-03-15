package com.recordcollection.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "artists")
data class ArtistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val albumCount: Int,
    val starred: Boolean,
    val lastUpdated: Long,
    /** Null = not yet fetched. Empty string = fetched but no image available. Otherwise the URL. */
    val artistImageUrl: String? = null,
)
