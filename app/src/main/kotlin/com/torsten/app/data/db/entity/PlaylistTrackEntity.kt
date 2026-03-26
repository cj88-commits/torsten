package com.torsten.app.data.db.entity

import androidx.room.Entity

@Entity(tableName = "playlist_tracks", primaryKeys = ["playlistId", "songId"])
data class PlaylistTrackEntity(
    val playlistId: String,
    val songId: String,
    val trackOrder: Int,
    val cachedAt: Long = System.currentTimeMillis(),
)
