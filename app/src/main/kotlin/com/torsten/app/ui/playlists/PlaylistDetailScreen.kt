package com.torsten.app.ui.playlists

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.torsten.app.data.api.dto.SongDto
import com.torsten.app.data.db.entity.DownloadState
import com.torsten.app.ui.common.DarkBackground
import com.torsten.app.ui.common.EmptyState
import com.torsten.app.ui.playback.PlaybackViewModel
import com.torsten.app.ui.theme.Radius
import com.torsten.app.ui.theme.TorstenColor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private fun formatDuration(seconds: Int): String = when {
    seconds >= 3600 -> "%dh %dm".format(seconds / 3600, (seconds % 3600) / 60)
    else -> "%dm".format(seconds / 60)
}

private fun formatTrackDuration(seconds: Int): String =
    "%d:%02d".format(seconds / 60, seconds % 60)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlaylistDetailScreen(
    viewModel: PlaylistDetailViewModel,
    playbackViewModel: PlaybackViewModel,
    initialName: String = "",
    onNavigateUp: () -> Unit,
    onAddToPlaylist: (songId: String) -> Unit,
    onStartInstantMix: (seed: SongDto) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var contextSong by remember { mutableStateOf<SongDto?>(null) }
    var contextSongIndex by remember { mutableIntStateOf(-1) }
    val contextSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf("") }
    var showDeleteDownloadDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.snackbar.collect { snackbarHostState.showSnackbar(it) }
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename playlist") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    singleLine = true,
                    label = { Text("Name") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renamePlaylist(renameValue)
                        showRenameDialog = false
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showDeleteDownloadDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDownloadDialog = false },
            title = { Text("Delete playlist download?") },
            text = { Text("All downloaded tracks will be removed from this device.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDownloadDialog = false
                        viewModel.deletePlaylistDownload()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDownloadDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (contextSong != null) {
        ModalBottomSheet(
            onDismissRequest = { contextSong = null },
            sheetState = contextSheetState,
            containerColor = TorstenColor.Surface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        ) {
            val song = contextSong!!
            val idx = contextSongIndex
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                TextButton(
                    onClick = {
                        contextSong = null
                        scope.launch {
                            val config = viewModel.getServerConfig().first()
                            playbackViewModel.enqueueNext(song, config)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null, tint = Color.White.copy(alpha = 0.7f))
                    Spacer(Modifier.width(12.dp))
                    Text("Play next", color = Color.White, modifier = Modifier.weight(1f))
                }
                TextButton(
                    onClick = {
                        contextSong = null
                        onAddToPlaylist(song.id)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null, tint = Color.White.copy(alpha = 0.7f))
                    Spacer(Modifier.width(12.dp))
                    Text("Add to playlist", color = Color.White, modifier = Modifier.weight(1f))
                }
                TextButton(
                    onClick = {
                        val s = contextSong ?: return@TextButton
                        contextSong = null
                        onStartInstantMix(s)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    Icon(Icons.Filled.Shuffle, null, tint = Color.White.copy(alpha = 0.7f))
                    Spacer(Modifier.width(12.dp))
                    Text("Start instant mix", color = Color.White, modifier = Modifier.weight(1f))
                }
                TextButton(
                    onClick = {
                        contextSong = null
                        viewModel.removeTrack(idx)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    Icon(Icons.Filled.Delete, null, tint = TorstenColor.Error)
                    Spacer(Modifier.width(12.dp))
                    Text("Remove from playlist", color = TorstenColor.Error, modifier = Modifier.weight(1f))
                }
            }
        }
    }

    Scaffold(
        containerColor = DarkBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.playlist?.name ?: initialName.ifEmpty { "Playlist" },
                        color = Color.White,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A0A0A)),
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            state.isLoading -> Box(
                Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = Color.White) }

            state.error != null -> Box(
                Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::loadPlaylist) { Text("Retry", color = Color.White) }
                }
            }

            else -> {
                val playlist = state.playlist ?: return@Scaffold
                val tracks = playlist.entry.orEmpty()

                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                ) {
                    // ── Playlist header ───────────────────────────────────────
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, bottom = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            BoxWithConstraints(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                val artSize = minOf(maxWidth, 280.dp)
                                Box(
                                    modifier = Modifier
                                        .size(artSize)
                                        .clip(RoundedCornerShape(Radius.card))
                                        .background(TorstenColor.ElevatedSurface),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    val artUrl = playlist.coverArt?.let { viewModel.getCoverArtUrl(it, 600) }
                                    if (artUrl != null) {
                                        AsyncImage(
                                            model = artUrl,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    } else {
                                        Icon(
                                            Icons.Filled.MusicNote,
                                            null,
                                            tint = Color.White.copy(alpha = 0.3f),
                                            modifier = Modifier.size(64.dp),
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(16.dp))

                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                                // Name + edit
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = playlist.name.ifEmpty { initialName.ifEmpty { "Playlist" } },
                                        style = MaterialTheme.typography.titleLarge,
                                        color = Color.White,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    // Only offer rename when online (name is authoritative from server)
                                    if (!state.isShowingCached) {
                                        IconButton(
                                            onClick = {
                                                renameValue = playlist.name
                                                showRenameDialog = true
                                            },
                                            modifier = Modifier.size(32.dp),
                                        ) {
                                            Icon(
                                                Icons.Filled.Edit,
                                                contentDescription = "Rename playlist",
                                                tint = Color.White.copy(alpha = 0.5f),
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                }

                                Spacer(Modifier.height(4.dp))

                                val meta = buildString {
                                    append("${tracks.size} ${if (tracks.size == 1) "track" else "tracks"}")
                                    if (playlist.duration > 0) append(" · ${formatDuration(playlist.duration)}")
                                }
                                Text(meta, style = MaterialTheme.typography.bodySmall, color = TorstenColor.TextTertiary)

                                // ── Offline indicator pill ────────────────────
                                if (state.isShowingCached) {
                                    Spacer(Modifier.height(10.dp))
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = Color.White.copy(alpha = 0.07f),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        ) {
                                            Icon(
                                                Icons.Filled.CloudOff,
                                                contentDescription = null,
                                                tint = TorstenColor.TextTertiary,
                                                modifier = Modifier.size(12.dp),
                                            )
                                            Text(
                                                "Offline — showing cached tracks",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TorstenColor.TextTertiary,
                                            )
                                        }
                                    }
                                }

                                // ── Action buttons ────────────────────────────
                                if (tracks.isNotEmpty()) {
                                    Spacer(Modifier.height(12.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        // Play — skips non-playable tracks when offline
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    val playable = if (state.isShowingCached) {
                                                        tracks.filter { it.albumId in state.downloadedAlbumIds }
                                                    } else tracks
                                                    if (playable.isEmpty()) {
                                                        snackbarHostState.showSnackbar("No downloaded tracks in this playlist")
                                                    } else {
                                                        val config = viewModel.getServerConfig().first()
                                                        playbackViewModel.playFromSongDtos(playable, config)
                                                    }
                                                }
                                            },
                                            modifier = Modifier.height(40.dp),
                                            shape = RoundedCornerShape(50),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color.White,
                                                contentColor   = Color.Black,
                                            ),
                                            contentPadding = PaddingValues(horizontal = 16.dp),
                                        ) {
                                            Icon(Icons.Filled.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Play", color = Color.Black)
                                        }

                                        // Shuffle — same offline filtering
                                        OutlinedButton(
                                            onClick = {
                                                scope.launch {
                                                    val playable = if (state.isShowingCached) {
                                                        tracks.filter { it.albumId in state.downloadedAlbumIds }
                                                    } else tracks
                                                    if (playable.isEmpty()) {
                                                        snackbarHostState.showSnackbar("No downloaded tracks in this playlist")
                                                    } else {
                                                        val config = viewModel.getServerConfig().first()
                                                        playbackViewModel.playFromSongDtos(playable, config, shuffle = true)
                                                    }
                                                }
                                            },
                                            modifier = Modifier.height(40.dp),
                                            shape = RoundedCornerShape(50),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                                            contentPadding = PaddingValues(horizontal = 16.dp),
                                        ) {
                                            Icon(Icons.Filled.Shuffle, null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Shuffle")
                                        }

                                        // Download button — hidden when showing cached (offline)
                                        if (!state.isShowingCached) {
                                            PlaylistDownloadButton(
                                                downloadState = downloadState,
                                                onDownload    = viewModel::downloadPlaylist,
                                                onCancel      = viewModel::cancelPlaylistDownload,
                                                onDelete      = { showDeleteDownloadDialog = true },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ── Empty state ───────────────────────────────────────────
                    if (tracks.isEmpty()) {
                        item {
                            EmptyState(
                                message  = "No tracks yet",
                                subtitle = "Add tracks from albums or search results",
                                modifier = Modifier.height(280.dp),
                            )
                        }
                    }

                    // ── Track list ────────────────────────────────────────────
                    itemsIndexed(tracks, key = { idx, song -> "${song.id}_$idx" }) { index, song ->
                        val isPlayable = !state.isShowingCached ||
                            (song.albumId != null && song.albumId in state.downloadedAlbumIds)
                        SwipeToRemoveTrackRow(
                            song        = song,
                            index       = index + 1,
                            coverArtUrl = song.coverArt?.let { viewModel.getCoverArtUrl(it, 150) },
                            isPlayable  = isPlayable,
                            onLongPress = {
                                contextSong = song
                                contextSongIndex = index
                            },
                            onMenuClick = {
                                contextSong = song
                                contextSongIndex = index
                            },
                            onSwipeRemove = { viewModel.removeTrack(index) },
                            onClick = {
                                if (!isPlayable) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Download the album to play offline")
                                    }
                                } else {
                                    scope.launch {
                                        val config = viewModel.getServerConfig().first()
                                        playbackViewModel.playFromSongDtos(
                                            tracks,
                                            config,
                                            startIndex = index,
                                            preservePriorityQueue = true,
                                        )
                                    }
                                }
                            },
                        )
                    }

                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

// ─── Download button ──────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistDownloadButton(
    downloadState: DownloadState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val isActive   = downloadState == DownloadState.DOWNLOADING
    val isComplete = downloadState == DownloadState.COMPLETE
    val isPartial  = downloadState == DownloadState.PARTIAL

    val borderColor  = when {
        isComplete -> null
        isActive   -> Color.White.copy(alpha = 0.2f)
        else       -> Color.White.copy(alpha = 0.3f)
    }
    val bgColor      = if (isComplete) TorstenColor.Success else Color.Transparent
    val contentColor = if (isActive) TorstenColor.TextTertiary else Color.White

    Surface(
        shape         = RoundedCornerShape(50),
        border        = borderColor?.let { BorderStroke(1.dp, it) },
        color         = bgColor,
        contentColor  = contentColor,
        modifier      = Modifier
            .height(40.dp)
            .combinedClickable(
                onClick = {
                    when (downloadState) {
                        DownloadState.NONE, DownloadState.PARTIAL -> onDownload()
                        else -> {}
                    }
                },
                onLongClick = when {
                    isActive   -> onCancel
                    isComplete -> onDelete
                    else       -> null
                },
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (downloadState) {
                DownloadState.NONE -> {
                    Icon(Icons.Filled.Download, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Download", style = MaterialTheme.typography.labelLarge)
                }
                DownloadState.DOWNLOADING -> {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color       = TorstenColor.TextTertiary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Downloading…", style = MaterialTheme.typography.labelLarge)
                }
                DownloadState.COMPLETE -> {
                    Icon(Icons.Filled.CheckCircle, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Downloaded ✓", style = MaterialTheme.typography.labelLarge)
                }
                DownloadState.PARTIAL -> {
                    Icon(Icons.Filled.Download, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Resume", style = MaterialTheme.typography.labelLarge)
                }
                else -> {
                    Icon(Icons.Filled.Download, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Download", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

// ─── Track row (swipe-to-remove + offline awareness) ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SwipeToRemoveTrackRow(
    song: SongDto,
    index: Int,
    coverArtUrl: String?,
    isPlayable: Boolean = true,
    onLongPress: () -> Unit,
    onMenuClick: () -> Unit = {},
    onSwipeRemove: () -> Unit,
    onClick: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { it == SwipeToDismissBoxValue.EndToStart },
    )
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onSwipeRemove()
        }
    }

    SwipeToDismissBox(
        state                  = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent      = {
            Box(
                Modifier.fillMaxSize().background(Color(0xFFB71C1C)),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Filled.Delete, "Remove", tint = Color.White, modifier = Modifier.padding(end = 20.dp))
            }
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkBackground)
                .combinedClickable(onClick = onClick, onLongClick = onLongPress)
                .alpha(if (isPlayable) 1f else 0.5f)
                .padding(start = 20.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text     = index.toString(),
                style    = MaterialTheme.typography.bodySmall,
                color    = Color.White.copy(alpha = 0.4f),
                modifier = Modifier.width(28.dp),
            )

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF1A1A1A)),
            ) {
                if (coverArtUrl != null) {
                    AsyncImage(
                        model        = coverArtUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier     = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        Icons.Filled.MusicNote,
                        null,
                        tint     = Color.White.copy(alpha = 0.2f),
                        modifier = Modifier.size(20.dp).align(Alignment.Center),
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    song.title,
                    style    = MaterialTheme.typography.bodyMedium,
                    color    = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!song.artist.isNullOrEmpty()) {
                    Text(
                        song.artist,
                        style    = MaterialTheme.typography.bodySmall,
                        color    = Color.White.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            song.duration?.let { dur ->
                Text(
                    formatTrackDuration(dur),
                    style    = MaterialTheme.typography.bodySmall,
                    color    = Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            // Non-playable offline tracks show a download hint icon instead of the menu
            if (!isPlayable) {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = "Album not downloaded",
                    tint               = Color.White.copy(alpha = 0.35f),
                    modifier           = Modifier
                        .padding(horizontal = 10.dp)
                        .size(18.dp),
                )
            } else {
                IconButton(onClick = onMenuClick, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "More options",
                        tint               = Color.White.copy(alpha = 0.4f),
                        modifier           = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
