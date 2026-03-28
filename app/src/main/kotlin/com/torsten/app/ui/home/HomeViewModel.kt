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
    val continueListening: AlbumDto? = null,
    val recentlyPlayed: List<AlbumDto> = emptyList(),
    val newAdditions: List<AlbumDto> = emptyList(),
    val mostPlayed: List<AlbumDto> = emptyList(),
    val forYou: List<AlbumDto> = emptyList(),
    val error: String? = null,
)

/**
 * Pure derivation of all Home section lists from raw API results.
 *
 * [continueListening] is the most-recently-played album rendered as a hero
 * card. [recentlyPlayed] is the horizontal row immediately below — it always
 * starts at index 1 so the hero is never shown twice on the same screen.
 *
 * [screenAlbumIds] is the union of every album ID visible in any section,
 * used by [ForYouComposer] to guarantee ForYou never repeats an album that
 * is already on screen.
 *
 * Extracted as an internal top-level function so it can be unit-tested
 * without Android framework dependencies.
 */
internal data class HomeSections(
    val continueListening: AlbumDto?,
    val recentlyPlayed: List<AlbumDto>,
    val newAdditions: List<AlbumDto>,
    val mostPlayed: List<AlbumDto>,
    val screenAlbumIds: Set<String>,
    val screenArtists: Set<String>,
)

internal fun buildHomeSections(
    recentList: List<AlbumDto>,
    frequentList: List<AlbumDto>,
    newestList: List<AlbumDto>,
): HomeSections {
    val continueListening = recentList.firstOrNull()
    // Drop the hero so it is not shown a second time in the Recently Played row.
    val recentlyPlayed    = recentList.drop(1).take(10)
    val newAdditions      = newestList
    val mostPlayed        = frequentList.take(10)

    val screenAlbumIds = (
        listOfNotNull(continueListening?.id) +
        recentlyPlayed.map { it.id } +
        newAdditions.map   { it.id } +
        mostPlayed.map     { it.id }
    ).toSet()

    // Artists already visible above the For You row (used for R5 in ForYouComposer).
    val screenArtists = recentList.take(6).mapNotNull { it.artistId }.toSet()

    return HomeSections(
        continueListening = continueListening,
        recentlyPlayed    = recentlyPlayed,
        newAdditions      = newAdditions,
        mostPlayed        = mostPlayed,
        screenAlbumIds    = screenAlbumIds,
        screenArtists     = screenArtists,
    )
}

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
                    val recent   = async(Dispatchers.IO) { client.getAlbumList2("recent",   20) }
                    val newest   = async(Dispatchers.IO) { client.getAlbumList2("newest",   10) }
                    val frequent = async(Dispatchers.IO) { client.getAlbumList2("frequent", 20) }
                    val starred  = async(Dispatchers.IO) {
                        runCatching { client.getAlbumList2("starred", 10) }.getOrElse { emptyList() }
                    }
                    val recentList   = recent.await()
                    val newestList   = newest.await()
                    val frequentList = frequent.await()
                    val starredList  = starred.await()

                    val sections = buildHomeSections(recentList, frequentList, newestList)

                    _state.value = HomeUiState(
                        isLoading         = false,
                        continueListening = sections.continueListening,
                        recentlyPlayed    = sections.recentlyPlayed,
                        newAdditions      = sections.newAdditions,
                        mostPlayed        = sections.mostPlayed,
                        forYou            = ForYouComposer.compose(
                            recent         = recentList,
                            frequent       = frequentList,
                            starred        = starredList,
                            newest         = newestList,
                            screenArtists  = sections.screenArtists,
                            screenAlbumIds = sections.screenAlbumIds,
                        ),
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
