package com.torsten.app.ui.playlists

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.torsten.app.TorstenApp
import com.torsten.app.data.api.SubsonicApiClient
import com.torsten.app.data.api.dto.PlaylistWithTracksDto
import com.torsten.app.data.datastore.DownloadedPlaylistInfo
import com.torsten.app.data.datastore.DownloadedPlaylistStore
import com.torsten.app.data.datastore.ServerConfig
import com.torsten.app.data.datastore.ServerConfigStore
import com.torsten.app.data.db.AppDatabase
import com.torsten.app.data.db.entity.DownloadState
import com.torsten.app.data.repository.PlaylistRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

data class PlaylistDetailUiState(
    val playlist: PlaylistWithTracksDto? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    /** True when displaying locally cached tracks because the network fetch failed. */
    val isShowingCached: Boolean = false,
    /** Album IDs whose downloadState == COMPLETE; used to gate offline playback per track. */
    val downloadedAlbumIds: Set<String> = emptySet(),
)

class PlaylistDetailViewModel(
    val playlistId: String,
    private val repository: PlaylistRepository,
    private val configStore: ServerConfigStore,
    private val downloadedPlaylistStore: DownloadedPlaylistStore,
    private val db: AppDatabase,
) : ViewModel() {

    private val _state = MutableStateFlow(PlaylistDetailUiState())
    val state: StateFlow<PlaylistDetailUiState> = _state.asStateFlow()

    private val _snackbar = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbar: SharedFlow<String> = _snackbar.asSharedFlow()

    private val _downloadState = MutableStateFlow(DownloadState.NONE)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    private var downloadStateJob: Job? = null

    private var serverConfig: ServerConfig? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            serverConfig = configStore.serverConfig.first()
        }
        // Keep downloadedAlbumIds live so the UI reacts as albums are downloaded
        viewModelScope.launch {
            db.albumDao().observeAll().collect { albums ->
                val ids = albums
                    .filter { it.downloadState == DownloadState.COMPLETE }
                    .map { it.id }
                    .toSet()
                _state.value = _state.value.copy(downloadedAlbumIds = ids)
            }
        }
        loadPlaylist()
    }

    fun loadPlaylist() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(error = null)

            // ── Step 1: serve from cache immediately (no spinner if cache exists) ──
            val cached = repository.getCachedPlaylistTracks(playlistId)
            if (cached.isNotEmpty()) {
                val stub = PlaylistWithTracksDto(
                    id        = playlistId,
                    name      = "",        // UI falls back to the initialName parameter
                    entry     = cached,
                    duration  = cached.sumOf { it.duration ?: 0 },
                    songCount = cached.size,
                )
                _state.value = _state.value.copy(
                    playlist        = stub,
                    isLoading       = false,
                    isShowingCached = true,
                )
                setupDownloadObservation(cached.map { it.id })
            } else {
                _state.value = _state.value.copy(isLoading = true)
            }

            // ── Step 2: refresh from network ──────────────────────────────────
            runCatching { repository.getPlaylist(playlistId) }
                .onSuccess { playlist ->
                    _state.value = _state.value.copy(
                        playlist        = playlist,
                        isLoading       = false,
                        isShowingCached = false,
                        error           = null,
                    )
                    val trackIds = playlist.entry.orEmpty().map { it.id }
                    setupDownloadObservation(trackIds)
                    repository.cachePlaylistTracks(playlistId, playlist.entry.orEmpty())
                }
                .onFailure { e ->
                    Timber.tag("[Playlists]").e(e, "getPlaylist failed")
                    if (cached.isEmpty()) {
                        _state.value = _state.value.copy(
                            isLoading = false,
                            error     = "Playlist unavailable offline",
                        )
                    }
                    // If cached tracks are already showing, leave them — isShowingCached stays true
                }
        }
    }

    private fun setupDownloadObservation(trackIds: List<String>) {
        downloadStateJob?.cancel()
        if (trackIds.isEmpty()) return
        downloadStateJob = viewModelScope.launch {
            val dbFlow = repository.observePlaylistDownloadState(trackIds)
            combine(dbFlow, _isDownloading) { dbState, isDownloading ->
                when {
                    dbState == DownloadState.COMPLETE -> DownloadState.COMPLETE
                    isDownloading -> DownloadState.DOWNLOADING
                    else -> dbState
                }
            }.collect { state ->
                _downloadState.value = state
                if (state == DownloadState.COMPLETE || state == DownloadState.PARTIAL) {
                    _isDownloading.value = false
                }
            }
        }
    }

    fun downloadPlaylist() {
        val playlist = _state.value.playlist ?: return
        val tracks = playlist.entry.orEmpty()
        if (tracks.isEmpty()) return
        _isDownloading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.downloadPlaylist(playlistId, tracks) }
                .onSuccess {
                    downloadedPlaylistStore.save(
                        DownloadedPlaylistInfo(
                            playlistId   = playlistId,
                            name         = playlist.name,
                            songCount    = tracks.size,
                            coverArtId   = playlist.coverArt,
                            downloadedAt = System.currentTimeMillis(),
                            songIds      = tracks.map { it.id },
                        ),
                    )
                }
                .onFailure { e ->
                    Timber.tag("[Download]").e(e, "downloadPlaylist failed")
                    _isDownloading.value = false
                    _snackbar.tryEmit("Failed to start download")
                }
        }
    }

    fun cancelPlaylistDownload() {
        repository.cancelPlaylistDownload(playlistId)
        _isDownloading.value = false
    }

    fun deletePlaylistDownload() {
        val trackIds = _state.value.playlist?.entry.orEmpty().map { it.id }
        _downloadState.value = DownloadState.NONE
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.deletePlaylistDownload(trackIds) }
                .onSuccess { downloadedPlaylistStore.remove(playlistId) }
                .onFailure { e ->
                    Timber.tag("[Download]").e(e, "deletePlaylistDownload failed")
                    _snackbar.tryEmit("Failed to remove downloads")
                }
        }
    }

    fun renamePlaylist(name: String) {
        val trimmed = name.trim().ifEmpty { return }
        val current = _state.value.playlist ?: return
        val newPlaylist = current.copy(name = trimmed)
        _state.value = _state.value.copy(playlist = newPlaylist)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.renamePlaylist(playlistId, trimmed) }
                .onFailure { e ->
                    Timber.tag("[Playlists]").e(e, "renamePlaylist failed")
                    loadPlaylist()
                    _snackbar.tryEmit("Failed to rename playlist")
                }
        }
    }

    fun removeTrack(songIndex: Int) {
        val current = _state.value.playlist ?: return
        val tracks = current.entry.orEmpty().toMutableList()
        if (songIndex !in tracks.indices) return
        val removedTitle = tracks[songIndex].title
        tracks.removeAt(songIndex)
        _state.value = _state.value.copy(
            playlist = current.copy(
                entry     = tracks,
                songCount = tracks.size,
            ),
        )
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.removeTrackFromPlaylist(playlistId, songIndex) }
                .onSuccess { _snackbar.tryEmit("\"$removedTitle\" removed from playlist") }
                .onFailure { e ->
                    Timber.tag("[Playlists]").e(e, "removeTrack failed")
                    loadPlaylist()
                    _snackbar.tryEmit("Failed to remove track")
                }
        }
    }

    fun getCoverArtUrl(coverArtId: String, size: Int = 300): String? {
        val config = serverConfig ?: return null
        return SubsonicApiClient(config).getCoverArtUrl(coverArtId, size)
    }

    fun getServerConfig(): Flow<ServerConfig> = configStore.serverConfig
}

class PlaylistDetailViewModelFactory(
    private val context: Context,
    private val playlistId: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val app = context.applicationContext as TorstenApp
        return PlaylistDetailViewModel(
            playlistId = playlistId,
            repository = PlaylistRepository(
                configStore        = ServerConfigStore(app),
                downloadRepository = app.downloadRepository,
                playlistTrackDao   = app.database.playlistTrackDao(),
                albumDao           = app.database.albumDao(),
            ),
            configStore             = ServerConfigStore(app),
            downloadedPlaylistStore = app.downloadedPlaylistStore,
            db                      = app.database,
        ) as T
    }
}
