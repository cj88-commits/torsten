package com.torsten.app.ui.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.torsten.app.TorstenApp
import com.torsten.app.data.api.SubsonicApiClient
import com.torsten.app.data.recommendation.ArtistTopTracksRepository
import com.torsten.app.data.api.dto.AlbumDto
import com.torsten.app.data.api.dto.ArtistDto
import com.torsten.app.data.api.dto.GenreDto
import com.torsten.app.data.api.dto.SongDto
import com.torsten.app.data.datastore.RecentSearchesStore
import com.torsten.app.data.datastore.ServerConfig
import com.torsten.app.data.datastore.ServerConfigStore
import com.torsten.app.data.db.dao.SongDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

enum class SearchFilter { All, Tracks, Artists, Albums }

data class SearchUiState(
    val isLoading: Boolean = false,
    val tracks: List<SongDto> = emptyList(),
    val albums: List<AlbumDto> = emptyList(),
    val artists: List<ArtistDto> = emptyList(),
    val error: String? = null,
    /** True once at least one search has completed (distinguishes empty results from idle). */
    val hasSearched: Boolean = false,
    val activeFilter: SearchFilter = SearchFilter.All,
)

@OptIn(FlowPreview::class)
class SearchViewModel(
    private val configStore: ServerConfigStore,
    private val recentSearchesStore: RecentSearchesStore,
    private val artistTopTracksRepository: ArtistTopTracksRepository,
    private val songDao: SongDao,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow(SearchUiState())
    val results: StateFlow<SearchUiState> = _results.asStateFlow()

    val recentSearches: StateFlow<List<String>> = recentSearchesStore.recentSearches
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _genres = MutableStateFlow<List<GenreDto>>(emptyList())
    val genres: StateFlow<List<GenreDto>> = _genres.asStateFlow()

    /** Retained once a successful API call establishes the client. */
    private var apiClient: SubsonicApiClient? = null

    internal val albumQueueBuilder = AlbumQueueBuilder(songDao) { apiClient }

    init {
        loadGenres()

        viewModelScope.launch {
            _query
                .debounce(300L)
                .collect { q ->
                    if (q.isBlank()) {
                        _results.value = SearchUiState()
                    } else {
                        performSearch(q)
                    }
                }
        }
    }

    fun setQuery(query: String) {
        _query.value = query
        // Show loading immediately so the UI reacts before debounce fires
        if (query.isNotBlank()) {
            _results.value = _results.value.copy(isLoading = true, error = null)
        }
    }

    fun clearQuery() {
        _query.value = ""
        _results.value = SearchUiState()
    }

    fun setFilter(filter: SearchFilter) {
        _results.value = _results.value.copy(activeFilter = filter)
    }

    fun removeRecentSearch(query: String) {
        viewModelScope.launch { recentSearchesStore.remove(query) }
    }

    fun clearRecentSearches() {
        viewModelScope.launch { recentSearchesStore.clearAll() }
    }

    /** Tap on a recent search chip — restores the query and triggers a search. */
    fun selectRecentSearch(query: String) {
        _query.value = query
    }

    fun getServerConfig(): Flow<ServerConfig> = configStore.serverConfig

    fun getCoverArtUrl(coverArtId: String, size: Int = 300): String? =
        apiClient?.getCoverArtUrl(coverArtId, size)

    /**
     * Fetches all tracks for [song.albumId] from Room, sorts them, and returns the full album
     * list together with the index of [song] within that list.  The caller passes both to
     * [playFromSongDtos] so Media3 starts at the tapped position while keeping earlier tracks
     * accessible as history — identical to the Album Detail behaviour.
     *
     * Falls back to ([song], 0) when the album is not in the local catalogue.
     */
    suspend fun buildAlbumQueue(song: SongDto): Pair<List<SongDto>, Int> =
        albumQueueBuilder.build(song)

    // ─────────────────────────────────────────────────────────────────────────

    private fun loadGenres() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val config = configStore.serverConfig.first()
                if (!config.isConfigured) return@launch
                val client = SubsonicApiClient(config).also { apiClient = it }
                _genres.value = client.getGenres()
            }.onFailure { e ->
                Timber.tag("[Search]").w(e, "Failed to load genres")
            }
        }
    }

    private suspend fun performSearch(query: String) {
        _results.value = SearchUiState(isLoading = true)
        val config = configStore.serverConfig.first()
        if (!config.isConfigured) {
            _results.value = SearchUiState(error = "Server not configured")
            return
        }
        runCatching {
            val client = SubsonicApiClient(config).also { apiClient = it }
            val r = client.search(query, songCount = 25, albumCount = 10, artistCount = 10)
            _results.value = SearchUiState(
                isLoading = false,
                tracks = r.tracks,
                albums = r.albums,
                artists = r.artists,
                hasSearched = true,
                activeFilter = SearchFilter.All,
            )
            // Persist the query as a recent search
            recentSearchesStore.add(query)
            // Prefetch top tracks when results narrow to a single artist
            if (r.artists.size == 1) {
                val artist = r.artists.first()
                Timber.tag("[ArtistTop]").d("prefetch trigger=search artistId='%s' artistName='%s'", artist.id, artist.name)
                artistTopTracksRepository.prefetchIfNeeded(artist.id, artist.name)
            }
            Timber.tag("[Search]").d(
                "search3('%s') → %d tracks, %d albums, %d artists",
                query, r.tracks.size, r.albums.size, r.artists.size,
            )
            r.artists.forEach { artist ->
                Timber.tag("[Search]").d("  artist id=%s name='%s' coverArt=%s", artist.id, artist.name, artist.coverArt)
            }
        }.onFailure { e ->
            Timber.tag("[Search]").e(e, "search3 failed for query '%s'", query)
            _results.value = SearchUiState(error = e.message ?: "Search failed", hasSearched = true)
        }
    }
}

class SearchViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val app = context.applicationContext as TorstenApp
        return SearchViewModel(
            configStore = ServerConfigStore(app),
            recentSearchesStore = RecentSearchesStore(app),
            artistTopTracksRepository = app.artistTopTracksRepository,
            songDao = app.database.songDao(),
        ) as T
    }
}
