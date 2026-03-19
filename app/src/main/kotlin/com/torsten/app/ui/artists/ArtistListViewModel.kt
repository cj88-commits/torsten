package com.torsten.app.ui.artists

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.torsten.app.TorstenApp
import com.torsten.app.data.api.SubsonicApiClient
import com.torsten.app.data.datastore.ServerConfigStore
import com.torsten.app.data.db.AppDatabase
import com.torsten.app.data.db.entity.ArtistEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Returns the sort key for an artist name, stripping a leading "The " article. */
fun artistSortKey(name: String): String {
    val trimmed = name.trim()
    return if (trimmed.startsWith("The ", ignoreCase = true) && trimmed.length > 4) {
        trimmed.substring(4)
    } else {
        trimmed
    }
}

/** Returns the index letter (A–Z, or # for non-alpha) for fast-scroll. */
fun artistIndexLetter(name: String): String {
    val first = artistSortKey(name).firstOrNull()?.uppercaseChar() ?: '#'
    return if (first.isLetter()) first.toString() else "#"
}

class ArtistListViewModel(
    private val db: AppDatabase,
    configStore: ServerConfigStore,
) : ViewModel() {

    private var apiClient: SubsonicApiClient? = null

    // Tracks artist IDs for which a getArtistInfo2 fetch is already in-flight this session.
    private val _fetchingIds = MutableStateFlow<Set<String>>(emptySet())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val config = configStore.serverConfig.first()
            if (config.isConfigured) apiClient = SubsonicApiClient(config)
        }
    }

    /**
     * Sorted list of artists. Each artist's [ArtistEntity.artistImageUrl] is the sole image
     * source — never album cover art. Null means "not yet fetched"; empty string means "fetched
     * but no image available". A background fetch is kicked off for any artist with a null URL.
     */
    val artists: StateFlow<List<ArtistEntity>> = db.artistDao().observeAll()
        .map { allArtists ->
            val sorted = allArtists.sortedBy { artistSortKey(it.name).lowercase() }
            val unfetched = sorted.filter { it.artistImageUrl == null }
            if (unfetched.isNotEmpty()) fetchMissingImages(unfetched)
            sorted
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private fun fetchMissingImages(artists: List<ArtistEntity>) {
        viewModelScope.launch(Dispatchers.IO) {
            val client = apiClient ?: return@launch
            val alreadyFetching = _fetchingIds.value
            val toFetch = artists.filter { it.id !in alreadyFetching }
            if (toFetch.isEmpty()) return@launch

            _fetchingIds.value = alreadyFetching + toFetch.map { it.id }.toSet()
            toFetch.forEach { artist ->
                val url = runCatching { client.getArtistInfo(artist.id) }.getOrNull()
                // Store the URL, or "" to mark "fetched but no image" and avoid re-fetching.
                db.artistDao().updateArtistImageUrl(artist.id, url ?: "")
            }
        }
    }
}

class ArtistListViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val app = context.applicationContext as TorstenApp
        return ArtistListViewModel(
            db = app.database,
            configStore = ServerConfigStore(app),
        ) as T
    }
}
