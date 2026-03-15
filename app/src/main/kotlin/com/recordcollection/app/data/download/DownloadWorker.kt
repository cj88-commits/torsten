package com.recordcollection.app.data.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.recordcollection.app.RecordCollectionApp
import com.recordcollection.app.data.api.SubsonicApiClient
import com.recordcollection.app.data.api.auth.TrustAllCerts
import com.recordcollection.app.data.datastore.DownloadConfigStore
import com.recordcollection.app.data.datastore.ServerConfigStore
import com.recordcollection.app.data.db.entity.DownloadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.net.URL
import java.util.concurrent.TimeUnit

class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val albumId = inputData.getString(KEY_ALBUM_ID) ?: return Result.failure()
        val app = applicationContext as RecordCollectionApp
        val db = app.database

        val config = withContext(Dispatchers.IO) {
            ServerConfigStore(app).serverConfig.first()
        }
        if (!config.isConfigured) {
            Timber.tag("[Download]").w("Server not configured — aborting download for album %s", albumId)
            return Result.failure()
        }

        val downloadConfigStore = DownloadConfigStore(app)
        val downloadFormat = withContext(Dispatchers.IO) { downloadConfigStore.downloadFormat.first() }
        val downloadMaxBitRate = withContext(Dispatchers.IO) { downloadConfigStore.downloadMaxBitRate.first() }

        val album = withContext(Dispatchers.IO) { db.albumDao().getById(albumId) }
        if (album == null) {
            Timber.tag("[Download]").w("Album %s not found — aborting", albumId)
            return Result.failure()
        }

        val songs = withContext(Dispatchers.IO) { db.songDao().getByAlbum(albumId) }
        if (songs.isEmpty()) {
            Timber.tag("[Download]").w("No songs found for album %s — aborting", albumId)
            return Result.failure()
        }

        // Check available storage before starting
        val estimated = StorageUtils.estimatedAlbumBytes(songs)
        if (!StorageUtils.hasEnoughStorage(applicationContext, estimated)) {
            Timber.tag("[Download]").w("Insufficient storage for album %s (~%d bytes needed)", albumId, estimated)
            withContext(Dispatchers.IO) {
                db.albumDao().updateDownloadState(albumId, DownloadState.FAILED, 0)
            }
            return Result.failure(workDataOf(KEY_ERROR to ERROR_STORAGE))
        }

        val okHttpClient = buildDownloadClient(config.serverUrl)
        val apiClient = SubsonicApiClient(config)
        val downloadDir = File(applicationContext.filesDir, "downloads/$albumId").also { it.mkdirs() }

        // Split into already-done and still-needed tracks
        val (alreadyDone, toDownload) = withContext(Dispatchers.IO) {
            songs.partition { song ->
                !song.localFilePath.isNullOrEmpty() && File(song.localFilePath).exists()
            }
        }
        val alreadyCount = alreadyDone.size

        // Start progress from wherever we left off, not from 0
        val initialProgress = if (songs.isNotEmpty()) alreadyCount * 100 / songs.size else 0
        withContext(Dispatchers.IO) {
            db.albumDao().updateDownloadState(albumId, DownloadState.DOWNLOADING, initialProgress)
        }

        if (toDownload.isEmpty()) {
            Timber.tag("[Download]").d("All tracks already present for album %s", albumId)
            withContext(Dispatchers.IO) {
                db.albumDao().updateDownloadComplete(albumId, DownloadState.COMPLETE, 100, System.currentTimeMillis())
            }
            return Result.success()
        }

        var successCount = 0
        var failCount = 0

        for ((index, song) in toDownload.withIndex()) {
            if (isStopped) {
                Timber.tag("[Download]").d("Worker stopped at track %d/%d for album %s", index, toDownload.size, albumId)
                break
            }

            try {
                val streamUrl = apiClient.streamUrl(song.id, format = downloadFormat, maxBitRate = downloadMaxBitRate)
                val ext = song.suffix?.takeIf { it.isNotEmpty() } ?: "mp3"
                val destFile = File(downloadDir, "${song.id}.$ext")
                val tempFile = File(downloadDir, "${song.id}.tmp")

                val request = Request.Builder().url(streamUrl).build()
                val response = withContext(Dispatchers.IO) { okHttpClient.newCall(request).execute() }

                if (!response.isSuccessful) {
                    Timber.tag("[Download]").e("HTTP %d downloading song %s", response.code, song.id)
                    response.close()
                    failCount++
                    continue
                }

                withContext(Dispatchers.IO) {
                    response.body!!.use { body ->
                        tempFile.outputStream().use { out ->
                            body.byteStream().copyTo(out)
                        }
                    }
                    tempFile.renameTo(destFile)
                    db.songDao().updateLocalFilePath(song.id, destFile.absolutePath)
                }

                successCount++
                Timber.tag("[Download]").d("Track %d/%d done: %s", index + 1, toDownload.size, song.title)

                // Progress is fraction of ALL songs, not just the remaining batch
                val progress = (alreadyCount + successCount) * 100 / songs.size
                withContext(Dispatchers.IO) {
                    db.albumDao().updateDownloadState(albumId, DownloadState.DOWNLOADING, progress)
                }

            } catch (e: Exception) {
                Timber.tag("[Download]").e(e, "Failed to download song %s", song.id)
                failCount++
            }
        }

        val finalState = when {
            isStopped -> if (successCount > 0) DownloadState.PARTIAL else DownloadState.NONE
            failCount == 0 -> DownloadState.COMPLETE
            successCount == 0 -> DownloadState.FAILED
            else -> DownloadState.PARTIAL
        }

        withContext(Dispatchers.IO) {
            if (finalState == DownloadState.COMPLETE) {
                db.albumDao().updateDownloadComplete(albumId, finalState, 100, System.currentTimeMillis())
            } else {
                val progress = (alreadyCount + successCount) * 100 / songs.size
                db.albumDao().updateDownloadState(albumId, finalState, progress)
            }
        }

        Timber.tag("[Download]").d(
            "Album %s finished: state=%s success=%d fail=%d",
            albumId, finalState, successCount, failCount,
        )

        return if (failCount == 0 && !isStopped) Result.success() else Result.failure()
    }

    private fun buildDownloadClient(serverUrl: String): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
        val host = runCatching { URL(serverUrl).host }.getOrNull()
        if (host != null && TrustAllCerts.isPrivateHost(host)) {
            TrustAllCerts.applyTrustAll(builder)
        }
        return builder.build()
    }

    companion object {
        const val KEY_ALBUM_ID = "albumId"
        const val KEY_ERROR = "error"
        const val ERROR_STORAGE = "insufficient_storage"
    }
}
