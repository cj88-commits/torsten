package com.recordcollection.app

import android.app.Application
import com.recordcollection.app.data.db.AppDatabase
import com.recordcollection.app.data.datastore.DownloadConfigStore
import com.recordcollection.app.data.datastore.ImageCacheConfigStore
import com.recordcollection.app.data.datastore.PlaybackConfigStore
import com.recordcollection.app.data.datastore.ServerConfigStore
import com.recordcollection.app.data.download.DownloadRepository
import com.recordcollection.app.data.network.ConnectivityMonitor
import com.recordcollection.app.data.repository.SyncRepository
import com.recordcollection.app.ui.initCoilImageLoader
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import timber.log.Timber

class RecordCollectionApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    val connectivityMonitor: ConnectivityMonitor by lazy { ConnectivityMonitor(this) }

    val playbackConfigStore: PlaybackConfigStore by lazy { PlaybackConfigStore(this) }

    val downloadConfigStore: DownloadConfigStore by lazy { DownloadConfigStore(this) }

    val imageCacheConfigStore: ImageCacheConfigStore by lazy { ImageCacheConfigStore(this) }

    val downloadRepository: DownloadRepository by lazy {
        DownloadRepository(this, database, downloadConfigStore)
    }

    val syncRepository: SyncRepository by lazy {
        SyncRepository(
            context = this,
            db = database,
            configStore = ServerConfigStore(this),
            connectivityMonitor = connectivityMonitor,
        )
    }

    override fun onCreate() {
        super.onCreate()
        setupTimber()
        val cacheSizeMb = runBlocking { imageCacheConfigStore.cacheSizeLimitMb.first() }
        initCoilImageLoader(this, cacheSizeMb)
    }

    private fun setupTimber() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}

/*
 * Logging conventions — use Timber.tag("[SUBSYSTEM]").d(...) at call sites:
 *
 *   [API]            – Retrofit/OkHttp calls (path only, NEVER full URL with auth params)
 *   [Sync]           – Background synchronisation and connectivity events
 *   [Player]         – Playback state, control events, and MediaSession
 *   [Download]       – File download progress and errors
 *   [DB]             – Room / DataStore reads and writes, settings changes
 *   [Stars]          – Starring/unstarring songs and albums
 *   [Scrobble]       – Scrobble submissions and now-playing notifications
 *   [Resume]         – Saved playback position save/restore
 *   [RecentlyPlayed] – Recently-played album tracking
 *   [UI]             – UI-level operational errors (e.g. scroll failures)
 *
 * NEVER log passwords, tokens, salts, or any auth-related query parameters.
 */
