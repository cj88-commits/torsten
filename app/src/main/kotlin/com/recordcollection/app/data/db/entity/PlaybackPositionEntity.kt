package com.recordcollection.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playback_positions")
data class PlaybackPositionEntity(
    @PrimaryKey val albumId: String,
    val songId: String,
    val positionMs: Long,
    val updatedAt: Long,
)
