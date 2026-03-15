package com.recordcollection.app.ui.albumdetail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.recordcollection.app.data.db.entity.AlbumEntity
import com.recordcollection.app.data.db.entity.DownloadState
import com.recordcollection.app.data.db.entity.SongEntity
import com.recordcollection.app.ui.common.AlbumCoverArt
import com.recordcollection.app.ui.common.DarkBackground
import com.recordcollection.app.ui.playback.PlaybackViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private fun formatDuration(seconds: Int): String = when {
    seconds >= 3600 -> "%d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
    else -> "%d:%02d".format(seconds / 60, seconds % 60)
}

private fun formatTimeMs(ms: Long): String =
    "%d:%02d".format(ms / 60_000L, (ms % 60_000L) / 1000L)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    viewModel: AlbumDetailViewModel,
    playbackViewModel: PlaybackViewModel,
    onNavigateUp: () -> Unit,
    onNavigateToArtist: (artistId: String) -> Unit,
) {
    val album by viewModel.album.collectAsStateWithLifecycle()
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val savedPosition by viewModel.savedPosition.collectAsStateWithLifecycle()
    val isLoadingSongs by viewModel.isLoadingSongs.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()

    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var showResumeDialog by remember { mutableStateOf(false) }
    var pendingResumeIndex by remember { mutableIntStateOf(0) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.snackbarEvent.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    fun playAlbum(songList: List<SongEntity>, albumEntity: AlbumEntity?, startIndex: Int) {
        if (albumEntity == null || songList.isEmpty()) return
        coroutineScope.launch {
            val config = viewModel.getServerConfig().first()
            val coverArtUrl = albumEntity.coverArtId?.let { viewModel.getCoverArtUrl(it, 300) }
            playbackViewModel.playAlbum(
                songs = songList,
                album = albumEntity,
                startIndex = startIndex,
                config = config,
                coverArtUrl = coverArtUrl,
            )
        }
    }

    fun tryPlay(songList: List<SongEntity>, albumEntity: AlbumEntity?) {
        if (albumEntity == null || songList.isEmpty()) return
        if (!isOnline && downloadState != DownloadState.COMPLETE) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Not available offline — connect to download")
            }
            return
        }
        val posMs = savedPosition?.positionMs ?: 0L
        val totalDurationMs = songList.sumOf { it.duration } * 1_000L
        if (totalDurationMs >= 90_000L && posMs >= 30_000L && posMs <= totalDurationMs - 60_000L) {
            pendingResumeIndex = songList.indexOfFirst { it.id == savedPosition?.songId }.coerceAtLeast(0)
            showResumeDialog = true
        } else {
            playAlbum(songList, albumEntity, 0)
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete downloaded album?") },
            text = { Text("All downloaded tracks will be removed from this device.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteDownload()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showResumeDialog) {
        val posMs = savedPosition?.positionMs ?: 0L
        AlertDialog(
            onDismissRequest = { showResumeDialog = false },
            title = { Text("Resume playback?") },
            text = { Text("Continue from where you left off?") },
            confirmButton = {
                TextButton(onClick = {
                    showResumeDialog = false
                    playAlbum(songs, album, pendingResumeIndex)
                }) {
                    Text("Resume from ${formatTimeMs(posMs)}")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showResumeDialog = false
                    viewModel.clearSavedPosition()
                    playAlbum(songs, album, 0)
                }) {
                    Text("Play from beginning")
                }
            },
        )
    }

    val playbackState by playbackViewModel.state.collectAsStateWithLifecycle()
    val isThisAlbumActive = playbackState.isActive && playbackState.albumId == album?.id

    Scaffold(
        containerColor = DarkBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(album?.title ?: "Album", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F0F1F)),
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // 1. Header: half-size art left + metadata column right
            item {
                val coverArtUrl = remember(album?.coverArtId) {
                    album?.coverArtId?.let { viewModel.getCoverArtUrl(it, 600) }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    // Left: square cover art at half width
                    AlbumCoverArt(
                        coverArtUrl = coverArtUrl,
                        coverArtId = album?.coverArtId,
                        contentDescription = album?.title ?: "",
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp)),
                        isOnline = isOnline,
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    // Right: metadata column
                    Column(modifier = Modifier.weight(1f)) {
                        // Album title
                        Text(
                            text = album?.title ?: "",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        // Artist name (tappable) + star
                        val hasArtist = album?.artistId?.isNotEmpty() == true
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = album?.artistName ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = if (hasArtist) 0.9f else 0.6f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable(
                                        enabled = hasArtist,
                                        onClick = { album?.artistId?.let { onNavigateToArtist(it) } },
                                    ),
                            )
                            val currentAlbum = album
                            if (currentAlbum != null) {
                                IconButton(
                                    onClick = { viewModel.toggleAlbumStar(currentAlbum) },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        imageVector = if (currentAlbum.starred) Icons.Filled.Star else Icons.Outlined.StarOutline,
                                        contentDescription = if (currentAlbum.starred) "Remove from favourites" else "Add to favourites",
                                        tint = if (currentAlbum.starred) Color(0xFFFFC107) else Color.White.copy(alpha = 0.6f),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Track count
                        if (songs.isNotEmpty()) {
                            Text(
                                text = "${songs.size} ${if (songs.size == 1) "track" else "tracks"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.5f),
                            )
                        }

                        // Total duration
                        val totalSeconds = songs.sumOf { it.duration }
                        if (totalSeconds > 0) {
                            Text(
                                text = formatDuration(totalSeconds),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.5f),
                            )
                        }

                        // Year
                        if (album?.year != null) {
                            Text(
                                text = album?.year.toString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
            }

            // 3. Action buttons
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FilledTonalButton(
                        onClick = { tryPlay(songs, album) },
                        modifier = Modifier.height(48.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Play")
                    }

                    DownloadButton(
                        downloadState = downloadState,
                        downloadProgressFlow = viewModel.downloadProgress,
                        onDownload = viewModel::downloadAlbum,
                        onCancel = viewModel::cancelDownload,
                        onDelete = { showDeleteDialog = true },
                    )
                }
            }

            // 4. Loading spinner
            if (isLoadingSongs && songs.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
            }

            // 5. Track list header
            item {
                Text(
                    text = "Tracks",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.padding(16.dp),
                )
            }

            // 6. Tracks
            itemsIndexed(songs) { index, song ->
                TrackRow(
                    song = song,
                    isCurrentlyPlaying = isThisAlbumActive && playbackState.currentIndex == index,
                    onClick = { playAlbum(songs, album, index) },
                    onStarClick = { viewModel.toggleSongStar(song) },
                )
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadButton(
    downloadState: DownloadState,
    downloadProgressFlow: StateFlow<Int>,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val downloadProgress by downloadProgressFlow.collectAsStateWithLifecycle()
    val isActive = downloadState == DownloadState.QUEUED || downloadState == DownloadState.DOWNLOADING
    val isComplete = downloadState == DownloadState.COMPLETE

    // OutlinedButton doesn't expose onLongClick, so we build a look-alike using
    // Surface + combinedClickable — the only reliable way to have separate tap / long-press handlers.
    val borderColor = if (isComplete) MaterialTheme.colorScheme.outline.copy(alpha = 0.38f)
                      else MaterialTheme.colorScheme.outline
    val contentColor = if (isComplete) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                       else MaterialTheme.colorScheme.primary

    Surface(
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, borderColor),
        color = Color.Transparent,
        contentColor = contentColor,
        modifier = Modifier
            .height(48.dp)
            .combinedClickable(
                onClick = {
                    when (downloadState) {
                        DownloadState.NONE, DownloadState.FAILED, DownloadState.PARTIAL -> onDownload()
                        else -> {} // QUEUED / DOWNLOADING / COMPLETE: tap does nothing
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
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Download", style = MaterialTheme.typography.labelLarge)
                }
                DownloadState.QUEUED -> {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Waiting…", style = MaterialTheme.typography.labelLarge)
                }
                DownloadState.DOWNLOADING -> {
                    CircularProgressIndicator(
                        progress = { downloadProgress / 100f },
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("$downloadProgress%", style = MaterialTheme.typography.labelLarge)
                }
                DownloadState.COMPLETE -> {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Downloaded", style = MaterialTheme.typography.labelLarge)
                }
                DownloadState.PARTIAL, DownloadState.FAILED -> {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Retry", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    song: SongEntity,
    isCurrentlyPlaying: Boolean,
    onClick: () -> Unit,
    onStarClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isCurrentlyPlaying) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Now playing",
                tint = Color(0xFF7C6FFF),
                modifier = Modifier
                    .width(32.dp)
                    .size(18.dp),
            )
        } else {
            Text(
                text = song.trackNumber.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.width(32.dp),
            )
        }

        Text(
            text = song.title,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = formatDuration(song.duration),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.5f),
        )

        IconButton(
            onClick = onStarClick,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector = if (song.starred) Icons.Filled.Star else Icons.Outlined.StarOutline,
                contentDescription = if (song.starred) "Remove from favourites" else "Add to favourites",
                tint = if (song.starred) Color(0xFFFFC107) else Color.White.copy(alpha = 0.4f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
