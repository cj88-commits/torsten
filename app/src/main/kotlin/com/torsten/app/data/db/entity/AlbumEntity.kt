package com.torsten.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey val id: String,
    val artistId: String,
    val artistName: String,
    val title: String,
    val year: Int?,
    val genre: String?,
    val songCount: Int,
    val duration: Int,
    val coverArtId: String?,
    val starred: Boolean,
    val downloadState: DownloadState,
    val downloadProgress: Int,
    val downloadedAt: Long?,
    val lastUpdated: Long,
)
