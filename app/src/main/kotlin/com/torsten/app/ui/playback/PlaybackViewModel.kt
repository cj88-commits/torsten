package com.torsten.app.ui.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.torsten.app.TorstenApp
import com.torsten.app.data.api.SubsonicApiClient
import com.torsten.app.data.datastore.ServerConfig
import com.torsten.app.data.queue.QueueManager
import com.torsten.app.data.repository.InstantMixRepository // kept; unused — do not delete
import com.torsten.app.data.queue.QueueTrack
import com.torsten.app.data.datastore.ServerConfigStore
import com.torsten.app.data.datastore.StreamingConfig
import com.torsten.app.data.datastore.StreamingConfigStore
import com.torsten.app.data.db.AppDatabase
import com.torsten.app.data.api.dto.SongDto
import com.torsten.app.data.db.entity.AlbumEntity
import com.torsten.app.data.db.entity.DownloadState
import com.torsten.app.data.db.entity.PendingStarEntity
import com.torsten.app.data.db.entity.SongEntity
import com.torsten.app.data.network.ConnectivityMonitor
import com.torsten.app.service.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

data class PlaybackUiState(
    val isActive: Boolean = false,
    val isPlaying: Boolean = false,
    val currentIndex: Int = 0,
    val currentSongTitle: String = "",
    val artistName: String = "",
    val artistId: String = "",
    val albumTitle: String = "",
    val albumId: String = "",
    val artworkUri: Uri? = null,
    val coverArtUrl: String? = null,
    val coverArtId: String? = null,
    val positionMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val songTitles: List<String> = emptyList(),
    val trackNumbers: List<Int> = emptyList(),
)

class PlaybackViewModel(private val context: Context) : ViewModel() {

    private val db: AppDatabase by lazy {
        (context.applicationContext as TorstenApp).database
    }

    private val connectivityMonitor: ConnectivityMonitor by lazy {
        (context.applicationContext as TorstenApp).connectivityMonitor
    }

    private var apiClient: SubsonicApiClient? = null

    private val queueManager: QueueManager
        get() = (context.applicationContext as TorstenApp).queueManager

    val priorityQueue: StateFlow<List<QueueTrack>> get() = queueManager.priorityQueue
    val backgroundSequence: StateFlow<List<QueueTrack>> get() = queueManager.backgroundSequence
    val backgroundCurrentIndex: StateFlow<Int> get() = queueManager.backgroundCurrentIndex

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    val isOnline: StateFlow<Boolean> get() = connectivityMonitor.isOnline

    private val _snackbarEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarEvent: SharedFlow<String> = _snackbarEvent.asSharedFlow()

    private val _isMixLoading = MutableStateFlow(false)
    val isMixLoading: StateFlow<Boolean> = _isMixLoading.asStateFlow()

    private val _currentSongId = MutableStateFlow("")

    @Suppress("OPT_IN_USAGE")
    val currentSongStarred: StateFlow<Boolean> = _currentSongId
        .flatMapLatest { id ->
            if (id.isEmpty()) flowOf(false)
            else db.songDao().observeStarredById(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Reactive quality badge text for the Now Playing screen. */
    @Suppress("OPT_IN_USAGE")
    val qualityBadge: StateFlow<String> = combine(
        _currentSongId.flatMapLatest { id ->
            if (id.isEmpty()) flowOf(null)
            else db.songDao().observeById(id).catch { e ->
                // Room throws NPE when a non-null column stores NULL (e.g. a Gson-bypassed
                // null title was persisted). Emit null so the badge gracefully degrades
                // rather than crashing the ViewModel scope.
                Timber.tag("[Player]").e(e, "observeById failed for song %s — badge degraded", id)
                emit(null)
            }
        },
        connectivityMonitor.isOnWifi,
        connectivityMonitor.isOnline,
        StreamingConfigStore(context).streamingConfig,
    ) { song, isOnWifi, isOnline, streamingConfig ->
        computeQualityBadge(song, isOnWifi, isOnline, streamingConfig)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    private var mediaController: MediaController? = null
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var pollJob: Job? = null

    init {
        connectToService()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val config = ServerConfigStore(context).serverConfig.first()
                if (config.isConfigured) {
                    apiClient = SubsonicApiClient(config)
                }
            } catch (e: Exception) {
                Timber.tag("[Player]").e(e, "Failed to initialise SubsonicApiClient in PlaybackViewModel")
            }
        }
    }

    // ─── Service connection ───────────────────────────────────────────────────

    private fun connectToService() {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java),
        )

