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
)
