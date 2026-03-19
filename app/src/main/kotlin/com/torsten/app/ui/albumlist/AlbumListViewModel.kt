package com.torsten.app.ui.albumlist

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.torsten.app.TorstenApp
import com.torsten.app.data.api.SubsonicApiClient
import com.torsten.app.data.api.dto.AlbumDto
import com.torsten.app.data.datastore.ServerConfigStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

data class AlbumListUiState(
    val isLoading: Boolean = true,
    val albums: List<AlbumDto> = emptyList(),
    val error: String? = null,
)

class AlbumListViewModel(
    private val listType: String,
    private val configStore: ServerConfigStore,
) : ViewModel() {

    private var apiClient: SubsonicApiClient? = null

    private val _state = MutableStateFlow(AlbumListUiState())
    val state: StateFlow<AlbumListUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = AlbumListUiState(isLoading = true)
            val config = configStore.serverConfig.first()
            if (!config.isConfigured) {
                _state.value = AlbumListUiState(isLoading = false, error = "Server not configured")
                return@launch
            }
            val client = SubsonicApiClient(config).also { apiClient = it }
            runCatching {
                client.getAlbumList2(type = listType, size = 50)
            }.onSuccess { albums ->
                _state.value = AlbumListUiState(isLoading = false, albums = albums)
            }.onFailure { e ->
                Timber.tag("[AlbumList]").e(e, "Failed to load album list type=%s", listType)
                _state.value = AlbumListUiState(isLoading = false, error = e.message ?: "Failed to load")
            }
        }
    }

    fun getCoverArtUrl(coverArtId: String, size: Int = 300): String? =
        apiClient?.getCoverArtUrl(coverArtId, size)
}

class AlbumListViewModelFactory(
    private val context: Context,
    private val listType: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val app = context.applicationContext as TorstenApp
        return AlbumListViewModel(listType, ServerConfigStore(app)) as T
    }
}
