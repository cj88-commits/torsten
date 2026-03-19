package com.torsten.app.data.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import com.torsten.app.TorstenApp
import com.torsten.app.data.api.SubsonicApiClient
import com.torsten.app.data.datastore.ServerConfigStore
import kotlinx.coroutines.flow.first
import timber.log.Timber

/**
 * Prefetches 150px cover art thumbnails for all albums into Coil's disk cache.
 * Runs after a successful sync, WiFi-only.
 */
class CoverArtPrefetchWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val config = ServerConfigStore(applicationContext).serverConfig.first()
        if (!config.isConfigured) return Result.success()

        val apiClient = SubsonicApiClient(config)
        val db = (applicationContext as TorstenApp).database
        val albums = db.albumDao().getAll()
        val imageLoader = SingletonImageLoader.get(applicationContext)

        Timber.tag("[Download]").d("CoverArtPrefetch: prefetching %d album thumbnails", albums.size)
        var count = 0
        for (album in albums) {
            if (isStopped) break
            val coverArtId = album.coverArtId ?: continue
            val url = apiClient.getCoverArtUrl(coverArtId, 150) ?: continue
            // Use the same stable key scheme as coverArtImageRequest in AlbumCardItem.kt so
            // that offline lookups from the UI composables hit this prefetched entry.
            val stableKey = "cover_art_${coverArtId}_150"
            val request = ImageRequest.Builder(applicationContext)
                .data(url)
                .diskCacheKey(stableKey)
                .memoryCacheKey(stableKey)
                .size(150)
                .build()
            imageLoader.execute(request)
            count++
        }
        Timber.tag("[Download]").d("CoverArtPrefetch: completed %d/%d", count, albums.size)
        return Result.success()
    }
}