        val future = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture = future

        future.addListener(
            {
                try {
                    val controller = future.get()
                    mediaController = controller
                    controller.addListener(controllerListener)
                    updateStateFromController(controller)
                    Timber.tag("[Player]").d("MediaController connected")
                } catch (e: Exception) {
                    Timber.tag("[Player]").e(e, "Failed to connect MediaController")
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    // ─── Player listener ──────────────────────────────────────────────────────

    private val controllerListener = object : Player.Listener {

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val controller = mediaController ?: return
            if (isPlaying) {
                startPolling()
            } else {
                stopPolling()
                _state.update { it.copy(positionMs = controller.currentPosition) }
            }
            updateStateFromController(controller)
        }

        override fun onPlaybackStateChanged(state: Int) {
            val controller = mediaController ?: return
            updateStateFromController(controller)
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            val controller = mediaController ?: return
            val songId = mediaItem?.mediaId.orEmpty()
            val isPriority = mediaItem?.mediaMetadata?.extras?.getString("queueType") == "priority"
            if (songId.isNotEmpty()) {
                queueManager.onAdvancedToItem(songId, isPriority)
            }
            updateStateFromController(controller)
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            val controller = mediaController ?: return
            updateStateFromController(controller)
        }

        override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
            val controller = mediaController ?: return
            updateStateFromController(controller)
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val isOffline = !connectivityMonitor.isOnline.value
            if (isOffline) {
                _snackbarEvent.tryEmit("Track not downloaded — connect to continue")
                Timber.tag("[Player]").w("Offline playback error (track unavailable): %s", error.message)
            } else {
                Timber.tag("[Player]").e(error, "Player error")
            }
        }
    }

    // ─── State update ─────────────────────────────────────────────────────────

    private fun updateStateFromController(controller: MediaController) {
        val itemCount = controller.mediaItemCount
        val isActive = itemCount > 0
        val currentIndex = controller.currentMediaItemIndex.coerceAtLeast(0)
        val currentItem = controller.currentMediaItem
        val metadata = currentItem?.mediaMetadata

        // Update song ID for star state observation
        _currentSongId.value = currentItem?.mediaId ?: ""
        Timber.tag("[Player]").d(
            "Track: %s | Artist: %s | Album: %s",
            currentItem?.mediaId, currentItem?.mediaMetadata?.extras?.getString("artistName"),
            currentItem?.mediaMetadata?.extras?.getString("albumTitle"),
        )

        // Build song title and track number lists from the timeline
        // Use extras for title/artist: in-band audio metadata (ID3/Vorbis) is merged into
        // mediaMetadata by ExoPlayer and can overwrite fields we set (e.g. a stream with no
        // title tag causes title to become null). Extras are never touched by the merge.
        val extras = currentItem?.mediaMetadata?.extras
        val currentSongTitle = extras?.getString("songTitle")
            ?: metadata?.title?.toString()
            ?: ""
        val artistName = extras?.getString("artistName")
            ?: metadata?.artist?.toString()
            ?: ""
        val artistId = extras?.getString("artistId") ?: ""
        val albumId = extras?.getString("albumId") ?: ""
        val albumTitleFromExtras = extras?.getString("albumTitle")
            ?: metadata?.albumTitle?.toString()
            ?: ""
        val coverArtUrl = extras?.getString("coverArtUrl")
        val coverArtId = extras?.getString("coverArtId")

        val titles = mutableListOf<String>()
        val numbers = mutableListOf<Int>()
        val timeline = controller.currentTimeline
        for (i in 0 until timeline.windowCount) {
            val window = Timeline.Window()
            timeline.getWindow(i, window)
            val windowExtras = window.mediaItem.mediaMetadata.extras
            titles.add(
                windowExtras?.getString("songTitle")
                    ?: window.mediaItem.mediaMetadata.title?.toString()
                    ?: ""
            )
            numbers.add(window.mediaItem.mediaMetadata.trackNumber ?: (i + 1))
        }

        _state.update { current ->
            current.copy(
                isActive = isActive,
                isPlaying = controller.isPlaying,
                currentIndex = currentIndex,
                currentSongTitle = currentSongTitle,
                artistName = artistName,
                artistId = artistId,
                albumTitle = albumTitleFromExtras,
                albumId = albumId,
                artworkUri = metadata?.artworkUri,
                coverArtUrl = coverArtUrl,
                coverArtId = coverArtId,
                positionMs = controller.currentPosition,
                bufferedPositionMs = controller.bufferedPosition,
                durationMs = controller.duration.coerceAtLeast(0L),
                songTitles = titles,
                trackNumbers = numbers,
            )
        }
    }

    // ─── Position polling ─────────────────────────────────────────────────────

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                delay(500L)
                val controller = mediaController ?: break
                val pos = controller.currentPosition
                val buffered = controller.bufferedPosition
                _state.update { it.copy(positionMs = pos, bufferedPositionMs = buffered) }
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    // ─── Playback controls ────────────────────────────────────────────────────

    fun play() {
        mediaController?.play()
    }

    fun pause() {
        mediaController?.pause()
    }

    fun playPause() {
        val controller = mediaController ?: return
        if (controller.isPlaying) controller.pause() else controller.play()
    }

    fun next() {
        mediaController?.seekToNextMediaItem()
    }

    fun previous() {
        val controller = mediaController ?: return
        // If more than 3 seconds into the track, seek to start; otherwise go to previous
        if (controller.currentPosition > 3_000L) {
            controller.seekTo(0L)
        } else {
            controller.seekToPreviousMediaItem()
        }
    }

    fun seekTo(positionMs: Long) {
        mediaController?.seekTo(positionMs)
    }

    fun seekToTrack(index: Int) {
        mediaController?.seekTo(index, 0L)
    }

    /**
     * Loads the given album into the player and starts playback from [startIndex].
     *
     * When offline, the cover art bitmap is read from Coil's disk cache before building
     * the [MediaItem]s so the notification and lock screen show artwork without any
     * network request. The disk-cache read is the only async step; media item setup and
     * [MediaController] calls all happen on the Main dispatcher as required.
     */
    /**
     * Loads the given album into the player and starts playback from [startIndex].
     *
     * @param preservePriorityQueue When `true` (individual track tap), the existing priority
     *   queue items are kept and re-inserted into the new playlist immediately after the
     *   tapped track. When `false` (Play button / fresh start), the priority queue is cleared.
     */
    fun playAlbum(
        songs: List<SongEntity>,
        album: AlbumEntity,
        startIndex: Int,
        config: ServerConfig,
        coverArtUrl: String?,
        preservePriorityQueue: Boolean = false,
    ) {
        val controller = mediaController ?: run {
            Timber.tag("[Player]").w("playAlbum called before MediaController connected")
            return
        }

        viewModelScope.launch {
            val isOnline = connectivityMonitor.isOnline.value
            val artworkBitmap = if (!isOnline) {
                PlaybackService.MediaItemBuilder.loadArtworkBitmapIfCached(context, coverArtUrl, album.coverArtId)
            } else null

            val bgMediaItems = PlaybackService.MediaItemBuilder.buildMediaItems(
                songs, album, config, coverArtUrl,
                isOnWifi = connectivityMonitor.isOnWifi.value,
                isOnline = isOnline,
                artworkBitmap = artworkBitmap,
            )

            // When preserving the priority queue, rebuild its MediaItems and splice them
            // back in right after the newly selected background track.
            val priorityTracks = if (preservePriorityQueue) queueManager.priorityQueue.value else emptyList()
            val priorityMediaItems = priorityTracks.map { track ->
                PlaybackService.MediaItemBuilder.buildPriorityMediaItem(track, config)
            }
            val mediaItems = if (priorityMediaItems.isEmpty()) {
                bgMediaItems
            } else {
                bgMediaItems.subList(0, startIndex + 1) +
                    priorityMediaItems +
                    bgMediaItems.subList(startIndex + 1, bgMediaItems.size)
            }

            controller.setMediaItems(mediaItems, startIndex, 0L)
            controller.prepare()
            controller.play()
            Timber.tag("[Player]").d(
                "playAlbum: %s, startIndex=%d, preservePriority=%b, pqSize=%d",
                album.title, startIndex, preservePriorityQueue, priorityTracks.size,
            )

            // Sync QueueManager
            val bgTracks = songs.map { song ->
                QueueTrack(
                    songId = song.id,
                    title = song.title,
                    artistName = album.artistName,
                    albumTitle = album.title,
                    coverArtUrl = coverArtUrl,
                    durationMs = song.duration.toLong() * 1000L,
                )
            }
            if (preservePriorityQueue) {
                queueManager.setBackgroundSequenceOnly(bgTracks, startIndex)
            } else {
                queueManager.setBackgroundSequence(bgTracks, startIndex)
            }

        }
    }

    /**
     * Plays a single song from a search result immediately.
     * Converts the DTO to stub entities so the existing playback pipeline is reused unchanged.
     */
    fun playSong(song: SongDto, config: ServerConfig) {
        val now = System.currentTimeMillis()
        val albumId = song.albumId.orEmpty().ifEmpty { "search_${song.id}" }
        val stubAlbum = AlbumEntity(
            id = albumId,
            title = song.album.orEmpty(),
            artistId = song.artistId.orEmpty(),
            artistName = song.artist.orEmpty(),
            year = song.year,
            genre = null,
            songCount = 1,
            duration = song.duration ?: 0,
            coverArtId = song.coverArt,
            starred = false,
            downloadState = DownloadState.NONE,
            downloadProgress = 0,
            downloadedAt = null,
            lastUpdated = now,
        )
        val stubSong = SongEntity(
            id = song.id,
            albumId = albumId,
            artistId = song.artistId.orEmpty(),
            title = song.title,
            trackNumber = song.track ?: 1,
            discNumber = song.discNumber ?: 1,
            duration = song.duration ?: 0,
            bitRate = song.bitRate,
            suffix = song.suffix,
            contentType = song.contentType,
            starred = song.starred != null,
            localFilePath = null,
            lastUpdated = now,
        )
        val coverArtUrl = song.coverArt?.let { SubsonicApiClient(config).getCoverArtUrl(it, 300) }
        playAlbum(listOf(stubSong), stubAlbum, 0, config, coverArtUrl)
    }

    // ─── Instant Mix ──────────────────────────────────────────────────────────

    fun startInstantMix(seedSong: SongDto, config: ServerConfig) {
        if (_isMixLoading.value) return
        _snackbarEvent.tryEmit("Building mix…")
        viewModelScope.launch {
            _isMixLoading.value = true
            try {
                val mix = withContext(Dispatchers.IO) {
                    val seedEntity = db.songDao().getById(seedSong.id)
                        ?: SongEntity(
                            id = seedSong.id,
                            albumId = seedSong.albumId.orEmpty(),
                            artistId = seedSong.artistId.orEmpty(),
                            title = seedSong.title,
                            trackNumber = seedSong.track ?: 0,
                            discNumber = seedSong.discNumber ?: 1,
                            duration = seedSong.duration ?: 0,
                            bitRate = seedSong.bitRate,
                            suffix = seedSong.suffix,
                            contentType = seedSong.contentType,
                            starred = seedSong.starred != null,
                            localFilePath = null,
                            lastUpdated = System.currentTimeMillis(),
                        )
                    val artistName = seedSong.artist.orEmpty()
                    val entities = (context.applicationContext as TorstenApp)
                        .instantMixRepositoryV2.getMix(seedEntity, artistName)
                    entities.map { entityToDto(it) }
                }
                playMix(mix, config)
                _snackbarEvent.tryEmit("Instant mix started")
            } catch (e: Exception) {
                Timber.tag("[InstantMix]").e(e, "Failed to build instant mix for %s", seedSong.id)
                _snackbarEvent.tryEmit("Couldn't build mix")
            } finally {
                _isMixLoading.value = false
            }
        }
    }

    private suspend fun entityToDto(entity: SongEntity): SongDto {
        val album = db.albumDao().getById(entity.albumId)
        return SongDto(
            id = entity.id,
            title = entity.title,
            album = album?.title,
            albumId = entity.albumId,
            artist = album?.artistName,
            artistId = entity.artistId,
            track = entity.trackNumber,
            discNumber = entity.discNumber,
            duration = entity.duration,
            bitRate = entity.bitRate,
            suffix = entity.suffix,
            contentType = entity.contentType,
            coverArt = album?.coverArtId,
            starred = if (entity.starred) "true" else null,
        )
    }

    fun startInstantMixForCurrentSong() {
        val songId = _currentSongId.value.takeIf { it.isNotEmpty() } ?: return
        val s = _state.value
        val seedSong = SongDto(
            id = songId,
            title = s.currentSongTitle,
            artist = s.artistName,
            artistId = s.artistId,
            album = s.albumTitle,
            albumId = s.albumId,
            coverArt = s.coverArtId,
            genre = null,
        )
        viewModelScope.launch {
            val config = ServerConfigStore(context).serverConfig.first()
            if (!config.isConfigured) return@launch
            startInstantMix(seedSong, config)
        }
    }

    /**
     * Public entry-point for playing an ad-hoc list of [SongDto]s (e.g. artist top tracks).
     * Pass [shuffle]=true to randomise order; [startIndex] sets which track begins playback.
     */
    fun playFromSongDtos(
        songs: List<SongDto>,
        config: ServerConfig,
        shuffle: Boolean = false,
        startIndex: Int = 0,
    ) {
        val ordered = if (shuffle) songs.shuffled() else songs
        playMix(ordered, config, if (shuffle) 0 else startIndex.coerceIn(0, (ordered.size - 1).coerceAtLeast(0)))
    }

    private fun playMix(songs: List<SongDto>, config: ServerConfig, startIndex: Int = 0) {
        val controller = mediaController ?: run {
            Timber.tag("[Player]").w("playMix called before MediaController connected")
            return
        }
        val isOnline = connectivityMonitor.isOnline.value
        val isOnWifi = connectivityMonitor.isOnWifi.value
        val now = System.currentTimeMillis()

        val allMediaItems = songs.map { song ->
            val albumId = song.albumId.orEmpty().ifEmpty { "mix_${song.id}" }
            val stubAlbum = AlbumEntity(
                id = albumId,
                title = song.album.orEmpty(),
                artistId = song.artistId.orEmpty(),
                artistName = song.artist.orEmpty(),
                year = song.year,
                genre = null,
                songCount = 1,
                duration = song.duration ?: 0,
                coverArtId = song.coverArt,
                starred = false,
                downloadState = DownloadState.NONE,
                downloadProgress = 0,
                downloadedAt = null,
                lastUpdated = now,
            )
            val stubSong = SongEntity(
                id = song.id,
                albumId = albumId,
                artistId = song.artistId.orEmpty(),
                title = song.title,
                trackNumber = song.track ?: 1,
                discNumber = song.discNumber ?: 1,
                duration = song.duration ?: 0,
                bitRate = song.bitRate,
                suffix = song.suffix,
                contentType = song.contentType,
                starred = song.starred != null,
                localFilePath = null,
                lastUpdated = now,
            )
            val coverArtUrl = song.coverArt?.let { SubsonicApiClient(config).getCoverArtUrl(it, 300) }
            PlaybackService.MediaItemBuilder.buildMediaItems(
                songs = listOf(stubSong),
                album = stubAlbum,
                config = config,
                coverArtUrl = coverArtUrl,
                isOnWifi = isOnWifi,
                isOnline = isOnline,
                artworkBitmap = null,
            ).first()
        }

        val bgTracks = songs.map { song ->
            val albumId = song.albumId.orEmpty().ifEmpty { "mix_${song.id}" }
            QueueTrack(
                songId = song.id,
                title = song.title,
                artistName = song.artist.orEmpty(),
                albumTitle = song.album.orEmpty(),
                coverArtUrl = song.coverArt?.let { SubsonicApiClient(config).getCoverArtUrl(it, 300) },
                durationMs = (song.duration ?: 0).toLong() * 1000L,
            )
        }

        controller.setMediaItems(allMediaItems, startIndex, 0L)
        controller.playWhenReady = true
        controller.prepare()
        queueManager.setBackgroundSequence(bgTracks, startIndex)
        Timber.tag("[Player]").d("playMix: %d tracks, startIndex=%d, seed=%s", songs.size, startIndex, songs.firstOrNull()?.id)
    }

    // ─── Queue management ─────────────────────────────────────────────────────

    /**
     * Enqueues [song] to play after the current track and any already-queued priority items.
     * Convenience overload for search results (converts SongDto to stub entities internally).
     */
    fun enqueueNext(song: SongDto, config: ServerConfig) {
        mediaController ?: run {
            Timber.tag("[Player]").w("enqueueNext called before MediaController connected")
            return
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val albumId = song.albumId.orEmpty().ifEmpty { "search_${song.id}" }
            val stubAlbum = AlbumEntity(
                id = albumId,
                title = song.album.orEmpty(),
                artistId = song.artistId.orEmpty(),
                artistName = song.artist.orEmpty(),
                year = song.year,
                genre = null,
                songCount = 1,
                duration = song.duration ?: 0,
                coverArtId = song.coverArt,
                starred = false,
                downloadState = DownloadState.NONE,
                downloadProgress = 0,
                downloadedAt = null,
                lastUpdated = now,
            )
            val stubSong = SongEntity(
                id = song.id,
                albumId = albumId,
                artistId = song.artistId.orEmpty(),
                title = song.title,
                trackNumber = song.track ?: 1,
                discNumber = song.discNumber ?: 1,
                duration = song.duration ?: 0,
                bitRate = song.bitRate,
                suffix = song.suffix,
                contentType = song.contentType,
                starred = song.starred != null,
                localFilePath = null,
                lastUpdated = now,
            )
            val coverArtUrl = song.coverArt?.let { SubsonicApiClient(config).getCoverArtUrl(it, 300) }
            enqueueEntitySong(stubSong, stubAlbum, config, coverArtUrl)
        }
    }

    /**
     * Enqueues a track from the album detail screen (already-resolved entities).
     */
    fun enqueueNextSong(
        song: SongEntity,
        album: AlbumEntity,
        config: ServerConfig,
        coverArtUrl: String?,
    ) {
        mediaController ?: run {
            Timber.tag("[Player]").w("enqueueNextSong called before MediaController connected")
            return
        }
        viewModelScope.launch { enqueueEntitySong(song, album, config, coverArtUrl) }
    }

    private suspend fun enqueueEntitySong(
        song: SongEntity,
        album: AlbumEntity,
        config: ServerConfig,
        coverArtUrl: String?,
    ) {
        val controller = mediaController ?: return
        val isOnline = connectivityMonitor.isOnline.value
        val mediaItems = PlaybackService.MediaItemBuilder.buildMediaItems(
            songs = listOf(song),
            album = album,
            config = config,
            coverArtUrl = coverArtUrl,
            isOnWifi = connectivityMonitor.isOnWifi.value,
            isOnline = isOnline,
            artworkBitmap = null,
            queueType = "priority",
        )
        val insertIndex = controller.currentMediaItemIndex + 1 + queueManager.priorityQueue.value.size
        controller.addMediaItem(insertIndex, mediaItems.first())

        val track = QueueTrack(
            songId = song.id,
            title = song.title,
            artistName = album.artistName,
            albumTitle = album.title,
            coverArtUrl = coverArtUrl,
            durationMs = song.duration.toLong() * 1000L,
        )
        queueManager.enqueueTrack(track)
        Timber.tag("[Player]").d("Enqueued next: %s", song.title)
    }

    /** Removes the priority queue item at [priorityIndex] from both the queue and the Media3 playlist. */
    fun removeFromQueue(priorityIndex: Int) {
        val controller = mediaController ?: return
        val media3Index = controller.currentMediaItemIndex + 1 + priorityIndex
        if (media3Index < controller.mediaItemCount) {
            controller.removeMediaItem(media3Index)
        }
        queueManager.removeFromPriorityQueue(priorityIndex)
    }

    /** Reorders a priority queue item from [fromIndex] to [toIndex]. */
    fun moveInPriorityQueue(fromIndex: Int, toIndex: Int) {
        val controller = mediaController ?: return
        val base = controller.currentMediaItemIndex + 1
        val fromMedia3 = base + fromIndex
        val toMedia3 = base + toIndex
        val itemCount = controller.mediaItemCount
        if (fromMedia3 < itemCount && toMedia3 < itemCount) {
            controller.moveMediaItem(fromMedia3, toMedia3)
        }
        queueManager.movePriorityQueueItem(fromIndex, toIndex)
    }

    /** Clears all priority queue items from both the queue and the Media3 playlist. */
    fun clearPriorityQueue() {
        val controller = mediaController ?: return
        val currentIdx = controller.currentMediaItemIndex
        val pqSize = queueManager.priorityQueue.value.size
        repeat(pqSize) {
            val removeIdx = currentIdx + 1
            if (removeIdx < controller.mediaItemCount) {
                controller.removeMediaItem(removeIdx)
            }
        }
        queueManager.clearPriorityQueue()
    }

    /**
     * Seeks to the background sequence track at [bgIndex].
     * Accounts for priority items that sit between the current position and future bg tracks.
     */
    fun seekToBackgroundTrack(bgIndex: Int) {
        val controller = mediaController ?: return
        val currentBgIdx = queueManager.backgroundCurrentIndex.value
        val pqSize = queueManager.priorityQueue.value.size
        val media3Index = if (bgIndex <= currentBgIdx) bgIndex else bgIndex + pqSize
        if (media3Index < controller.mediaItemCount) {
            controller.seekTo(media3Index, 0L)
        }
    }

    // ─── Stars ────────────────────────────────────────────────────────────────

    fun toggleCurrentSongStar() {
        val songId = _currentSongId.value.takeIf { it.isNotEmpty() } ?: return
        val currentStarred = currentSongStarred.value
        val newStarred = !currentStarred

        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.songDao().updateStarred(songId, newStarred) }
            Timber.tag("[Stars]").d("Song %s starred=%b (optimistic, NowPlaying)", songId, newStarred)

            val client = apiClient
            val isOnline = connectivityMonitor.isOnline.value

            if (client == null || !isOnline) {
                withContext(Dispatchers.IO) {
                    db.pendingStarDao().upsert(
                        PendingStarEntity(
                            targetId = songId,
                            targetType = "song",
                            starred = newStarred,
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                }
                _snackbarEvent.tryEmit("Will sync when back online")
                Timber.tag("[Stars]").d("Queued pending star for song %s", songId)
                return@launch
            }

            try {
                if (newStarred) client.star(id = songId) else client.unstar(id = songId)
                Timber.tag("[Stars]").d("Song star %b synced: %s", newStarred, songId)
            } catch (e: Exception) {
                Timber.tag("[Stars]").e(e, "Failed to sync star for song %s", songId)
                withContext(Dispatchers.IO) { db.songDao().updateStarred(songId, currentStarred) }
                _snackbarEvent.tryEmit("Couldn't save — try again")
            }
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCleared() {
        stopPolling()
        mediaController?.removeListener(controllerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        mediaController = null
        super.onCleared()
    }
}

class PlaybackViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        PlaybackViewModel(context.applicationContext) as T
}

// ─── Quality badge helpers ────────────────────────────────────────────────────

private fun computeQualityBadge(
    song: SongEntity?,
    isOnWifi: Boolean,
    isOnline: Boolean,
    streamingConfig: StreamingConfig,
): String {
    if (song == null) return ""
    return when {
        // Mobile online: we transcode — show configured mobile format/bitrate.
        // If mobileFormat is "raw" (no transcode), fall through to original quality.
        isOnline && !isOnWifi && streamingConfig.mobileFormat != "raw" ->
            formatTranscodeQuality(streamingConfig.mobileFormat, streamingConfig.mobileMaxBitRate)
        // WiFi (always stream raw) or offline (local file): show the source file's quality.
        else -> formatOriginalQuality(song.suffix, song.bitRate)
    }
}

private fun formatTranscodeQuality(format: String, maxBitRate: Int): String {
    val label = when (format.lowercase()) {
        "opus" -> "Opus"
        "mp3" -> "MP3"
        "aac" -> "AAC"
        else -> format.replaceFirstChar { it.uppercase() }
    }
    return if (maxBitRate > 0) "$label ${maxBitRate}kbps" else label
}

private val losslessFormats = setOf("flac", "wav", "aiff", "aif", "alac")

private fun formatOriginalQuality(suffix: String?, bitRate: Int?): String {
    val lower = suffix?.lowercase() ?: return ""
    if (lower in losslessFormats) {
        return when (lower) {
            "flac" -> "FLAC"
            "wav" -> "WAV"
            "aiff", "aif" -> "AIFF"
            "alac" -> "ALAC"
            else -> suffix.uppercase()
        }
    }
    val label = when (lower) {
        "opus" -> "Opus"
        "mp3" -> "MP3"
        "aac", "m4a" -> "AAC"
        "ogg", "oga" -> "OGG"
        else -> suffix.uppercase()
    }
    return if (bitRate != null && bitRate > 0) "$label ${bitRate}kbps" else label
}
