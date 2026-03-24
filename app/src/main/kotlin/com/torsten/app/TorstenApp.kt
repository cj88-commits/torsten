package com.torsten.app

import android.app.Application
import com.torsten.app.data.db.AppDatabase
import com.torsten.app.data.datastore.DownloadConfigStore
import com.torsten.app.data.datastore.DownloadedPlaylistStore
import com.torsten.app.data.datastore.ImageCacheConfigStore
import com.torsten.app.data.datastore.PlaybackConfigStore
import com.torsten.app.data.datastore.ServerConfigStore
import com.torsten.app.data.download.DownloadRepository
import com.torsten.app.data.metabrainz.ListenBrainzClient
import com.torsten.app.data.metabrainz.MusicBrainzClient
import com.torsten.app.data.network.ConnectivityMonitor
import com.torsten.app.data.queue.QueueManager
import com.torsten.app.data.recommendation.ArtistTopTracksRepository
import com.torsten.app.data.repository.SyncRepository
import com.torsten.app.ui.initCoilImageLoader
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import timber.log.Timber

class TorstenApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    val connectivityMonitor: ConnectivityMonitor by lazy { ConnectivityMonitor(this) }

    val playbackConfigStore: PlaybackConfigStore by lazy { PlaybackConfigStore(this) }

    val downloadConfigStore: DownloadConfigStore by lazy { DownloadConfigStore(this) }

    val imageCacheConfigStore: ImageCacheConfigStore by lazy { ImageCacheConfigStore(this) }

    val downloadRepository: DownloadRepository by lazy {
        DownloadRepository(this, database, downloadConfigStore)
    }

    val downloadedPlaylistStore: DownloadedPlaylistStore by lazy { DownloadedPlaylistStore(this) }

    val queueManager: QueueManager by lazy { QueueManager() }

    val syncRepository: SyncRepository by lazy {
        SyncRepository(
            context = this,
            db = database,
            configStore = ServerConfigStore(this),
            connectivityMonitor = connectivityMonitor,
        )
    }

    val artistTopTracksRepository: ArtistTopTracksRepository by lazy {
        ArtistTopTracksRepository(
            serverConfigStore = ServerConfigStore(this),
            songDao = database.songDao(),
            albumDao = database.albumDao(),
            artistMbidCacheDao = database.artistMbidCacheDao(),
            musicBrainzClient = MusicBrainzClient(),
            listenBrainzClient = ListenBrainzClient(),
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
