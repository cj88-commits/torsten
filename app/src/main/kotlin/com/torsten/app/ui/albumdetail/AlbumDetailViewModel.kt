package com.torsten.app.ui.albumdetail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.torsten.app.TorstenApp
import com.torsten.app.data.api.SubsonicApiClient
import com.torsten.app.data.datastore.ServerConfig
import com.torsten.app.data.datastore.ServerConfigStore
import com.torsten.app.data.db.AppDatabase
import com.torsten.app.data.db.entity.AlbumEntity
import com.torsten.app.data.db.entity.DownloadState
import com.torsten.app.data.db.entity.PendingStarEntity
import com.torsten.app.data.db.entity.PlaybackPositionEntity
import com.torsten.app.data.db.entity.SongEntity
import com.torsten.app.data.download.DownloadRepository
import com.torsten.app.data.download.StorageUtils
import com.torsten.app.data.network.ConnectivityMonitor
import com.torsten.app.data.recommendation.ArtistTopTracksRepository
import com.torsten.app.data.repository.SyncRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class AlbumDetailViewModel(
    private val db: AppDatabase,
    private val albumId: String,
    private val syncRepository: SyncRepository,
    private val configStore: ServerConfigStore,
    private val downloadRepository: DownloadRepository,
    private val connectivityMonitor: ConnectivityMonitor,
    private val appContext: Context,
    private val artistTopTracksRepository: ArtistTopTracksRepository,
) : ViewModel() {

    // Raw Room flow — always up-to-date, used for download-related derived flows.
    private val rawAlbum: Flow<AlbumEntity?> = db.albumDao().observeById(albumId)

    // Filtered view: only emits when display-relevant fields change (title, artist, cover art,
    // starred). Download progress updates are suppressed here so the cover art and info
    // sections don't recompose on every worker tick.
    val album: StateFlow<AlbumEntity?> = rawAlbum
        .distinctUntilChanged { old, new ->
            old?.id == new?.id &&
            old?.title == new?.title &&
            old?.artistId == new?.artistId &&
            old?.artistName == new?.artistName &&
            old?.year == new?.year &&
            old?.coverArtId == new?.coverArtId &&
            old?.starred == new?.starred
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val songs: StateFlow<List<SongEntity>> = db.songDao()
        .observeByAlbum(albumId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Derive download flows from rawAlbum so they update even when album is filtered.
    val downloadState: StateFlow<DownloadState> = rawAlbum
        .map { it?.downloadState ?: DownloadState.NONE }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadState.NONE)

    val downloadProgress: StateFlow<Int> = rawAlbum
        .map { it?.downloadProgress ?: 0 }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val isOnline: StateFlow<Boolean> = connectivityMonitor.isOnline
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val _savedPosition = MutableStateFlow<PlaybackPositionEntity?>(null)
    val savedPosition: StateFlow<PlaybackPositionEntity?> = _savedPosition.asStateFlow()

    private val _isLoadingSongs = MutableStateFlow(false)
    val isLoadingSongs: StateFlow<Boolean> = _isLoadingSongs.asStateFlow()

    private val _snackbarEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarEvent: SharedFlow<String> = _snackbarEvent.asSharedFlow()

    private var apiClient: SubsonicApiClient? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _savedPosition.value = db.playbackPositionDao().getForAlbum(albumId)
        }

        viewModelScope.launch {
            _isLoadingSongs.value = true
            try {
                syncRepository.fetchAndCacheSongs(albumId)
            } catch (e: Exception) {
                Timber.tag("[Sync]").e(e, "fetchAndCacheSongs failed for album %s", albumId)
            } finally {
                _isLoadingSongs.value = false
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val config = configStore.serverConfig.first()
                if (config.isConfigured) {
                    val client = SubsonicApiClient(config)
                    apiClient = client
                    // Process any star ops that were queued while offline
                    val songIds = db.songDao().getByAlbum(albumId).map { it.id }
                    processPendingStars(client, songIds)
                }
            } catch (e: Exception) {
                Timber.tag("[API]").e(e, "Failed to initialise SubsonicApiClient in AlbumDetailViewModel")
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            val a = album.first { it != null } ?: return@launch
            Timber.tag("[ArtistTop]").d("prefetch trigger=albumOpen artistId='%s' artistName='%s'", a.artistId, a.artistName)
            artistTopTracksRepository.prefetchIfNeeded(a.artistId, a.artistName)
        }
    }

    // ─── URL helpers ──────────────────────────────────────────────────────────

    fun getCoverArtUrl(coverArtId: String, size: Int = 300): String? =
        apiClient?.getCoverArtUrl(coverArtId, size)

    fun getServerConfig(): Flow<ServerConfig> = configStore.serverConfig

    // ─── Stars ────────────────────────────────────────────────────────────────

    fun toggleSongStar(song: SongEntity) {
        val newStarred = !song.starred
        viewModelScope.launch {
            // Optimistic: write to Room immediately so the UI responds instantly
            withContext(Dispatchers.IO) { db.songDao().updateStarred(song.id, newStarred) }
            Timber.tag("[Stars]").d("Song %s starred=%b (optimistic)", song.id, newStarred)

            val client = apiClient
            val isOnline = connectivityMonitor.isOnline.value

            if (client == null || !isOnline) {
                // Queue for later sync
                withContext(Dispatchers.IO) {
                    db.pendingStarDao().upsert(
                        PendingStarEntity(
                            targetId = song.id,
                            targetType = "song",
                            starred = newStarred,
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                }
                _snackbarEvent.tryEmit("Will sync when back online")
                Timber.tag("[Stars]").d("Queued pending star for song %s", song.id)
                return@launch
            }

            try {
                if (newStarred) client.star(id = song.id) else client.unstar(id = song.id)
                Timber.tag("[Stars]").d("Song star %b synced: %s", newStarred, song.id)
            } catch (e: Exception) {
                Timber.tag("[Stars]").e(e, "Failed to sync star for song %s", song.id)
                // Revert the optimistic update
                withContext(Dispatchers.IO) { db.songDao().updateStarred(song.id, song.starred) }
                _snackbarEvent.tryEmit("Couldn't save — try again")
            }
        }
    }

    fun toggleAlbumStar(album: AlbumEntity) {
        val newStarred = !album.starred
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.albumDao().updateStarred(album.id, newStarred) }
            Timber.tag("[Stars]").d("Album %s starred=%b (optimistic)", album.id, newStarred)

            val client = apiClient
            val isOnline = connectivityMonitor.isOnline.value

            if (client == null || !isOnline) {
                withContext(Dispatchers.IO) {
                    db.pendingStarDao().upsert(
                        PendingStarEntity(
                            targetId = album.id,
                            targetType = "album",
                            starred = newStarred,
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                }
                _snackbarEvent.tryEmit("Will sync when back online")
                Timber.tag("[Stars]").d("Queued pending star for album %s", album.id)
                return@launch
            }

            try {
                if (newStarred) client.star(albumId = album.id) else client.unstar(albumId = album.id)
                Timber.tag("[Stars]").d("Album star %b synced: %s", newStarred, album.id)
            } catch (e: Exception) {
                Timber.tag("[Stars]").e(e, "Failed to sync star for album %s", album.id)
                withContext(Dispatchers.IO) { db.albumDao().updateStarred(album.id, album.starred) }
                _snackbarEvent.tryEmit("Couldn't save — try again")
            }
        }
    }

    /**
     * Processes pending star operations for this album's songs and the album itself.
     * Called on init once the API client is available and connectivity is confirmed.
     * Must be called from an IO-friendly context.
     */
    private suspend fun processPendingStars(client: SubsonicApiClient, songIds: List<String>) {
        if (!connectivityMonitor.isOnline.value) return

        val ids = songIds + albumId
        val pending = db.pendingStarDao().getForIds(ids)
        if (pending.isEmpty()) return

        for (op in pending) {
            try {
                when {
                    op.starred && op.targetType == "song" -> client.star(id = op.targetId)
                    op.starred && op.targetType == "album" -> client.star(albumId = op.targetId)
                    !op.starred && op.targetType == "song" -> client.unstar(id = op.targetId)
                    else -> client.unstar(albumId = op.targetId)
                }
                db.pendingStarDao().delete(op.targetId)
                Timber.tag("[Stars]").d("Processed pending star for %s: %b", op.targetId, op.starred)
            } catch (e: Exception) {
                Timber.tag("[Stars]").e(e, "Failed to process pending star for %s", op.targetId)
            }
        }
    }

    // ─── Resume prompt ────────────────────────────────────────────────────────

    fun clearSavedPosition() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                db.playbackPositionDao().deleteForAlbum(albumId)
                _savedPosition.value = null
                Timber.tag("[Resume]").d("Cleared saved position for album %s", albumId)
            } catch (e: Exception) {
                Timber.tag("[Resume]").e(e, "Failed to clear saved position for album %s", albumId)
            }
        }
    }

    // ─── Downloads ────────────────────────────────────────────────────────────

    fun downloadAlbum() {
        viewModelScope.launch {
            val songList = songs.value
            if (songList.isEmpty()) return@launch

            val estimated = StorageUtils.estimatedAlbumBytes(songList)
            if (!StorageUtils.hasEnoughStorage(appContext, estimated)) {
                _snackbarEvent.tryEmit("Not enough storage to download this album")
                return@launch
            }

            downloadRepository.enqueueDownload(albumId)
        }
    }

    fun cancelDownload() {
        viewModelScope.launch {
            downloadRepository.cancelDownload(albumId)
        }
    }

    fun deleteDownload() {
        viewModelScope.launch {
            downloadRepository.cancelDownload(albumId)
            Timber.tag("[Download]").d("Deleted download for album %s", albumId)
        }
    }
}

class AlbumDetailViewModelFactory(
    private val context: Context,
    private val albumId: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val app = context.applicationContext as TorstenApp
        return AlbumDetailViewModel(
            db = app.database,
            albumId = albumId,
            syncRepository = app.syncRepository,
            configStore = ServerConfigStore(app),
            downloadRepository = app.downloadRepository,
            connectivityMonitor = app.connectivityMonitor,
            appContext = app,
            artistTopTracksRepository = app.artistTopTracksRepository,
        ) as T
    }
}
