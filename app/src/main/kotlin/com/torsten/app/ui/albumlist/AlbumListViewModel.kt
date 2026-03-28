package com.torsten.app.ui.albumlist

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.torsten.app.TorstenApp
import com.torsten.app.data.api.SubsonicApiClient
import com.torsten.app.data.api.dto.AlbumDto
import com.torsten.app.data.datastore.ServerConfigStore
import com.torsten.app.ui.home.ForYouComposer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
            if (listType == "forYou") {
                loadForYou(client)
            } else {
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
    }

    private suspend fun loadForYou(client: SubsonicApiClient) {
        runCatching {
            coroutineScope {
                val recent   = async(Dispatchers.IO) { client.getAlbumList2("recent",   20) }
                val frequent = async(Dispatchers.IO) { client.getAlbumList2("frequent", 20) }
                val newest   = async(Dispatchers.IO) { client.getAlbumList2("newest",   10) }
                val starred  = async(Dispatchers.IO) {
                    runCatching { client.getAlbumList2("starred", 10) }.getOrElse { emptyList() }
                }
                ForYouComposer.compose(
                    recent   = recent.await(),
                    frequent = frequent.await(),
                    starred  = starred.await(),
                    newest   = newest.await(),
                    maxItems = 50,
                )
            }
        }.onSuccess { albums ->
            _state.value = AlbumListUiState(isLoading = false, albums = albums)
        }.onFailure { e ->
            Timber.tag("[AlbumList]").e(e, "Failed to load For You list")
            _state.value = AlbumListUiState(isLoading = false, error = e.message ?: "Failed to load")
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
