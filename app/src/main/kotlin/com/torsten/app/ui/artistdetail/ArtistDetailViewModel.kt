package com.torsten.app.ui.artistdetail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.torsten.app.TorstenApp
import com.torsten.app.data.api.SubsonicApiClient
import com.torsten.app.data.datastore.ServerConfigStore
import com.torsten.app.data.db.AppDatabase
import com.torsten.app.data.db.entity.AlbumEntity
import com.torsten.app.data.db.entity.ArtistEntity
import com.torsten.app.data.network.ConnectivityMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ArtistDetailViewModel(
    db: AppDatabase,
    private val artistId: String,
    private val configStore: ServerConfigStore,
    private val connectivityMonitor: ConnectivityMonitor,
) : ViewModel() {

    val artist: StateFlow<ArtistEntity?> = db.artistDao()
        .observeById(artistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // observeByArtist is already sorted by year ASC, title ASC in the DAO
    val albums: StateFlow<List<AlbumEntity>> = db.albumDao()
        .observeByArtist(artistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _artistLargeImageUrl = MutableStateFlow<String?>(null)

    /** Large image URL from getArtistInfo2 (Last.fm / MusicBrainz). Null until loaded or if unavailable. */
    val artistLargeImageUrl: StateFlow<String?> = _artistLargeImageUrl.asStateFlow()

    private var apiClient: SubsonicApiClient? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val config = configStore.serverConfig.first()
            if (config.isConfigured) {
                apiClient = SubsonicApiClient(config)
                // Fire-and-forget: errors are swallowed inside getArtistInfo
                launch { _artistLargeImageUrl.value = apiClient?.getArtistInfo(artistId) }
            }
        }
    }

    val isOnline: StateFlow<Boolean> get() = connectivityMonitor.isOnline

    fun getCoverArtUrl(coverArtId: String, size: Int = 300): String? =
        apiClient?.getCoverArtUrl(coverArtId, size)
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
