package com.torsten.app.ui.downloads

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.torsten.app.data.datastore.DownloadedPlaylistInfo
import com.torsten.app.data.db.entity.AlbumEntity
import com.torsten.app.data.db.entity.DownloadState
import com.torsten.app.ui.common.EmptyState
import com.torsten.app.ui.common.SectionHeader
import com.torsten.app.ui.common.coverArtImageRequest
import com.torsten.app.ui.theme.TorstenColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    isOnline: Boolean,
    onAlbumClick: (albumId: String, title: String) -> Unit,
    onPlaylistClick: (playlistId: String, name: String) -> Unit = { _, _ -> },
    onBrowseLibrary: () -> Unit = {},
) {
    val activeDownloads by viewModel.activeDownloads.collectAsStateWithLifecycle()
    val completedDownloads by viewModel.completedDownloads.collectAsStateWithLifecycle()
    val failedDownloads by viewModel.failedDownloads.collectAsStateWithLifecycle()
    val downloadedPlaylists by viewModel.downloadedPlaylists.collectAsStateWithLifecycle()

    val isEmpty = activeDownloads.isEmpty() && completedDownloads.isEmpty() &&
        failedDownloads.isEmpty() && downloadedPlaylists.isEmpty()

    var selectedAlbum by remember { mutableStateOf<AlbumEntity?>(null) }
    var selectedPlaylist by remember { mutableStateOf<DownloadedPlaylistInfo?>(null) }

    if (selectedAlbum != null) {
        val album = selectedAlbum!!
        CompletedAlbumSheet(
            albumTitle = album.title,
            onDismiss = { selectedAlbum = null },
            onPlay = {
                onAlbumClick(album.id, album.title)
                selectedAlbum = null
            },
            onDelete = {
                viewModel.deleteDownload(album.id)
                selectedAlbum = null
            },
        )
    }

    if (selectedPlaylist != null) {
        val playlist = selectedPlaylist!!
        CompletedAlbumSheet(
            albumTitle = playlist.name,
            onDismiss = { selectedPlaylist = null },
            onPlay = {
                onPlaylistClick(playlist.playlistId, playlist.name)
                selectedPlaylist = null
            },
            onDelete = {
                viewModel.deletePlaylistDownload(playlist.playlistId, playlist.songIds)
                selectedPlaylist = null
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TorstenColor.Background)
            .statusBarsPadding(),
    ) {
        if (isEmpty) {
            EmptyState(
                message = "No downloads yet",
                icon = Icons.Filled.Download,
                subtitle = "Download albums to listen offline",
                actionLabel = "Browse Library",
                onAction = onBrowseLibrary,
                modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {

            item {
                Text(
                    text = "Offline Library",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                )
            }

            // ── Active downloads ──────────────────────────────────────────────
            if (activeDownloads.isNotEmpty()) {
                item {
                    SectionHeader(title = "Downloading")
                }
                itemsIndexed(activeDownloads, key = { _, a -> a.id }) { _, album ->
                    ActiveDownloadRow(
                        album = album,
                        coverArtUrl = album.coverArtId?.let { viewModel.getCoverArtUrl(it) },
                        isOnline = isOnline,
                        onCancel = { viewModel.cancelDownload(album.id) },
                    )
                }
            }

            // ── Completed downloads ───────────────────────────────────────────
            if (completedDownloads.isNotEmpty()) {
                item {
                    if (activeDownloads.isNotEmpty()) {
                        HorizontalDivider(
                            modifier = Modifier.padding(top = 8.dp),
                            color = TorstenColor.Surface,
                        )
                    }
                    SectionHeader(
                        title = "Albums",
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                itemsIndexed(completedDownloads, key = { _, a -> a.id }) { _, album ->
                    CompletedDownloadRow(
                        album = album,
                        coverArtUrl = album.coverArtId?.let { viewModel.getCoverArtUrl(it) },
                        isOnline = isOnline,
                        onClick = { onAlbumClick(album.id, album.title) },
                        onLongPress = { selectedAlbum = album },
                    )
                }
            }

            // ── Failed downloads ──────────────────────────────────────────────
            if (failedDownloads.isNotEmpty()) {
                item {
                    HorizontalDivider(
                        modifier = Modifier.padding(top = 8.dp),
                        color = TorstenColor.Surface,
                    )
                    SectionHeader(
                        title = "Failed",
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                itemsIndexed(failedDownloads, key = { _, a -> a.id }) { _, album ->
                    FailedDownloadRow(
                        album = album,
                        coverArtUrl = album.coverArtId?.let { viewModel.getCoverArtUrl(it) },
                        isOnline = isOnline,
                        onRetry = { viewModel.retryDownload(album.id) },
                    )
                }
            }

            // ── Downloaded playlists ──────────────────────────────────────────
            if (downloadedPlaylists.isNotEmpty()) {
                item {
                    HorizontalDivider(
                        modifier = Modifier.padding(top = 8.dp),
                        color = TorstenColor.Surface,
                    )
                    SectionHeader(
                        title = "Playlists",
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                itemsIndexed(downloadedPlaylists, key = { _, p -> p.playlistId }) { _, playlist ->
                    DownloadedPlaylistRow(
                        playlist = playlist,
                        coverArtUrl = playlist.coverArtId?.let { viewModel.getCoverArtUrl(it) },
                        onClick = { onPlaylistClick(playlist.playlistId, playlist.name) },
                        onLongPress = { selectedPlaylist = playlist },
                    )
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

// ─── Active download row ──────────────────────────────────────────────────────

@Composable
private fun ActiveDownloadRow(
    album: AlbumEntity,
    coverArtUrl: String?,
    isOnline: Boolean,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = coverArtImageRequest(context, coverArtUrl, album.coverArtId, isOnline),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(TorstenColor.ElevatedSurface),
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = album.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = album.artistName,
                    style = MaterialTheme.typography.bodySmall,
                    color = TorstenColor.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (album.downloadState == DownloadState.QUEUED) "Queued"
                           else "${album.downloadProgress}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = TorstenColor.TextTertiary,
                )
            }

            IconButton(onClick = onCancel) {
                Icon(
                    imageVector = Icons.Filled.Cancel,
                    contentDescription = "Cancel download",
                    tint = TorstenColor.TextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        if (album.downloadState == DownloadState.QUEUED) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .padding(end = 48.dp),
                color = TorstenColor.TextSecondary,
                trackColor = TorstenColor.ElevatedSurface,
            )
        } else {
            LinearProgressIndicator(
                progress = { album.downloadProgress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .padding(end = 48.dp),
                color = Color.White,
                trackColor = TorstenColor.ElevatedSurface,
            )
        }
    }
}

// ─── Completed download row ───────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompletedDownloadRow(
    album: AlbumEntity,
    coverArtUrl: String?,
    isOnline: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Art with subtle offline indicator
        Box(modifier = Modifier.size(56.dp)) {
            AsyncImage(
                model = coverArtImageRequest(context, coverArtUrl, album.coverArtId, isOnline),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(TorstenColor.ElevatedSurface),
            )
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(3.dp)
                    .size(12.dp),
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = album.title,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = album.artistName,
                style = MaterialTheme.typography.bodySmall,
                color = TorstenColor.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (album.downloadedAt != null) {
            Text(
                text = formatDownloadDate(album.downloadedAt),
                style = MaterialTheme.typography.labelSmall,
                color = TorstenColor.TextTertiary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

// ─── Failed download row ──────────────────────────────────────────────────────

@Composable
private fun FailedDownloadRow(
    album: AlbumEntity,
    coverArtUrl: String?,
    isOnline: Boolean,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = coverArtImageRequest(context, coverArtUrl, album.coverArtId, isOnline),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(TorstenColor.ElevatedSurface),
        )

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = album.title,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = album.artistName,
                style = MaterialTheme.typography.bodySmall,
                color = TorstenColor.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (album.downloadState == DownloadState.PARTIAL) "Incomplete" else "Download failed",
                style = MaterialTheme.typography.labelSmall,
                color = TorstenColor.Error,
            )
        }

        Button(
            onClick = onRetry,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            modifier = Modifier.height(32.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = TorstenColor.ElevatedSurface,
                contentColor = Color.White,
            ),
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text("Retry", style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ─── Completed album action sheet ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompletedAlbumSheet(
    albumTitle: String,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = TorstenColor.ElevatedSurface,
    ) {
        Text(
            text = albumTitle,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )
        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
        TextButton(
            onClick = onPlay,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text("Play album", color = Color.White, modifier = Modifier.weight(1f))
        }
        TextButton(
            onClick = onDelete,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = null,
                tint = TorstenColor.Error,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text("Delete download", color = TorstenColor.Error, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ─── Downloaded playlist row ──────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadedPlaylistRow(
    playlist: DownloadedPlaylistInfo,
    coverArtUrl: String?,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(56.dp)) {
            AsyncImage(
                model = coverArtImageRequest(context, coverArtUrl, playlist.coverArtId, true),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(TorstenColor.ElevatedSurface),
            )
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(3.dp)
                    .size(12.dp),
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${playlist.songCount} ${if (playlist.songCount == 1) "track" else "tracks"}",
                style = MaterialTheme.typography.bodySmall,
                color = TorstenColor.TextSecondary,
            )
        }

        Text(
            text = formatDownloadDate(playlist.downloadedAt),
            style = MaterialTheme.typography.labelSmall,
            color = TorstenColor.TextTertiary,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

// ─── Date formatting ──────────────────────────────────────────────────────────

private val dateFormatter = SimpleDateFormat("d MMM", Locale.getDefault())

private fun formatDownloadDate(epochMs: Long): String =
    dateFormatter.format(Date(epochMs))
