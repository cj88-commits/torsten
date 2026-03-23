package com.torsten.app.ui.playlists

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.torsten.app.data.api.dto.SongDto
import com.torsten.app.data.datastore.ServerConfig
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

    // Track long-press context sheet
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

    // Context menu bottom sheet
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

                // Play next
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

                // Add to playlist
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

                // Start instant mix
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

                // Remove from playlist
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
                    // Header
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, bottom = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            // Centered square art, full width minus 40dp padding, max 280dp
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
                                        Icon(Icons.Filled.MusicNote, null, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(64.dp))
                                    }
                                }
                            }

                            Spacer(Modifier.height(16.dp))

                            // Metadata column
                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                                // Playlist name + edit button
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = playlist.name,
                                        style = MaterialTheme.typography.titleLarge,
                                        color = Color.White,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
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

                                Spacer(Modifier.height(4.dp))

                                // Metadata row: tracks · duration
                                val meta = buildString {
                                    append("${tracks.size} ${if (tracks.size == 1) "track" else "tracks"}")
                                    if (playlist.duration > 0) append(" · ${formatDuration(playlist.duration)}")
                                }
                                Text(meta, style = MaterialTheme.typography.bodySmall, color = TorstenColor.TextTertiary)

                                if (tracks.isNotEmpty()) {
                                    Spacer(Modifier.height(12.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    val config = viewModel.getServerConfig().first()
                                                    playPlaylist(tracks, playlist.id, playlist.name, playlist.coverArt, config, viewModel, playbackViewModel)
                                                }
                                            },
                                            modifier = Modifier.height(40.dp),
                                            shape = RoundedCornerShape(50),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                                            contentPadding = PaddingValues(horizontal = 16.dp),
                                        ) {
                                            Icon(Icons.Filled.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Play", color = Color.Black)
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                scope.launch {
                                                    val config = viewModel.getServerConfig().first()
                                                    playPlaylist(tracks.shuffled(), playlist.id, playlist.name, playlist.coverArt, config, viewModel, playbackViewModel)
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
                                        PlaylistDownloadButton(
                                            downloadState = downloadState,
                                            onDownload = viewModel::downloadPlaylist,
                                            onCancel = viewModel::cancelPlaylistDownload,
                                            onDelete = { showDeleteDownloadDialog = true },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Empty state
                    if (tracks.isEmpty()) {
                        item {
                            EmptyState(
                                message = "No tracks yet",
                                subtitle = "Add tracks from albums or search results",
                                modifier = Modifier.height(280.dp),
                            )
                        }
                    }

                    // Track list
                    itemsIndexed(tracks, key = { idx, song -> "${song.id}_$idx" }) { index, song ->
                        SwipeToRemoveTrackRow(
                            song = song,
                            index = index + 1,
                            coverArtUrl = song.coverArt?.let { viewModel.getCoverArtUrl(it, 150) },
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
                                scope.launch {
                                    val config = viewModel.getServerConfig().first()
                                    playPlaylist(tracks, playlist.id, playlist.name, playlist.coverArt, config, viewModel, playbackViewModel, startIndex = index)
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

private suspend fun playPlaylist(
    tracks: List<SongDto>,
    playlistId: String,
    playlistName: String,
    coverArtId: String?,
    config: ServerConfig,
    detailViewModel: PlaylistDetailViewModel,
    playbackViewModel: PlaybackViewModel,
    startIndex: Int = 0,
) {
    val now = System.currentTimeMillis()
    val coverArtUrl = coverArtId?.let { detailViewModel.getCoverArtUrl(it, 300) }

    val songs = tracks.mapIndexed { idx, song ->
        com.torsten.app.data.db.entity.SongEntity(
            id = song.id,
            albumId = song.albumId.orEmpty().ifEmpty { "playlist_$playlistId" },
            artistId = song.artistId.orEmpty(),
            title = song.title,
            trackNumber = idx + 1,
            discNumber = 1,
            duration = song.duration ?: 0,
            bitRate = song.bitRate,
            suffix = song.suffix,
            contentType = song.contentType,
            starred = song.starred != null,
            localFilePath = null,
            lastUpdated = now,
        )
    }
    val album = com.torsten.app.data.db.entity.AlbumEntity(
        id = "playlist_$playlistId",
        title = playlistName,
        artistId = "",
        artistName = "",
        year = null,
        genre = null,
        songCount = tracks.size,
        duration = tracks.sumOf { it.duration ?: 0 },
        coverArtId = coverArtId,
        starred = false,
        downloadState = com.torsten.app.data.db.entity.DownloadState.NONE,
        downloadProgress = 0,
        downloadedAt = null,
        lastUpdated = now,
    )
    playbackViewModel.playAlbum(songs, album, startIndex, config, coverArtUrl)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistDownloadButton(
    downloadState: DownloadState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val isActive = downloadState == DownloadState.DOWNLOADING
    val isComplete = downloadState == DownloadState.COMPLETE
    val isPartial = downloadState == DownloadState.PARTIAL

    val borderColor = when {
        isComplete -> null
        isActive -> Color.White.copy(alpha = 0.2f)
        else -> Color.White.copy(alpha = 0.3f)
    }
    val bgColor = if (isComplete) TorstenColor.Success else Color.Transparent
    val contentColor = if (isActive) TorstenColor.TextTertiary else Color.White

    Surface(
        shape = RoundedCornerShape(50),
        border = borderColor?.let { BorderStroke(1.dp, it) },
        color = bgColor,
        contentColor = contentColor,
        modifier = Modifier
            .height(40.dp)
            .combinedClickable(
                onClick = {
                    when (downloadState) {
                        DownloadState.NONE, DownloadState.PARTIAL -> onDownload()
                        else -> {}
                    }
                },
                onLongClick = when {
                    isActive -> onCancel
                    isComplete -> onDelete
                    else -> null
                },
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (downloadState) {
                DownloadState.NONE -> {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Download", style = MaterialTheme.typography.labelLarge)
                }
                DownloadState.DOWNLOADING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = TorstenColor.TextTertiary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Downloading…", style = MaterialTheme.typography.labelLarge)
                }
                DownloadState.COMPLETE -> {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Downloaded ✓", style = MaterialTheme.typography.labelLarge)
                }
                DownloadState.PARTIAL -> {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Resume", style = MaterialTheme.typography.labelLarge)
                }
                else -> {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Download", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SwipeToRemoveTrackRow(
    song: SongDto,
    index: Int,
    coverArtUrl: String?,
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
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(Color(0xFFB71C1C)),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Filled.Delete, "Remove",
                    tint = Color.White,
                    modifier = Modifier.padding(end = 20.dp),
                )
            }
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkBackground)
                .combinedClickable(onClick = onClick, onLongClick = onLongPress)
                .padding(start = 20.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = index.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.4f),
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
                        model = coverArtUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(Icons.Filled.MusicNote, null, tint = Color.White.copy(alpha = 0.2f), modifier = Modifier.size(20.dp).align(Alignment.Center))
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    song.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!song.artist.isNullOrEmpty()) {
                    Text(
                        song.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            song.duration?.let { dur ->
                Text(
                    formatTrackDuration(dur),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            IconButton(onClick = onMenuClick, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "More options",
                    tint = Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
