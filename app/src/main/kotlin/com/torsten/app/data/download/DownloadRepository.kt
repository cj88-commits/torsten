package com.torsten.app.data.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.torsten.app.data.api.dto.SongDto
import com.torsten.app.data.datastore.DownloadConfigStore
import com.torsten.app.data.db.AppDatabase
import com.torsten.app.data.db.entity.DownloadState
import com.torsten.app.data.db.entity.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

class DownloadRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val downloadConfigStore: DownloadConfigStore,
) {
    private val workManager = WorkManager.getInstance(context)

    /** Marks the album as QUEUED in Room, then enqueues a WorkManager job.
     *  Network constraint is UNMETERED (WiFi-only) or CONNECTED based on the download setting. */
    suspend fun enqueueDownload(albumId: String) {
        withContext(Dispatchers.IO) {
            db.albumDao().updateDownloadState(albumId, DownloadState.QUEUED, 0)
        }

        val wifiOnly = downloadConfigStore.wifiOnly.first()
        val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .build()

        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .addTag(DOWNLOAD_TAG)
            .setConstraints(constraints)
            .setInputData(workDataOf(DownloadWorker.KEY_ALBUM_ID to albumId))
            .build()

        workManager.enqueueUniqueWork(workName(albumId), ExistingWorkPolicy.KEEP, request)
        Timber.tag("[Download]").d("Enqueued download for album %s (wifiOnly=%b)", albumId, wifiOnly)
    }

    /** Cancels any active/pending download and resets album state to NONE. */
    suspend fun cancelDownload(albumId: String) {
        workManager.cancelUniqueWork(workName(albumId))
        withContext(Dispatchers.IO) {
            db.albumDao().updateDownloadState(albumId, DownloadState.NONE, 0)
            db.songDao().clearLocalFilePathsForAlbum(albumId)
        }
        File(context.filesDir, "downloads/$albumId").deleteRecursively()
        Timber.tag("[Download]").d("Cancelled download for album %s", albumId)
    }

    /** Cancels all downloads, resets all album download states, and deletes all local files. */
    suspend fun clearAllDownloads() {
        workManager.cancelAllWorkByTag(DOWNLOAD_TAG)
        withContext(Dispatchers.IO) {
            db.albumDao().resetAllDownloadStates()
            db.songDao().clearAllLocalFilePaths()
        }
        File(context.filesDir, "downloads").deleteRecursively()
        Timber.tag("[Download]").i("All downloads cleared")
    }

    fun getDownloadState(albumId: String): Flow<DownloadState> =
        db.albumDao().observeById(albumId).map { it?.downloadState ?: DownloadState.NONE }

    /** Upserts the song to the local DB (if not already present) and enqueues a per-track
     *  WorkManager job. Pass [playlistId] so the worker can be cancelled by playlist. */
    suspend fun downloadTrack(song: SongDto, playlistId: String) {
        val wifiOnly = downloadConfigStore.wifiOnly.first()
        val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED

        val shouldDownload = withContext(Dispatchers.IO) {
            val existing = db.songDao().getById(song.id)
            when {
                existing == null -> {
                    db.songDao().upsertAll(
                        listOf(
                            SongEntity(
                                id = song.id,
                                albumId = song.albumId.orEmpty(),
                                artistId = song.artistId.orEmpty(),
                                title = song.title,
                                trackNumber = song.track ?: 0,
                                discNumber = song.discNumber ?: 1,
                                duration = song.duration ?: 0,
                                bitRate = song.bitRate,
                                suffix = song.suffix,
                                contentType = song.contentType,
                                starred = song.starred != null,
                                localFilePath = null,
                                lastUpdated = System.currentTimeMillis(),
                                artistName = song.artist.orEmpty(),
                                albumArtistName = song.albumArtist.orEmpty(),
                            ),
                        ),
                    )
                    true
                }
                !existing.localFilePath.isNullOrEmpty() && File(existing.localFilePath!!).exists() -> false
                else -> true // Song in DB but file missing — re-download
            }
        }

        if (!shouldDownload) return

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .build()

        val request = OneTimeWorkRequestBuilder<TrackDownloadWorker>()
            .addTag(TRACK_DOWNLOAD_TAG)
            .addTag(playlistTag(playlistId))
            .setConstraints(constraints)
            .setInputData(workDataOf(TrackDownloadWorker.KEY_SONG_ID to song.id))
            .build()

        workManager.enqueueUniqueWork(trackWorkName(song.id), ExistingWorkPolicy.KEEP, request)
        Timber.tag("[Download]").d("Enqueued track download: %s (playlist=%s)", song.title, playlistId)
    }

    /** Cancels all pending/active track downloads for a playlist. */
    fun cancelPlaylistTrackDownloads(playlistId: String) {
        workManager.cancelAllWorkByTag(playlistTag(playlistId))
        Timber.tag("[Download]").d("Cancelled track downloads for playlist %s", playlistId)
    }

    /** Deletes local files and clears localFilePath for the given song IDs. */
    suspend fun deleteTrackDownloads(songIds: List<String>) {
        withContext(Dispatchers.IO) {
            for (songId in songIds) {
                val song = db.songDao().getById(songId) ?: continue
                song.localFilePath?.let { path -> File(path).delete() }
                db.songDao().clearLocalFilePath(songId)
            }
        }
        Timber.tag("[Download]").d("Deleted track downloads for %d songs", songIds.size)
    }

    /** Emits a list of booleans (one per songId) indicating whether each song is downloaded. */
    fun observeSongDownloadStates(songIds: List<String>): Flow<List<Boolean>> {
        if (songIds.isEmpty()) return flowOf(emptyList())
        val flows = songIds.map { id ->
            db.songDao().observeById(id).map { song ->
                !song?.localFilePath.isNullOrEmpty() && File(song!!.localFilePath!!).exists()
            }
        }
        if (flows.size == 1) return flows[0].map { listOf(it) }
        @Suppress("UNCHECKED_CAST")
        return combine(*flows.toTypedArray()) { booleans -> booleans.toList() }
    }

    private fun workName(albumId: String) = "download_album_$albumId"
    private fun trackWorkName(songId: String) = "download_track_$songId"
    private fun playlistTag(playlistId: String) = "download_playlist_$playlistId"

    companion object {
        private const val DOWNLOAD_TAG = "download_album"
        private const val TRACK_DOWNLOAD_TAG = "download_track"
    }
}
