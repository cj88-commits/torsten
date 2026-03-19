package com.torsten.app.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import coil3.SingletonImageLoader
import com.torsten.app.TorstenApp
import com.torsten.app.data.api.SubsonicApiClient
import com.torsten.app.data.api.auth.SubsonicTokenAuth
import com.torsten.app.data.api.auth.SubsonicTokenAuth.asQueryString
import com.torsten.app.data.api.auth.TrustAllCerts
import com.torsten.app.data.datastore.PlaybackConfigStore
import com.torsten.app.data.datastore.ServerConfig
import com.torsten.app.data.datastore.ServerConfigStore
import com.torsten.app.data.db.AppDatabase
import com.torsten.app.data.db.entity.AlbumEntity
import com.torsten.app.data.db.entity.PlaybackPositionEntity
import com.torsten.app.data.db.entity.SongEntity
import com.torsten.app.data.queue.QueueTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var db: AppDatabase

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var replayGainEnabled = false
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var savePositionJob: Job? = null
    private var noisyReceiver: BroadcastReceiver? = null

    // ─── Scrobble state ───────────────────────────────────────────────────────
    private var scrobblingEnabled = false
    private var scrobbleApiClient: SubsonicApiClient? = null
    private val scrobbledIds = mutableSetOf<String>()  // cleared on app restart
    private var nowPlayingReportedId = ""              // tracks which song got a now-playing ping

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        val app = applicationContext as TorstenApp
        db = app.database

        // Read one-shot settings and initialise API client
        serviceScope.launch(Dispatchers.IO) {
            replayGainEnabled = app.playbackConfigStore.replayGainEnabled.first()
            val config = ServerConfigStore(app).serverConfig.first()
            if (config.isConfigured) {
                scrobbleApiClient = SubsonicApiClient(config)
            }
        }

        player = buildExoPlayer()
        player.addListener(playerListener)

        // Observe scrobbling preference reactively so toggling in Settings takes effect immediately.
        // Launched after player is created so the collect block can safely call player.isPlaying.
        serviceScope.launch {
            app.playbackConfigStore.scrobblingEnabled.collect { enabled ->
                val wasEnabled = scrobblingEnabled
                scrobblingEnabled = enabled
                // When scrobbling is turned on mid-session, send now-playing for the current track
                if (enabled && !wasEnabled && player.isPlaying) {
                    val songId = player.currentMediaItem?.mediaId?.takeIf { it.isNotEmpty() }
                    if (songId != null) {
                        nowPlayingReportedId = ""
                        reportNowPlaying(songId)
                    }
                }
            }
        }

        // Build a PendingIntent that brings the existing MainActivity to the front without
        // creating a new instance or clearing the back stack. FLAG_ACTIVITY_SINGLE_TOP works
        // in conjunction with android:launchMode="singleTop" in the manifest to ensure the
        // same activity instance is reused when the user taps the media notification.
        val sessionActivityIntent = packageManager
            .getLaunchIntentForPackage(packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) }
            ?.let { intent ->
                PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(PlaybackSessionCallback())
            .apply { sessionActivityIntent?.let { setSessionActivity(it) } }
            .build()

        // Restore the last playback position on startup (paused, not auto-playing)
        serviceScope.launch { restoreLastPlayback() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    override fun onDestroy() {
        unregisterNoisyReceiver()
        serviceScope.launch { savePosition() }
        mediaSession.release()
        player.removeListener(playerListener)
        player.release()
        loudnessEnhancer?.release()
        super.onDestroy()
    }

    // ─── ExoPlayer construction ───────────────────────────────────────────────

    private fun buildExoPlayer(): ExoPlayer {
        // Apply trust-all to the OkHttp client used for media streaming.
        // Private/Tailscale servers commonly use self-signed certificates.
        val okHttpBuilder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
        TrustAllCerts.applyTrustAll(okHttpBuilder)
        val okHttpClient = okHttpBuilder.build()

        // DefaultDataSource.Factory routes URIs by scheme:
        //   file://  → FileDataSource  (local downloaded tracks)
        //   https:// → OkHttpDataSource (streaming from the Subsonic server)
        //   content:// → ContentDataSource
        // Using OkHttpDataSource.Factory alone means OkHttp handles every URI, including
        // file:// ones — OkHttp cannot open file:// URIs and throws
        // HttpDataSourceException: Malformed URL for every local playback attempt.
        val httpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
        exoPlayer.shuffleModeEnabled = false

        return exoPlayer
    }

    // ─── Player listener ──────────────────────────────────────────────────────

    private val playerListener = object : Player.Listener {

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                registerNoisyReceiver()
                startSaveLoop()
                reportNowPlaying(player.currentMediaItem?.mediaId ?: "")
            } else {
                stopSaveLoop()
                serviceScope.launch { savePosition() }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Save position whenever the active track changes (skip next/prev, seek to track).
            // This ensures the correct track index is persisted even before the 10s loop fires.
            serviceScope.launch { savePosition() }
            // Reset now-playing tracking; send notification if already playing (e.g. auto-advance).
            nowPlayingReportedId = ""
            if (player.isPlaying) {
                reportNowPlaying(mediaItem?.mediaId ?: "")
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                Timber.tag("[Player]").d("Playback ended — pausing and resetting to start")
                // Read albumId while still on the main thread before state changes
                val albumId = player.currentMediaItem?.mediaMetadata?.extras?.getString("albumId")
                player.pause()
                player.seekTo(0, 0)
                // Natural album end: clear saved position so no resume prompt appears
                if (albumId != null) {
                    serviceScope.launch {
                        withContext(Dispatchers.IO) {
                            try {
                                db.playbackPositionDao().deleteForAlbum(albumId)
                                Timber.tag("[Resume]").d("Cleared position for album %s after natural end", albumId)
                            } catch (e: Exception) {
                                Timber.tag("[Resume]").e(e, "Failed to clear position for album %s", albumId)
                            }
                        }
                    }
                }
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            if (!replayGainEnabled) return
            Timber.tag("[Player]").d("Audio session changed — reinitialising LoudnessEnhancer")
            loudnessEnhancer?.release()
            loudnessEnhancer = try {
                LoudnessEnhancer(audioSessionId).also { it.enabled = true }
            } catch (e: Exception) {
                Timber.tag("[Player]").e(e, "Failed to create LoudnessEnhancer")
                null
            }
        }

        override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
            if (!replayGainEnabled) return
            val gainMb = mediaMetadata.extras?.getInt("REPLAYGAIN_ALBUM_GAIN_MB", Int.MIN_VALUE)
            if (gainMb != null && gainMb != Int.MIN_VALUE) {
                try {
                    loudnessEnhancer?.setTargetGain(gainMb)
                    Timber.tag("[Player]").d("ReplayGain applied: %d mB", gainMb)
                } catch (e: Exception) {
                    Timber.tag("[Player]").e(e, "Failed to apply ReplayGain")
                }
            }
        }
    }

    // ─── Audio-becoming-noisy receiver ────────────────────────────────────────

    private fun registerNoisyReceiver() {
        if (noisyReceiver != null) return
        noisyReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                    Timber.tag("[Player]").d("Audio becoming noisy (e.g. BT disconnect) — pausing")
                    player.pause()
                }
            }
        }
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }

    private fun unregisterNoisyReceiver() {
        noisyReceiver?.let { unregisterReceiver(it) }
        noisyReceiver = null
    }

    // ─── Position save loop ───────────────────────────────────────────────────

    private fun startSaveLoop() {
        savePositionJob?.cancel()
        savePositionJob = serviceScope.launch {
            while (true) {
                delay(10_000L)
                savePosition()
                checkAndScrobble()
            }
        }
    }

    private fun stopSaveLoop() {
        savePositionJob?.cancel()
        savePositionJob = null
    }

    private suspend fun savePosition() {
        // Player must be read on the main thread.
        val (albumId, songId, positionMs) = withContext(Dispatchers.Main) {
            val item = player.currentMediaItem ?: return@withContext null
            val albumId = item.mediaMetadata.extras?.getString("albumId") ?: return@withContext null
            val songId = item.mediaId.takeIf { it.isNotEmpty() } ?: return@withContext null
            Triple(albumId, songId, player.currentPosition)
        } ?: return

        withContext(Dispatchers.IO) {
            try {
                db.playbackPositionDao().upsert(
                    PlaybackPositionEntity(
                        albumId = albumId,
                        songId = songId,
                        positionMs = positionMs,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            } catch (e: Exception) {
                Timber.tag("[Player]").e(e, "Failed to save playback position")
            }
        }
    }

    // ─── Scrobbling ───────────────────────────────────────────────────────────

    /**
     * Sends a "now playing" notification to the server for [songId].
     * Called when a track first starts playing (play button or auto-advance).
     * No-ops if [songId] was already reported or is empty.
     */
    private fun reportNowPlaying(songId: String) {
        if (songId.isEmpty() || songId == nowPlayingReportedId) return
        nowPlayingReportedId = songId
        if (!scrobblingEnabled) return
        val client = scrobbleApiClient ?: return
        serviceScope.launch(Dispatchers.IO) {
            client.scrobble(songId, submission = false)
        }
    }

    /**
     * Checks whether the current track has crossed the scrobble threshold (50% or 4 min)
     * and submits a scrobble if so. Called every 10 seconds while playing.
     */
    private suspend fun checkAndScrobble() {
        data class ScrobbleCheck(val songId: String, val positionMs: Long, val durationMs: Long)

        val check = withContext(Dispatchers.Main) {
            val item = player.currentMediaItem ?: return@withContext null
            val id = item.mediaId.takeIf { it.isNotEmpty() } ?: return@withContext null
            if (!player.isPlaying) return@withContext null
            ScrobbleCheck(id, player.currentPosition, player.duration)
        } ?: return

        val (songId, positionMs, durationMs) = check

        if (songId in scrobbledIds) return

        if (durationMs <= 0) return

        val threshold = minOf(durationMs / 2, 240_000L)
        if (positionMs < threshold) return

        if (!scrobblingEnabled) return

        val client = scrobbleApiClient ?: run {
            Timber.tag("[Scrobble]").w("No API client — cannot scrobble song=%s", songId)
            return
        }

        scrobbledIds.add(songId)
        Timber.tag("[Scrobble]").i(
            "Scrobbling song=%s at %dms (threshold=%dms)",
            songId, positionMs, threshold,
        )
        withContext(Dispatchers.IO) {
            client.scrobble(songId, submission = true)
        }
    }

    // ─── Restore last playback ────────────────────────────────────────────────

    private suspend fun restoreLastPlayback() {
        try {
            val saved = withContext(Dispatchers.IO) {
                db.playbackPositionDao().getMostRecent()
            } ?: run {
                Timber.tag("[Player]").d("No saved playback position found")
                return
            }

            val app = applicationContext as TorstenApp
            val config = withContext(Dispatchers.IO) {
                ServerConfigStore(app).serverConfig.first()
            }

            if (!config.isConfigured) {
                Timber.tag("[Player]").d("Server not configured — skipping restore")
                return
            }

            val (songs, album) = withContext(Dispatchers.IO) {
                val s = db.songDao().getByAlbum(saved.albumId)
                val a = db.albumDao().getById(saved.albumId)
                s to a
            }

            if (songs.isEmpty() || album == null) {
                Timber.tag("[Player]").d("Songs or album not found for %s — skipping restore", saved.albumId)
                return
            }

            val coverArtUrl = withContext(Dispatchers.IO) {
                album.coverArtId?.let { SubsonicApiClient(config).getCoverArtUrl(it, 300) }
            }

            val connectivity = (applicationContext as TorstenApp).connectivityMonitor
            val isOnline = connectivity.isOnline.value
            val artworkBitmap = if (!isOnline) {
                MediaItemBuilder.loadArtworkBitmapIfCached(applicationContext, coverArtUrl, album.coverArtId)
            } else null
            val mediaItems = MediaItemBuilder.buildMediaItems(
                songs, album, config, coverArtUrl,
                isOnWifi = connectivity.isOnWifi.value,
                isOnline = isOnline,
                artworkBitmap = artworkBitmap,
            )
            val startIndex = songs.indexOfFirst { it.id == saved.songId }.coerceAtLeast(0)

            // Player operations must happen on the main thread
            withContext(Dispatchers.Main) {
                player.setMediaItems(mediaItems, startIndex, saved.positionMs)
                player.prepare()
                // Do NOT call player.play() — leave in paused/ready state
            }

            Timber.tag("[Player]").d(
                "Restored album %s, track index %d, position %dms",
                saved.albumId, startIndex, saved.positionMs,
            )
        } catch (e: Exception) {
            Timber.tag("[Player]").e(e, "Failed to restore last playback")
        }
    }

    // ─── Session callback ─────────────────────────────────────────────────────

    private inner class PlaybackSessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
    }

    // ─── MediaItem builder (shared with PlaybackViewModel) ───────────────────

    object MediaItemBuilder {

        fun buildMediaItems(
            songs: List<SongEntity>,
            album: AlbumEntity,
            config: ServerConfig,
            coverArtUrl: String?,
            isOnWifi: Boolean,
            isOnline: Boolean,
            artworkBitmap: Bitmap? = null,
            queueType: String = "background",
        ): List<MediaItem> {
            val baseUrl = config.serverUrl.trimEnd('/')
            val auth = SubsonicTokenAuth.buildAuthParams(config.username, config.password)
            // Encode artwork bitmap to bytes once for the whole album.
            // When offline, artworkBitmap is loaded from Coil's disk cache by the caller so
            // the notification shows art without any network request.
            val artworkData: ByteArray? = artworkBitmap?.let { bmp ->
                val out = java.io.ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.toByteArray()
            }
            // Use a remote URI only when online and no embedded bitmap is available.
            val remoteArtworkUri = if (isOnline && artworkData == null) coverArtUrl?.toUri() else null

            return songs.map { song ->
                val hasLocal = !song.localFilePath.isNullOrEmpty() && File(song.localFilePath).exists()
                val streamUri = "$baseUrl/rest/stream?id=${song.id}&format=raw&${auth.asQueryString()}".toUri()
                // Uri.fromFile produces a proper file:// URI.
                // song.localFilePath?.toUri() calls Uri.parse() on a bare path such as
                // /data/user/0/…, which yields a scheme-less URI — ExoPlayer then routes it to
                // its HTTP data source and throws HttpDataSourceException: Malformed URL.
                val localUri = song.localFilePath?.let { Uri.fromFile(File(it)) }

                // Local file always takes priority — guarantees offline playback for downloaded
                // tracks regardless of reported network state (avoids race conditions where
                // isOnWifi may still be true while internet is unavailable).
                // Only stream when no local file exists; surface an error if also offline.
                val uri = when {
                    hasLocal -> localUri!!
                    isOnline -> streamUri
                    else -> streamUri  // offline + no local → ExoPlayer will surface the error
                }

                val source = when {
                    hasLocal -> if (isOnline) "local/online" else "local/offline"
                    isOnline && isOnWifi -> "stream/wifi"
                    isOnline -> "stream/mobile"
                    else -> "stream/offline-fallback"
                }
                Timber.tag("[Player]").d("Song %s → %s", song.id, source)

                val extras = Bundle().apply {
                    putString("albumId", album.id)
                    putString("artistId", album.artistId)
                    putString("songTitle", song.title)
                    putString("artistName", album.artistName)
                    putString("queueType", queueType)
                    putLong("durationMs", song.duration.toLong() * 1000L)
                    if (coverArtUrl != null) putString("coverArtUrl", coverArtUrl)
                    if (album.coverArtId != null) putString("coverArtId", album.coverArtId)
                }

                MediaItem.Builder()
                    .setMediaId(song.id)
                    .setUri(uri)
                    .apply {
                        // MIME type hint for local files. Without it ExoPlayer may not pick the
                        // right extractor and could fall back to probing via HTTP.
                        if (hasLocal) suffixToMimeType(song.suffix)?.let { setMimeType(it) }
                    }
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(song.title)
                            .setArtist(album.artistName)
                            .setAlbumTitle(album.title)
                            .setTrackNumber(song.trackNumber)
                            .apply {
                                when {
                                    // Embedded bitmap: used offline (loaded from Coil disk cache).
                                    artworkData != null ->
                                        setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                                    // Remote URI: used online when no bitmap was pre-loaded.
                                    remoteArtworkUri != null ->
                                        setArtworkUri(remoteArtworkUri)
                                    // else: no artwork — omit rather than trigger a failing request.
                                }
                            }
                            .setExtras(extras)
                            .build()
                    )
                    .build()
            }
        }

        /**
         * Reads cover art from Coil's disk cache without touching the network.
         * Returns null if the image was never cached or the cache entry has been evicted.
         * Callers should only invoke this when offline; when online, [buildMediaItems] will
         * set a remote [Uri] instead and ExoPlayer's bitmap loader will fetch it normally.
         *
         * Uses the same stable key format — `"cover_art_$coverArtId"` — as [coverArtImageRequest]
         * so the lookup always matches the entry written by the UI composables regardless of
         * which auth salt was active when the image was first cached.
         */
        suspend fun loadArtworkBitmapIfCached(
            context: Context,
            url: String?,
            coverArtId: String?,
        ): Bitmap? {
            if (url == null) return null
            // Use the same stable key as coverArtImageRequest in AlbumCardItem.kt.
            val size = android.net.Uri.parse(url).getQueryParameter("size")
            val cacheKey = when {
                coverArtId != null && size != null -> "cover_art_${coverArtId}_$size"
                coverArtId != null -> "cover_art_$coverArtId"
                else -> url
            }
            return withContext(Dispatchers.IO) {
                try {
                    SingletonImageLoader.get(context).diskCache
                        ?.openSnapshot(cacheKey)
                        ?.use { snapshot ->
                            BitmapFactory.decodeFile(snapshot.data.toFile().absolutePath)
                        }
                } catch (e: Exception) {
                    Timber.tag("[Player]").w(e, "Could not read artwork from disk cache")
                    null
                }
            }
        }

        /**
         * Rebuilds a priority [MediaItem] from a [QueueTrack] so it can be re-inserted into a
         * freshly loaded Media3 playlist when the user taps an individual track while priority
         * items are still queued.
         */
        fun buildPriorityMediaItem(track: QueueTrack, config: ServerConfig): MediaItem {
            val baseUrl = config.serverUrl.trimEnd('/')
            val auth = SubsonicTokenAuth.buildAuthParams(config.username, config.password)
            val streamUri = "$baseUrl/rest/stream?id=${track.songId}&format=raw&${auth.asQueryString()}".toUri()
            val extras = Bundle().apply {
                putString("queueType", "priority")
                putString("songTitle", track.title)
                putString("artistName", track.artistName)
                putLong("durationMs", track.durationMs)
                if (track.coverArtUrl != null) putString("coverArtUrl", track.coverArtUrl)
            }
            return MediaItem.Builder()
                .setMediaId(track.songId)
                .setUri(streamUri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artistName)
                        .setAlbumTitle(track.albumTitle)
                        .apply {
                            if (track.coverArtUrl != null) setArtworkUri(track.coverArtUrl.toUri())
                        }
                        .setExtras(extras)
                        .build()
                )
                .build()
        }

        private fun suffixToMimeType(suffix: String?): String? = when (suffix?.lowercase()) {
            "mp3"        -> MimeTypes.AUDIO_MPEG
            "flac"       -> MimeTypes.AUDIO_FLAC
            "ogg", "oga" -> MimeTypes.AUDIO_OGG
            "opus"       -> MimeTypes.AUDIO_OPUS
            "aac"        -> MimeTypes.AUDIO_AAC
            "m4a", "mp4" -> MimeTypes.AUDIO_MP4
            "wav"        -> MimeTypes.AUDIO_WAV
            else         -> null
        }
    }
}
