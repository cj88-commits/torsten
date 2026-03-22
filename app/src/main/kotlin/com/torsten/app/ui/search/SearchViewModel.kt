package com.torsten.app.ui.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.torsten.app.TorstenApp
import com.torsten.app.data.api.SubsonicApiClient
import com.torsten.app.data.api.dto.AlbumDto
import com.torsten.app.data.api.dto.ArtistDto
import com.torsten.app.data.api.dto.GenreDto
import com.torsten.app.data.api.dto.SongDto
import com.torsten.app.data.datastore.RecentSearchesStore
import com.torsten.app.data.datastore.ServerConfig
import com.torsten.app.data.datastore.ServerConfigStore
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

data class SearchUiState(
    val isLoading: Boolean = false,
    val tracks: List<SongDto> = emptyList(),
    val albums: List<AlbumDto> = emptyList(),
    val artists: List<ArtistDto> = emptyList(),
    val error: String? = null,
    /** True once at least one search has completed (distinguishes empty results from idle). */
    val hasSearched: Boolean = false,
)

@OptIn(FlowPreview::class)
class SearchViewModel(
    private val configStore: ServerConfigStore,
    private val recentSearchesStore: RecentSearchesStore,
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
            val r = client.search(query, songCount = 3, albumCount = 3, artistCount = 3)
            _results.value = SearchUiState(
                isLoading = false,
                tracks = r.tracks,
                albums = r.albums,
                artists = r.artists,
                hasSearched = true,
            )
            // Persist the query as a recent search
            recentSearchesStore.add(query)
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
        ) as T
    }
}
