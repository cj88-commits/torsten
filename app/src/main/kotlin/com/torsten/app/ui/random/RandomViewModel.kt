package com.torsten.app.ui.random

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.torsten.app.TorstenApp
import com.torsten.app.data.api.SubsonicApiClient
import com.torsten.app.data.datastore.ServerConfigStore
import com.torsten.app.data.db.AppDatabase
import com.torsten.app.data.db.entity.AlbumEntity
import com.torsten.app.data.db.entity.DownloadState
import com.torsten.app.data.network.ConnectivityMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class RandomViewModel(
    private val db: AppDatabase,
    private val configStore: ServerConfigStore,
    private val connectivityMonitor: ConnectivityMonitor,
) : ViewModel() {

    private var apiClient: SubsonicApiClient? = null

    private val _randomAlbums = MutableStateFlow<List<AlbumEntity>>(emptyList())
    val randomAlbums: StateFlow<List<AlbumEntity>> = _randomAlbums.asStateFlow()

    private val _isShuffling = MutableStateFlow(false)
    val isShuffling: StateFlow<Boolean> = _isShuffling.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val config = configStore.serverConfig.first()
            if (config.isConfigured) apiClient = SubsonicApiClient(config)
        }
        shuffle()
    }

    fun shuffle() {
        viewModelScope.launch(Dispatchers.IO) {
            _isShuffling.value = true
            val isOnline = connectivityMonitor.isOnline.value
            val all = db.albumDao().getAll()
            val pool = if (isOnline) all else all.filter { it.downloadState == DownloadState.COMPLETE }
            _randomAlbums.value = pool.shuffled().take(4)
            delay(300)
            _isShuffling.value = false
        }
    }

    fun getCoverArtUrl(coverArtId: String, size: Int = 300): String? =
        apiClient?.getCoverArtUrl(coverArtId, size)
}

class RandomViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val app = context.applicationContext as TorstenApp
        return RandomViewModel(
            db = app.database,
            configStore = ServerConfigStore(app),
            connectivityMonitor = app.connectivityMonitor,
        ) as T
    }
}
