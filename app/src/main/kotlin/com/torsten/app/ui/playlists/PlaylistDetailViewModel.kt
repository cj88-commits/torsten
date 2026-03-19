package com.torsten.app.ui.playlists

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.torsten.app.TorstenApp
import com.torsten.app.data.api.SubsonicApiClient
import com.torsten.app.data.api.dto.PlaylistWithTracksDto
import com.torsten.app.data.datastore.ServerConfig
import com.torsten.app.data.datastore.ServerConfigStore
import com.torsten.app.data.repository.PlaylistRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
) : ViewModel() {

    private val _state = MutableStateFlow(PlaylistDetailUiState())
    val state: StateFlow<PlaylistDetailUiState> = _state.asStateFlow()

    private val _snackbar = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbar: SharedFlow<String> = _snackbar.asSharedFlow()

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
                }
                .onFailure { e ->
                    Timber.tag("[Playlists]").e(e, "getPlaylist failed")
                    _state.value = PlaylistDetailUiState(isLoading = false, error = e.message ?: "Failed to load playlist")
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
            repository = PlaylistRepository(ServerConfigStore(app)),
            configStore = ServerConfigStore(app),
        ) as T
    }
}
