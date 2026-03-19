package com.torsten.app.ui.genre

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.torsten.app.TorstenApp
import com.torsten.app.data.api.SubsonicApiClient
import com.torsten.app.data.api.dto.AlbumDto
import com.torsten.app.data.datastore.ServerConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

data class GenreUiState(
    val isLoading: Boolean = true,
    val albums: List<AlbumDto> = emptyList(),
    val error: String? = null,
)

class GenreViewModel(
    private val genre: String,
    private val configStore: ServerConfigStore,
) : ViewModel() {

    private var apiClient: SubsonicApiClient? = null

    private val _state = MutableStateFlow(GenreUiState())
    val state: StateFlow<GenreUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = GenreUiState(isLoading = true)
            val config = configStore.serverConfig.first()
            if (!config.isConfigured) {
                _state.value = GenreUiState(isLoading = false, error = "Server not configured")
                return@launch
            }
            val client = SubsonicApiClient(config).also { apiClient = it }
            runCatching {
                client.getAlbumList2(type = "byGenre", size = 50, genre = genre)
            }.onSuccess { albums ->
                _state.value = GenreUiState(isLoading = false, albums = albums)
            }.onFailure { e ->
                Timber.tag("[Genre]").e(e, "Failed to load genre albums for %s", genre)
                _state.value = GenreUiState(isLoading = false, error = e.message ?: "Failed to load")
            }
        }
    }

    fun getCoverArtUrl(coverArtId: String, size: Int = 300): String? =
        apiClient?.getCoverArtUrl(coverArtId, size)
}

class GenreViewModelFactory(
    private val context: Context,
    private val genre: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val app = context.applicationContext as TorstenApp
        return GenreViewModel(genre, ServerConfigStore(app)) as T
    }
}
