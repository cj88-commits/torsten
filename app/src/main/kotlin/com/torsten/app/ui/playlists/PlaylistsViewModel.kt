package com.torsten.app.ui.playlists

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.torsten.app.TorstenApp
import com.torsten.app.data.api.SubsonicApiClient
import com.torsten.app.data.api.dto.PlaylistDto
import com.torsten.app.data.datastore.ServerConfig
import com.torsten.app.data.datastore.ServerConfigStore
import com.torsten.app.data.repository.PlaylistRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

data class PlaylistsUiState(
    val playlists: List<PlaylistDto> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

class PlaylistsViewModel(
    private val repository: PlaylistRepository,
    private val configStore: ServerConfigStore,
) : ViewModel() {

    private val _state = MutableStateFlow(PlaylistsUiState())
    val state: StateFlow<PlaylistsUiState> = _state.asStateFlow()

    private val _snackbar = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbar: SharedFlow<String> = _snackbar.asSharedFlow()

    private var serverConfig: ServerConfig? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            serverConfig = configStore.serverConfig.first()
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(isLoading = true, error = null)
            runCatching { repository.getPlaylists() }
                .onSuccess { playlists ->
                    _state.value = PlaylistsUiState(playlists = playlists, isLoading = false)
                }
                .onFailure { e ->
                    Timber.tag("[Playlists]").e(e, "Failed to load playlists")
                    _state.value = PlaylistsUiState(isLoading = false, error = e.message ?: "Failed to load playlists")
                }
        }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.createPlaylist(name.trim()) }
                .onSuccess { created ->
                    if (created != null) {
                        _state.value = _state.value.copy(
                            playlists = _state.value.playlists + created,
                        )
                        _snackbar.tryEmit("Playlist \"${created.name}\" created")
                    }
                }
                .onFailure { e ->
                    Timber.tag("[Playlists]").e(e, "createPlaylist failed")
                    _snackbar.tryEmit("Failed to create playlist")
                }
        }
    }

    fun addTrackToPlaylist(playlistId: String, trackId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.addTrackToPlaylist(playlistId, trackId) }
                .onSuccess { _snackbar.tryEmit("Added to playlist") }
                .onFailure { _snackbar.tryEmit("Failed to add to playlist") }
        }
    }

    fun getCoverArtUrl(coverArtId: String, size: Int = 300): String? {
        val config = serverConfig ?: return null
        return SubsonicApiClient(config).getCoverArtUrl(coverArtId, size)
    }
}

class PlaylistsViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val app = context.applicationContext as TorstenApp
        return PlaylistsViewModel(
            repository = PlaylistRepository(ServerConfigStore(app)),
            configStore = ServerConfigStore(app),
        ) as T
    }
}
