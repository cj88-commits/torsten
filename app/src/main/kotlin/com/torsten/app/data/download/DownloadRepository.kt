package com.torsten.app.data.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.torsten.app.data.datastore.DownloadConfigStore
import com.torsten.app.data.db.AppDatabase
import com.torsten.app.data.db.entity.DownloadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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

    private fun workName(albumId: String) = "download_album_$albumId"

    companion object {
        private const val DOWNLOAD_TAG = "download_album"
    }
}
