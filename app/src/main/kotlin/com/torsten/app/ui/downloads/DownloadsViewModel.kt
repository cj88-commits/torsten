package com.torsten.app.ui.downloads

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.torsten.app.TorstenApp
import com.torsten.app.data.api.SubsonicApiClient
import com.torsten.app.data.datastore.DownloadedPlaylistInfo
import com.torsten.app.data.datastore.DownloadedPlaylistStore
import com.torsten.app.data.datastore.ServerConfigStore
import com.torsten.app.data.db.AppDatabase
import com.torsten.app.data.db.entity.AlbumEntity
import com.torsten.app.data.db.entity.DownloadState
import com.torsten.app.data.download.DownloadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DownloadsViewModel(
    private val db: AppDatabase,
    private val downloadRepository: DownloadRepository,
    private val configStore: ServerConfigStore,
    private val downloadedPlaylistStore: DownloadedPlaylistStore,
) : ViewModel() {

    private var apiClient: SubsonicApiClient? = null

    private val allAlbums: StateFlow<List<AlbumEntity>> = db.albumDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeDownloads: StateFlow<List<AlbumEntity>> = allAlbums
        .map { albums -> albums.filter { it.downloadState == DownloadState.QUEUED || it.downloadState == DownloadState.DOWNLOADING } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val completedDownloads: StateFlow<List<AlbumEntity>> = allAlbums
        .map { albums ->
            albums.filter { it.downloadState == DownloadState.COMPLETE }
                .sortedByDescending { it.downloadedAt }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val failedDownloads: StateFlow<List<AlbumEntity>> = allAlbums
        .map { albums -> albums.filter { it.downloadState == DownloadState.FAILED || it.downloadState == DownloadState.PARTIAL } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val downloadedPlaylists: StateFlow<List<DownloadedPlaylistInfo>> =
        downloadedPlaylistStore.downloadedPlaylists
            .map { it.sortedByDescending { p -> p.downloadedAt } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val config = configStore.serverConfig.first()
            if (config.isConfigured) apiClient = SubsonicApiClient(config)
        }
    }

    fun getCoverArtUrl(coverArtId: String, size: Int = 150): String? =
        apiClient?.getCoverArtUrl(coverArtId, size)

    fun cancelDownload(albumId: String) {
        viewModelScope.launch { downloadRepository.cancelDownload(albumId) }
    }

    fun deleteDownload(albumId: String) {
        viewModelScope.launch { downloadRepository.cancelDownload(albumId) }
    }

    fun retryDownload(albumId: String) {
        viewModelScope.launch { downloadRepository.enqueueDownload(albumId) }
    }

    fun deletePlaylistDownload(playlistId: String, songIds: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            downloadRepository.deleteTrackDownloads(songIds)
            downloadedPlaylistStore.remove(playlistId)
        }
    }
}

class DownloadsViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val app = context.applicationContext as TorstenApp
        return DownloadsViewModel(
            db = app.database,
            downloadRepository = app.downloadRepository,
            configStore = ServerConfigStore(app),
            downloadedPlaylistStore = app.downloadedPlaylistStore,
        ) as T
    }
}
