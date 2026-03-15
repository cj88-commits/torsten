package com.recordcollection.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stores star/unstar operations that couldn't be synced to the server (e.g. offline).
 * Processed the next time the album detail screen opens with connectivity.
 */
@Entity(tableName = "pending_stars")
data class PendingStarEntity(
    @PrimaryKey val targetId: String,   // songId or albumId
    val targetType: String,             // "song" or "album"
    val starred: Boolean,
    val createdAt: Long,
)
