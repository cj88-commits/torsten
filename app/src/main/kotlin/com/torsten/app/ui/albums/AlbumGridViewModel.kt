package com.torsten.app.ui.albums

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.torsten.app.TorstenApp
import com.torsten.app.data.api.SubsonicApiClient
import com.torsten.app.data.db.AppDatabase
import com.torsten.app.data.db.entity.AlbumEntity
import com.torsten.app.data.db.entity.DownloadState
import com.torsten.app.data.db.entity.RecentlyPlayedEntity
import com.torsten.app.data.datastore.ServerConfigStore
import com.torsten.app.data.network.ConnectivityMonitor
import com.torsten.app.data.repository.SyncErrorType
import com.torsten.app.data.repository.SyncRepository
import com.torsten.app.data.repository.SyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

// ─── Grid item model ──────────────────────────────────────────────────────────

sealed class AlbumGridItem {
    /** Full-width section divider label (letter for A–Z, decade for By Year). */
    data class SectionHeader(val label: String) : AlbumGridItem()
    data class Album(val album: AlbumEntity) : AlbumGridItem()
}

// ─── UI state ────────────────────────────────────────────────────────────────

data class AlbumGridUiState(
    val gridItems: List<AlbumGridItem> = emptyList(),
    val syncState: SyncState = SyncState.IDLE,
    val isOnline: Boolean = true,
) {
    val hasAlbums: Boolean get() = gridItems.any { it is AlbumGridItem.Album }
}

// ─── ViewModel ───────────────────────────────────────────────────────────────

