package com.recordcollection.app.data.download

import android.content.Context
import android.os.StatFs
import com.recordcollection.app.data.db.entity.SongEntity

object StorageUtils {

    /** Estimates total bytes needed for the given songs at their stored bit rate (320 kbps fallback). */
    fun estimatedAlbumBytes(songs: List<SongEntity>): Long =
        songs.sumOf { song ->
            val kbps = if ((song.bitRate ?: 0) > 0) song.bitRate!!.toLong() else 320L
            song.duration.toLong() * kbps * 1000L / 8L
        }

    /** Returns true if the device has at least [requiredBytes] + 50 MB of free space. */
    fun hasEnoughStorage(context: Context, requiredBytes: Long): Boolean {
        val stat = StatFs(context.filesDir.path)
        return stat.availableBytes >= requiredBytes + 50L * 1024L * 1024L
    }
}
