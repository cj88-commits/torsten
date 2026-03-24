package com.torsten.app.ui.albums

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.torsten.app.data.repository.SyncState
import com.torsten.app.ui.common.AlbumCardItem
import com.torsten.app.ui.common.DarkBackground
import com.torsten.app.ui.common.LibraryHeader
import com.torsten.app.ui.common.LibrarySheetOption
import com.torsten.app.ui.common.LibraryTab
import com.torsten.app.ui.common.SheetSectionLabel
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

    val gridState = rememberLazyGridState()

    var showSortSheet by remember { mutableStateOf(false) }
    var showJumpSheet by remember { mutableStateOf(false) }

    // Section header positions used by the Jump To sheet
    val sectionHeaders = remember(state.gridItems) {
        state.gridItems.mapIndexedNotNull { index, item ->
            if (item is AlbumGridItem.SectionHeader) item.label to index else null
        }
    }

    // Restore scroll position when sort order changes
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

    // Persist scroll position as user scrolls
    LaunchedEffect(gridState, sortOrder) {
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .drop(1)
            .collect { (index, offset) ->
                viewModel.saveScrollPosition(sortOrder, index, offset)
            }
    }

    LaunchedEffect(Unit) {
        viewModel.snackbarEvent.collect { message ->
            snackbarHostState.showSnackbar(message)
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
            Column {
                LibraryHeader(
                    currentTab = LibraryTab.ALBUMS,
                    onNavigateToArtists = onNavigateToArtists,
                    onNavigateToPlaylists = onNavigateToPlaylists,
                )
                AlbumSortBar(
                    sortOrder = sortOrder,
                    hasSections = sectionHeaders.isNotEmpty(),
                    onSortClick = { showSortSheet = true },
                    onJumpClick = { showJumpSheet = true },
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (!state.isOnline) OfflineBanner()
            if (syncFailed) SyncFailedBanner(onRetry = { if (state.isOnline) viewModel.refresh() })

            when {
                // Filter-specific empty states
                !state.hasAlbums && !isActivelySyncing && isApiClientReady &&
                    sortOrder == AlbumSortOrder.RECENTLY_PLAYED -> {
                    FilterEmptyState(
                        icon = { Icon(Icons.Filled.MusicNote, null, modifier = Modifier.size(48.dp), tint = Color.White.copy(alpha = 0.3f)) },
                        message = "No recently played albums yet",
                    )
                }
                !state.hasAlbums && !isActivelySyncing && isApiClientReady &&
                    sortOrder == AlbumSortOrder.STARRED -> {
                    FilterEmptyState(
                        icon = { Icon(Icons.Filled.Star, null, modifier = Modifier.size(48.dp), tint = Color.White.copy(alpha = 0.3f)) },
                        message = "No starred albums yet",
                    )
                }
                !state.hasAlbums && isSyncing -> {
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
                                            modifier = Modifier.padding(start = 2.dp, top = 8.dp, bottom = 2.dp),
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
                                            showDownloadBadge = false,
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

    // ── Sort bottom sheet ──────────────────────────────────────────────────────
    if (showSortSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSortSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color(0xFF1A1A1A),
        ) {
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                SheetSectionLabel("Sort by")
                AlbumSortOrder.entries.forEach { order ->
                    LibrarySheetOption(order.label, sortOrder == order) {
                        viewModel.setSortOrder(order)
                        showSortSheet = false
                    }
                }
            }
        }
    }

    // ── Jump to bottom sheet — compact chip grid ───────────────────────────────
    if (showJumpSheet) {
        ModalBottomSheet(
            onDismissRequest = { showJumpSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color(0xFF1A1A1A),
        ) {
            JumpChipGrid(
                headers = sectionHeaders,
                onSelect = { gridIndex ->
                    scope.launch { gridState.scrollToItem(gridIndex) }
                    showJumpSheet = false
                },
            )
        }
    }
}

// ─── Jump-to chip grid ────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JumpChipGrid(
    headers: List<Pair<String, Int>>,
    onSelect: (gridIndex: Int) -> Unit,
) {
    Column(
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp),
    ) {
        Text(
            text = "Jump to",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.45f),
            modifier = Modifier.padding(bottom = 4.dp),
        )
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            headers.forEach { (label, gridIndex) ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.1f))
                        .clickable { onSelect(gridIndex) }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

// ─── Secondary control row (Albums tab only) ──────────────────────────────────

@Composable
private fun AlbumSortBar(
    sortOrder: AlbumSortOrder,
    hasSections: Boolean,
    onSortClick: () -> Unit,
    onJumpClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0A0A0A))
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Sort button
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = 0.08f))
                .clickable(onClick = onSortClick)
                .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = sortOrder.label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }

        // Jump to button — only when the current sort has section headers
        if (hasSections) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .clickable(onClick = onJumpClick)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "Jump to",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
    }
}

// ─── Filter empty state ───────────────────────────────────────────────────────

@Composable
private fun FilterEmptyState(
    icon: @Composable () -> Unit,
    message: String,
) {
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