class AlbumGridViewModel(
    private val db: AppDatabase,
    private val syncRepository: SyncRepository,
    private val connectivityMonitor: ConnectivityMonitor,
    private val configStore: ServerConfigStore,
) : ViewModel() {

    private val _sortOrder = MutableStateFlow(AlbumSortOrder.A_Z)
    val sortOrder: StateFlow<AlbumSortOrder> = _sortOrder

    val uiState: StateFlow<AlbumGridUiState> = combine(
        // Strip downloadProgress before diffing: progress ticks on every chunk written, which
        // would otherwise rebuild the entire grid on every download update. Only downloadState
        // transitions (NONE→QUEUED→DOWNLOADING→COMPLETE) need to trigger a grid rebuild.
        db.albumDao().observeAll()
            .map { albums -> albums.map { it.copy(downloadProgress = 0) } }
            .distinctUntilChanged(),
        db.recentlyPlayedDao().observeRecent().distinctUntilChanged(),
        _sortOrder,
        syncRepository.syncState,
        connectivityMonitor.isOnline,
    ) { albums, recentlyPlayed, sortOrder, syncState, isOnline ->
        AlbumGridUiState(
            gridItems = try {
                buildGridItems(albums, recentlyPlayed, sortOrder)
            } catch (e: Exception) {
                Timber.tag("[UI]").e(e, "buildGridItems crashed for sort=%s", sortOrder)
                emptyList()
            },
            syncState = syncState,
            isOnline = isOnline,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AlbumGridUiState(),
    )

    private val _snackbarEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarEvent: SharedFlow<String> = _snackbarEvent.asSharedFlow()

    private var apiClient: SubsonicApiClient? = null

    private val _isApiClientReady = MutableStateFlow(false)
    val isApiClientReady: StateFlow<Boolean> = _isApiClientReady.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val config = configStore.serverConfig.first()
            if (config.isConfigured) {
                apiClient = SubsonicApiClient(config)
            }
            _isApiClientReady.value = true
        }
        syncRepository.triggerSync(force = false)

        viewModelScope.launch {
            syncRepository.syncError.collect { errorType ->
                val message = when (errorType) {
                    SyncErrorType.TIMEOUT -> "Connection timed out — check your server"
                    SyncErrorType.AUTH_FAILED -> "Authentication failed — check Settings"
                    SyncErrorType.SERVER_ERROR -> "Server error — try again later"
                    SyncErrorType.NETWORK_ERROR -> null
                }
                if (message != null) _snackbarEvent.tryEmit(message)
            }
        }
    }

    fun refresh() = syncRepository.triggerSync(force = true)

    fun setSortOrder(order: AlbumSortOrder) {
        _sortOrder.value = order
    }

    // ─── Scroll position (firstVisibleItemIndex, firstVisibleItemScrollOffset) ─

    private val scrollPositions = mutableMapOf<AlbumSortOrder, Pair<Int, Int>>()

    fun saveScrollPosition(order: AlbumSortOrder, index: Int, offset: Int) {
        scrollPositions[order] = index to offset
    }

    fun getScrollPosition(order: AlbumSortOrder): Pair<Int, Int> =
        scrollPositions[order] ?: (0 to 0)

    fun getCoverArtUrl(coverArtId: String, size: Int = 300): String? =
        apiClient?.getCoverArtUrl(coverArtId, size)

    private fun buildGridItems(
        albums: List<AlbumEntity>,
        recentlyPlayed: List<RecentlyPlayedEntity>,
        order: AlbumSortOrder,
    ): List<AlbumGridItem> = when (order) {

        AlbumSortOrder.A_Z -> {
            buildList {
                var lastLetter = ""
                // Sort by (sectionLetter, title) rather than title alone.
                // Sorting purely by title.lowercase() can place Unicode titles such as
                // "½ …" (code point 189) after 'z' (122) even though they map to "#",
                // producing a second non-contiguous "#" group and a duplicate header key
                // that crashes LazyGrid with IllegalArgumentException.
                // "#" < "A" … "Z" in Unicode so all non-letter albums naturally sort first.
                val sorted = albums.sortedWith(
                    compareBy(
                        { album ->
                            album.title.trim().firstOrNull()?.uppercaseChar()
                                ?.takeIf { it.isLetter() }?.toString() ?: "#"
                        },
                        { it.title.lowercase() },
                    )
                )
                for (album in sorted) {
                    val letter = album.title.trim().firstOrNull()?.uppercaseChar()
                        ?.takeIf { it.isLetter() }?.toString() ?: "#"
                    if (letter != lastLetter) {
                        add(AlbumGridItem.SectionHeader(letter))
                        lastLetter = letter
                    }
                    add(AlbumGridItem.Album(album))
                }
            }
        }

        AlbumSortOrder.BY_YEAR -> {
            val sorted = albums.sortedWith(
                compareBy<AlbumEntity> { it.year ?: Int.MAX_VALUE }.thenBy { it.title.lowercase() },
            )
            buildList {
                var lastDecade = ""
                for (album in sorted) {
                    val decade = album.year?.let { "${(it / 10) * 10}s" } ?: "Unknown"
                    if (decade != lastDecade) {
                        add(AlbumGridItem.SectionHeader(decade))
                        lastDecade = decade
                    }
                    add(AlbumGridItem.Album(album))
                }
            }
        }

        AlbumSortOrder.RECENTLY_PLAYED -> {
            val recentIds = recentlyPlayed.map { it.albumId }
            val recentSet = recentIds.toHashSet()
            albums.filter { it.id in recentSet }
                .sortedBy { recentIds.indexOf(it.id) }
                .map { AlbumGridItem.Album(it) }
        }

        AlbumSortOrder.STARRED ->
            albums.filter { it.starred }
                .sortedBy { it.title.lowercase() }
                .map { AlbumGridItem.Album(it) }

        AlbumSortOrder.DOWNLOADED ->
            albums.filter { it.downloadState == DownloadState.COMPLETE }
                .sortedBy { it.title.lowercase() }
                .map { AlbumGridItem.Album(it) }
    }
}

class AlbumGridViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val app = context.applicationContext as TorstenApp
        return AlbumGridViewModel(
            db = app.database,
            syncRepository = app.syncRepository,
            connectivityMonitor = app.connectivityMonitor,
            configStore = ServerConfigStore(context.applicationContext),
        ) as T
    }
}
