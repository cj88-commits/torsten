package com.torsten.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: String,
    val albumId: String,
    val artistId: String,
    val title: String,
    val trackNumber: Int,
    val discNumber: Int = 1,
    val duration: Int,
    val bitRate: Int?,
    val suffix: String?,
    val contentType: String?,
    val starred: Boolean,
    val localFilePath: String?,
    val lastUpdated: Long,
    /** Track-level artist name (e.g. "Rita Ora ft. Avicii"). */
    val artistName: String = "",
    /** Album-level artist name (e.g. "Avicii"). Used to attribute compilation tracks correctly. */
    val albumArtistName: String = "",
)
