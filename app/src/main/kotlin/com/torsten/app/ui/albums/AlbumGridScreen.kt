package com.torsten.app.ui.albums

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.torsten.app.data.repository.SyncState
import com.torsten.app.ui.common.AlbumCardItem
import com.torsten.app.ui.common.DarkBackground
import com.torsten.app.ui.common.LibraryTab
import com.torsten.app.ui.common.LibraryTabRow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumGridScreen(
    viewModel: AlbumGridViewModel,
    onAlbumClick: (albumId: String, albumTitle: String) -> Unit,
    onNavigateToArtists: () -> Unit = {},
    onNavigateToPlaylists: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()
    val isApiClientReady by viewModel.isApiClientReady.collectAsStateWithLifecycle()
    val isActivelySyncing = state.syncState == SyncState.SYNCING
    val isSyncing = !isApiClientReady || isActivelySyncing || state.syncState == SyncState.IDLE
    val syncFailed = state.syncState == SyncState.ERROR && state.hasAlbums

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Grid state hoisted here so SortBar can observe the visible item for "Jump to" label
    val gridState = rememberLazyGridState()

    // Restore the saved scroll position for this sort order.
    // When sort changes this scrolls to the top (saved position is 0,0 for a sort never visited).
    // When navigating back and the composable was recomposed, this restores where the user was.
    // The index is clamped to the current list size: Room may have emitted a shorter list since
    // the position was saved (e.g. a sync removed albums), which would make scrollToItem crash.
    LaunchedEffect(sortOrder) {
        val (index, offset) = viewModel.getScrollPosition(sortOrder)
        val itemCount = state.gridItems.size
        if (itemCount == 0) return@LaunchedEffect
        val safeIndex = index.coerceIn(0, itemCount - 1)
        try {
            gridState.scrollToItem(safeIndex, if (safeIndex == index) offset else 0)
        } catch (e: Exception) {
            Timber.tag("[UI]").e(e, "scrollToItem failed: index=%d itemCount=%d", safeIndex, itemCount)
        }
    }

    // Persist scroll position into the ViewModel as the user scrolls.
    // drop(1) skips the snapshot emitted before the restoration above takes effect,
    // so we never accidentally overwrite a saved position with a stale pre-restore value.
    LaunchedEffect(gridState, sortOrder) {
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .drop(1)
            .collect { (index, offset) ->
                viewModel.saveScrollPosition(sortOrder, index, offset)
            }
    }

    // Section header indices: label → flat item index in gridItems
    val sectionHeaderIndices by remember {
        derivedStateOf {
            state.gridItems.mapIndexedNotNull { index, item ->
                if (item is AlbumGridItem.SectionHeader) item.label to index else null
            }.toMap()
        }
    }

    // Current section label derived from the topmost visible item
    val showJumpTo = sortOrder == AlbumSortOrder.A_Z || sortOrder == AlbumSortOrder.BY_YEAR
    val currentSectionLabel by remember {
        derivedStateOf {
            val idx = gridState.firstVisibleItemIndex
            val items = state.gridItems
            var label = ""
            for (i in 0..idx.coerceAtMost(items.lastIndex.coerceAtLeast(0))) {
                val item = items.getOrNull(i) ?: break
                if (item is AlbumGridItem.SectionHeader) label = item.label
            }
            label
        }
    }

    // Bottom sheet for jump-to navigation
    var showJumpSheet by remember { mutableStateOf(false) }
    val jumpSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) {
        viewModel.snackbarEvent.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    if (showJumpSheet) {
        val labels = remember(sectionHeaderIndices, sortOrder) {
            when (sortOrder) {
                AlbumSortOrder.A_Z -> sectionHeaderIndices.keys
                    .sortedWith(compareBy { if (it == "#") "\uFFFF" else it })
                AlbumSortOrder.BY_YEAR -> sectionHeaderIndices.keys
                    .sortedBy { it.trimEnd('s').toIntOrNull() ?: Int.MAX_VALUE }
                else -> emptyList()
            }
        }
        ModalBottomSheet(
            onDismissRequest = { showJumpSheet = false },
            sheetState = jumpSheetState,
            containerColor = Color(0xFF1A1A1A),
        ) {
            Text(
                text = "Jump to",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(64.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.padding(bottom = 24.dp),
            ) {
                items(labels) { label ->
                    TextButton(
                        onClick = {
                            scope.launch {
                                jumpSheetState.hide()
                                showJumpSheet = false
                                // Re-read the index after hide() — the list may have changed
                                // during the suspension. Bounds-check before scrolling to avoid
                                // an IndexOutOfBoundsException if the list shrank.
                                val targetIndex = sectionHeaderIndices[label]
                                val itemCount = state.gridItems.size
                                if (targetIndex != null && targetIndex < itemCount) {
                                    try {
                                        gridState.animateScrollToItem(targetIndex)
                                    } catch (e: Exception) {
                                        Timber.tag("[UI]").e(
                                            e, "animateScrollToItem failed: index=%d itemCount=%d",
                                            targetIndex, itemCount,
                                        )
                                    }
                                } else if (targetIndex != null) {
                                    Timber.tag("[UI]").w(
                                        "Jump-to index %d out of range (size=%d) — skipping",
                                        targetIndex, itemCount,
                                    )
                                }
                            }
                        },
                    ) {
                        Text(
                            text = label,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }

    Scaffold(
        containerColor = DarkBackground,
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = Color(0xFF1A1A1A),
                    contentColor = Color.White,
                )
            }
        },
        topBar = {
            TopAppBar(
                title = { Text("Library", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A0A0A)),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (!state.isOnline) OfflineBanner()
            if (syncFailed) SyncFailedBanner(onRetry = { if (state.isOnline) viewModel.refresh() })

            LibraryTabRow(
                selected = LibraryTab.ALBUMS,
                onAlbumsClick = {},
                onArtistsClick = onNavigateToArtists,
                onPlaylistsClick = onNavigateToPlaylists,
            )

            when {
                // Filter-specific empty states — shown immediately without spinner
                !state.hasAlbums && !isActivelySyncing && isApiClientReady &&
                    sortOrder == AlbumSortOrder.RECENTLY_PLAYED -> {
                    FilterEmptyState(
                        icon = { Icon(Icons.Filled.MusicNote, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.White.copy(alpha = 0.3f)) },
                        message = "No recently played albums yet",
                        sortBar = {
                            SortBar(
                                sortOrder = sortOrder,
                                onSortOrderSelected = viewModel::setSortOrder,
                                jumpToLabel = null,
                                onJumpToClick = {},
                            )
                        },
                    )
                }
                !state.hasAlbums && !isActivelySyncing && isApiClientReady &&
                    sortOrder == AlbumSortOrder.STARRED -> {
                    FilterEmptyState(
                        icon = { Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.White.copy(alpha = 0.3f)) },
                        message = "No starred albums yet",
                        sortBar = {
                            SortBar(
                                sortOrder = sortOrder,
                                onSortOrderSelected = viewModel::setSortOrder,
                                jumpToLabel = null,
                                onJumpToClick = {},
                            )
                        },
                    )
                }
                !state.hasAlbums && !isActivelySyncing && isApiClientReady &&
                    sortOrder == AlbumSortOrder.DOWNLOADED -> {
                    FilterEmptyState(
                        icon = { Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.White.copy(alpha = 0.3f)) },
                        message = "No downloaded albums yet",
                        sortBar = {
                            SortBar(
                                sortOrder = sortOrder,
                                onSortOrderSelected = viewModel::setSortOrder,
                                jumpToLabel = null,
                                onJumpToClick = {},
                            )
                        },
                    )
                }

                !state.hasAlbums && isSyncing -> {
                    // SINGLE SPINNER — do not add additional loading indicators
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }

                !state.hasAlbums && state.syncState == SyncState.ERROR -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "Sync failed. Check your connection.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = viewModel::refresh) {
                                Text("Retry", color = Color.White)
                            }
                        }
                    }
                }

                !state.hasAlbums -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No albums found",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.6f),
                        )
                    }
                }

                else -> {
                    SortBar(
                        sortOrder = sortOrder,
                        onSortOrderSelected = viewModel::setSortOrder,
                        jumpToLabel = if (showJumpTo && currentSectionLabel.isNotEmpty()) currentSectionLabel else null,
                        onJumpToClick = { showJumpSheet = true },
                    )
                    PullToRefreshBox(
                        isRefreshing = false,
                        onRefresh = { if (state.isOnline) viewModel.refresh() },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Fixed(3),
                            contentPadding = PaddingValues(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            // itemsIndexed: header keys combine the label with the flat list index
                            // so that two SectionHeader items with the same label (e.g. two "#"
                            // groups) can never produce duplicate keys → no ANR/crash.
                            itemsIndexed(
                                items = state.gridItems,
                                key = { index, item ->
                                    when (item) {
                                        is AlbumGridItem.SectionHeader -> "header_${item.label}_${index}"
                                        is AlbumGridItem.Album -> item.album.id
                                    }
                                },
                                span = { _, item ->
                                    when (item) {
                                        is AlbumGridItem.SectionHeader -> GridItemSpan(maxLineSpan)
                                        is AlbumGridItem.Album -> GridItemSpan(1)
                                    }
                                },
                            ) { _, item ->
                                when (item) {
                                    is AlbumGridItem.SectionHeader -> {
                                        Text(
                                            text = item.label,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Color.White.copy(alpha = 0.5f),
                                            modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
                                        )
                                    }
                                    is AlbumGridItem.Album -> {
                                        val album = item.album
                                        val subtitle = when (sortOrder) {
                                            AlbumSortOrder.BY_YEAR -> album.year?.toString() ?: "Unknown"
                                            else -> album.artistName
                                        }
                                        AlbumCardItem(
                                            album = album,
                                            coverArtUrl = album.coverArtId?.let {
                                                viewModel.getCoverArtUrl(it, 300)
                                            },
                                            isOnline = state.isOnline,
                                            onClick = onAlbumClick,
                                            onOfflineBlocked = {
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(
                                                        "Not available offline. Connect to stream this album.",
                                                    )
                                                }
                                            },
                                            subtitle = subtitle,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Sort bar ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortBar(
    sortOrder: AlbumSortOrder,
    onSortOrderSelected: (AlbumSortOrder) -> Unit,
    jumpToLabel: String?,
    onJumpToClick: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box {
            Surface(
                onClick = { expanded = true },
                shape = RoundedCornerShape(50),
                color = Color(0xFF111111),
                border = BorderStroke(1.dp, Color(0xFF1A1A1A)),
                modifier = Modifier.height(32.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Sort: ${sortOrder.label}",
                        color = Color(0xFF8C8C8C),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                        tint = Color(0xFF8C8C8C),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                AlbumSortOrder.entries.forEach { order ->
                    DropdownMenuItem(
                        text = { Text(order.label) },
                        onClick = { onSortOrderSelected(order); expanded = false },
                    )
                }
            }
        }

        if (jumpToLabel != null) {
            Surface(
                onClick = onJumpToClick,
                shape = RoundedCornerShape(50),
                color = Color(0xFF111111),
                border = BorderStroke(1.dp, Color(0xFF1A1A1A)),
                modifier = Modifier.height(32.dp),
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Jump to: $jumpToLabel",
                        color = Color(0xFF8C8C8C),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

// ─── Filter empty state ───────────────────────────────────────────────────────

@Composable
private fun FilterEmptyState(
    icon: @Composable () -> Unit,
    message: String,
    sortBar: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        sortBar()
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                icon()
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.5f),
                )
            }
        }
    }
}

// ─── Banners ──────────────────────────────────────────────────────────────────

@Composable
private fun OfflineBanner() {
    Surface(color = Color(0xFF1A1A1A), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.WifiOff,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = Color.White.copy(alpha = 0.8f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Offline — showing cached library",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun SyncFailedBanner(onRetry: () -> Unit) {
    Surface(color = Color(0xFF3D2020), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.SyncProblem,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = Color(0xFFFF8A80),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Last sync failed — pull to refresh",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFFF8A80),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) {
                Text("Retry", color = Color(0xFFFF8A80), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
