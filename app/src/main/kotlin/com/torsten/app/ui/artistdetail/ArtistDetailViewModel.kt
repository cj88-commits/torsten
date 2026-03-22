package com.torsten.app.ui.artistdetail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.torsten.app.TorstenApp
import com.torsten.app.data.api.SubsonicApiClient
import com.torsten.app.data.api.dto.SongDto
import com.torsten.app.data.datastore.ServerConfig
import com.torsten.app.data.datastore.ServerConfigStore
import com.torsten.app.data.db.AppDatabase
import com.torsten.app.data.db.entity.AlbumEntity
import com.torsten.app.data.db.entity.ArtistEntity
import com.torsten.app.data.network.ConnectivityMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ArtistDetailViewModel(
    private val db: AppDatabase,
    private val artistId: String,
    private val configStore: ServerConfigStore,
    private val connectivityMonitor: ConnectivityMonitor,
) : ViewModel() {

    val artist: StateFlow<ArtistEntity?> = db.artistDao()
        .observeById(artistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Sorted year DESC — newest albums first
    val albums: StateFlow<List<AlbumEntity>> = db.albumDao()
        .observeByArtist(artistId)
        .map { list -> list.sortedByDescending { it.year ?: 0 } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _artistLargeImageUrl = MutableStateFlow<String?>(null)
    val artistLargeImageUrl: StateFlow<String?> = _artistLargeImageUrl.asStateFlow()

    private val _topTracks = MutableStateFlow<List<SongDto>>(emptyList())
    val topTracks: StateFlow<List<SongDto>> = _topTracks.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var apiClient: SubsonicApiClient? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val config = configStore.serverConfig.first()
            if (config.isConfigured) {
                apiClient = SubsonicApiClient(config)
                coroutineScope {
                    val artistInfoJob = async {
                        runCatching { apiClient?.getArtistInfo(artistId) }.getOrNull()
                    }
                    val topSongsJob = async {
                        val artistName = db.artistDao().observeById(artistId).first()?.name
                            ?: return@async emptyList<SongDto>()
                        runCatching { apiClient?.getTopSongs(artistName, 5).orEmpty() }
                            .getOrElse { emptyList() }
                    }
                    _artistLargeImageUrl.value = artistInfoJob.await()
                    _topTracks.value = topSongsJob.await()
                }
            }
            _isLoading.value = false
        }
    }

    val isOnline: StateFlow<Boolean> get() = connectivityMonitor.isOnline

    fun getCoverArtUrl(coverArtId: String, size: Int = 300): String? =
        apiClient?.getCoverArtUrl(coverArtId, size)

    fun getServerConfig(): Flow<ServerConfig> = configStore.serverConfig
}

class ArtistDetailViewModelFactory(
    private val context: Context,
    private val artistId: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val app = context.applicationContext as TorstenApp
        return ArtistDetailViewModel(
            db = app.database,
            artistId = artistId,
            configStore = ServerConfigStore(context.applicationContext),
            connectivityMonitor = app.connectivityMonitor,
        ) as T
    }
}
