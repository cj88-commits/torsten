package com.torsten.app.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.torsten.app.TorstenApp
import com.torsten.app.data.api.SubsonicApiClient
import com.torsten.app.data.api.dto.AlbumDto
import com.torsten.app.data.datastore.ServerConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

data class HomeUiState(
    val isLoading: Boolean = true,
    val recentlyPlayed: List<AlbumDto> = emptyList(),
    val newAdditions: List<AlbumDto> = emptyList(),
    val mostPlayed: List<AlbumDto> = emptyList(),
    val error: String? = null,
)

class HomeViewModel(
    private val configStore: ServerConfigStore,
) : ViewModel() {

    private var apiClient: SubsonicApiClient? = null

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        loadFeed()
    }

    fun loadFeed() {
        viewModelScope.launch {
            _state.value = HomeUiState(isLoading = true)
            val config = configStore.serverConfig.first()
            if (!config.isConfigured) {
                _state.value = HomeUiState(isLoading = false, error = "Server not configured")
                return@launch
            }
            val client = SubsonicApiClient(config).also { apiClient = it }
            try {
                coroutineScope {
                    val recent   = async(Dispatchers.IO) { client.getAlbumList2("recent",   10) }
                    val newest   = async(Dispatchers.IO) { client.getAlbumList2("newest",   10) }
                    val frequent = async(Dispatchers.IO) { client.getAlbumList2("frequent", 10) }
                    _state.value = HomeUiState(
                        isLoading      = false,
                        recentlyPlayed = recent.await(),
                        newAdditions   = newest.await(),
                        mostPlayed     = frequent.await(),
                    )
                }
            } catch (e: Exception) {
                Timber.tag("[UI]").e(e, "Home feed fetch failed")
                _state.value = HomeUiState(
                    isLoading = false,
                    error = e.message ?: "Failed to load feed",
                )
            }
        }
    }

    fun getCoverArtUrl(coverArtId: String, size: Int = 300): String? =
        apiClient?.getCoverArtUrl(coverArtId, size)
}

class HomeViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val app = context.applicationContext as TorstenApp
        return HomeViewModel(
            configStore = ServerConfigStore(app),
        ) as T
    }
}
