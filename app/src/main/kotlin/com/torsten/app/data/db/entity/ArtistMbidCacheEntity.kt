package com.torsten.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

private const val CACHE_VALID_MS = 180L * 24 * 60 * 60 * 1_000L // 180 days

@Entity(tableName = "artist_mbid_cache")
data class ArtistMbidCacheEntity(
    @PrimaryKey val artistId: String,
    val mbid: String,
    val cachedAt: Long,
) {
    fun isValid(): Boolean = System.currentTimeMillis() - cachedAt < CACHE_VALID_MS
}
