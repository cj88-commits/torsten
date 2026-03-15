package com.recordcollection.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recently_played")
data class RecentlyPlayedEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val albumId: String,
    val playedAt: Long,
)
