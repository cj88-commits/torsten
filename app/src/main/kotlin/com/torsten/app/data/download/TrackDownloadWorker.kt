package com.torsten.app.data.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.torsten.app.TorstenApp
import com.torsten.app.data.api.SubsonicApiClient
import com.torsten.app.data.api.auth.TrustAllCerts
import com.torsten.app.data.datastore.DownloadConfigStore
import com.torsten.app.data.datastore.ServerConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.net.URL
import java.util.concurrent.TimeUnit

class TrackDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val songId = inputData.getString(KEY_SONG_ID) ?: return Result.failure()
        val app = applicationContext as TorstenApp
        val db = app.database

        val config = withContext(Dispatchers.IO) {
            ServerConfigStore(app).serverConfig.first()
        }
        if (!config.isConfigured) return Result.failure()

        val song = withContext(Dispatchers.IO) { db.songDao().getById(songId) }
        if (song == null) {
            Timber.tag("[Download]").w("TrackDownloadWorker: song %s not in DB — aborting", songId)
            return Result.failure()
        }

        // Already downloaded
        if (!song.localFilePath.isNullOrEmpty() && File(song.localFilePath).exists()) {
            return Result.success()
        }

        val downloadConfigStore = DownloadConfigStore(app)
        val downloadFormat = withContext(Dispatchers.IO) { downloadConfigStore.downloadFormat.first() }
        val downloadMaxBitRate = withContext(Dispatchers.IO) { downloadConfigStore.downloadMaxBitRate.first() }

        val effectiveAlbumId = song.albumId.ifEmpty { "playlist_tracks" }
        val downloadDir = File(applicationContext.filesDir, "downloads/$effectiveAlbumId").also { it.mkdirs() }
        val ext = song.suffix?.takeIf { it.isNotEmpty() } ?: "mp3"
        val destFile = File(downloadDir, "${song.id}.$ext")
        val tempFile = File(downloadDir, "${song.id}.tmp")

        return try {
            val apiClient = SubsonicApiClient(config)
            val streamUrl = apiClient.streamUrl(song.id, format = downloadFormat, maxBitRate = downloadMaxBitRate)
            val okHttpClient = buildDownloadClient(config.serverUrl)
            val request = Request.Builder().url(streamUrl).build()
            val response = withContext(Dispatchers.IO) { okHttpClient.newCall(request).execute() }

            if (!response.isSuccessful) {
                Timber.tag("[Download]").e("TrackDownloadWorker: HTTP %d for song %s", response.code, songId)
                response.close()
                return Result.failure()
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

            Timber.tag("[Download]").d("TrackDownloadWorker: downloaded %s", song.title)
            Result.success()
        } catch (e: Exception) {
            Timber.tag("[Download]").e(e, "TrackDownloadWorker: failed for song %s", songId)
            Result.failure()
        }
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
        const val KEY_SONG_ID = "songId"
    }
}
