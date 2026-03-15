package com.recordcollection.app.data.datastore

/**
 * Per-network streaming quality settings persisted in DataStore.
 *
 * Format values accepted by the Subsonic `stream` endpoint:
 *   "raw"  – no transcoding, serve original file
 *   "mp3"  – transcode to MP3
 *   "opus" – transcode to Opus (lowest bandwidth, good quality)
 *
 * A [maxBitRate] of 0 means "original quality / no limit".
 */
data class StreamingConfig(
    val wifiFormat: String = "raw",
    val wifiMaxBitRate: Int = 0,
    val mobileFormat: String = "opus",
    val mobileMaxBitRate: Int = 96,
)
