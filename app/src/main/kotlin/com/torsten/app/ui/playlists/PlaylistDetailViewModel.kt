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
)

class PlaylistDetailViewModel(
    val playlistId: String,
    private val repository: PlaylistRepository,
    private val configStore: ServerConfigStore,
    private val downloadedPlaylistStore: DownloadedPlaylistStore,
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
        loadPlaylist()
    }

    fun loadPlaylist() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(isLoading = true, error = null)
            runCatching { repository.getPlaylist(playlistId) }
                .onSuccess { playlist ->
                    _state.value = PlaylistDetailUiState(playlist = playlist, isLoading = false)
                    val trackIds = playlist.entry.orEmpty().map { it.id }
                    setupDownloadObservation(trackIds)
                }
                .onFailure { e ->
                    Timber.tag("[Playlists]").e(e, "getPlaylist failed")
                    _state.value = PlaylistDetailUiState(isLoading = false, error = e.message ?: "Failed to load playlist")
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
                            playlistId = playlistId,
                            name = playlist.name,
                            songCount = tracks.size,
                            coverArtId = playlist.coverArt,
                            downloadedAt = System.currentTimeMillis(),
                            songIds = tracks.map { it.id },
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
        _state.value = _state.value.copy(playlist = current.copy(name = trimmed))
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
        // Optimistic update
        _state.value = _state.value.copy(
            playlist = current.copy(
                entry = tracks,
                songCount = tracks.size,
            ),
        )
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.removeTrackFromPlaylist(playlistId, songIndex) }
                .onSuccess { _snackbar.tryEmit("\"$removedTitle\" removed from playlist") }
                .onFailure { e ->
                    Timber.tag("[Playlists]").e(e, "removeTrack failed")
                    // Revert
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
            repository = PlaylistRepository(ServerConfigStore(app), app.downloadRepository),
            configStore = ServerConfigStore(app),
            downloadedPlaylistStore = app.downloadedPlaylistStore,
        ) as T
    }
}
