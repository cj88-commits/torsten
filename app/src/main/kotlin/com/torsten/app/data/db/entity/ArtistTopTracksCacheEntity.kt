package com.torsten.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

private const val TOP_TRACKS_CACHE_VALID_MS = 24L * 60 * 60 * 1_000L // 24 hours

@Entity(tableName = "artist_top_tracks_cache")
data class ArtistTopTracksCacheEntity(
    @PrimaryKey val artistId: String,
    val trackIds: String, // comma-separated song IDs in ranked order
    val cachedAt: Long,
) {
    fun isValid(): Boolean = System.currentTimeMillis() - cachedAt < TOP_TRACKS_CACHE_VALID_MS
}
