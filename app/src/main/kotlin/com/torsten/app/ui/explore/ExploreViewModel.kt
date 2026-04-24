package com.torsten.app.ui.explore

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.torsten.app.TorstenApp
import com.torsten.app.data.api.SubsonicApiClient
import com.torsten.app.data.api.dto.AlbumDto
import com.torsten.app.data.api.dto.ArtistDto
import com.torsten.app.data.api.dto.SongDto
import com.torsten.app.data.datastore.ServerConfig
import com.torsten.app.data.datastore.ServerConfigStore
import com.torsten.app.data.db.AppDatabase
import com.torsten.app.data.db.entity.SongEntity
import com.torsten.app.data.recommendation.ExploreRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

sealed class ExploreUiState {
    data object Loading : ExploreUiState()
    data class Ready(
        val artist: ArtistDto,
        val album: AlbumDto,
        val topTracks: List<SongEntity>,
        val topTracksError: Boolean = false,
    ) : ExploreUiState()
    data class Error(val message: String) : ExploreUiState()
}

class ExploreViewModel(
    private val db: AppDatabase,
    private val configStore: ServerConfigStore,
    private val exploreRepository: ExploreRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ExploreUiState>(ExploreUiState.Loading)
    val uiState: StateFlow<ExploreUiState> = _uiState.asStateFlow()

    private var apiClient: SubsonicApiClient? = null

    init { load() }

    fun reroll() {
        _uiState.value = ExploreUiState.Loading
        load()
    }

    private fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val config = configStore.serverConfig.first()
                if (!config.isConfigured) {
                    _uiState.value = ExploreUiState.Error("Server not configured")
                    return@launch
                }
                apiClient = SubsonicApiClient(config)

                val payload = exploreRepository.awaitNext()
                if (payload == null) {
                    _uiState.value = ExploreUiState.Error("Failed to load explore data")
                    return@launch
                }
                _uiState.value = ExploreUiState.Ready(
                    artist = payload.artist,
                    album = payload.album,
                    topTracks = payload.topTracks,
                    topTracksError = payload.topTracksError,
                )
            } catch (e: Exception) {
                Timber.tag("[Explore]").e(e, "Failed to load explore data")
                _uiState.value = ExploreUiState.Error("Failed to load: ${e.message}")
            }
        }
    }

    fun getCoverArtUrl(id: String?, size: Int = 400): String? {
        id ?: return null
        return apiClient?.getCoverArtUrl(id, size)
    }

    fun getServerConfig(): Flow<ServerConfig> = configStore.serverConfig

    /** Builds playback queue: rank-1 track first, then tracks 2–10 shuffled. */
    suspend fun buildArtistQueue(tracks: List<SongEntity>): List<SongDto> = withContext(Dispatchers.IO) {
        if (tracks.isEmpty()) return@withContext emptyList()
        val ordered = if (tracks.size > 1) listOf(tracks.first()) + tracks.drop(1).shuffled() else tracks
        ordered.mapNotNull { song ->
            val album = db.albumDao().getById(song.albumId) ?: return@mapNotNull null
            SongDto(
                id = song.id,
                title = song.title,
                album = album.title,
                albumId = song.albumId,
                artist = album.artistName,
                artistId = song.artistId,
                track = song.trackNumber,
                discNumber = song.discNumber,
                duration = song.duration,
                bitRate = song.bitRate,
                suffix = song.suffix,
                contentType = song.contentType,
                coverArt = album.coverArtId,
                starred = if (song.starred) "true" else null,
            )
        }
    }

    /** Fetches album tracks from the API, sorted by disc/track order. */
    suspend fun getAlbumSongs(albumId: String): List<SongDto> = withContext(Dispatchers.IO) {
        val config = configStore.serverConfig.first()
        if (!config.isConfigured) return@withContext emptyList()
        SubsonicApiClient(config).getAlbum(albumId)
            .song.orEmpty()
            .sortedWith(compareBy({ it.discNumber }, { it.track }))
    }
}

class ExploreViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val app = context.applicationContext as TorstenApp
        return ExploreViewModel(
            db = app.database,
            configStore = ServerConfigStore(context.applicationContext),
            exploreRepository = app.exploreRepository,
        ) as T
    }
}
