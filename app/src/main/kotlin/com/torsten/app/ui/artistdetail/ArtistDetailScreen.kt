package com.torsten.app.ui.artistdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.torsten.app.data.api.dto.SongDto
import com.torsten.app.data.db.entity.AlbumEntity
import com.torsten.app.data.db.entity.DownloadState
import com.torsten.app.data.db.entity.SongEntity
import com.torsten.app.ui.common.AlbumCardItem
import com.torsten.app.ui.common.EmptyState
import com.torsten.app.ui.common.SectionHeader
import com.torsten.app.ui.common.coverArtImageRequest
import com.torsten.app.ui.common.shimmerBrush
import com.torsten.app.ui.playback.PlaybackViewModel
import com.torsten.app.ui.theme.TorstenColor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val HeroHeight = 260.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDetailScreen(
    viewModel: ArtistDetailViewModel,
    playbackViewModel: PlaybackViewModel,
    onAlbumClick: (albumId: String, albumTitle: String) -> Unit,
    onNavigateUp: () -> Unit,
    onAddToPlaylist: (songId: String) -> Unit = {},
    onStartInstantMix: (seed: SongDto) -> Unit = {},
) {
    val artist by viewModel.artist.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val topTracks by viewModel.topTracks.collectAsStateWithLifecycle()
    val displayTopTracks by viewModel.displayTopTracks.collectAsStateWithLifecycle()
    val artistSongs by viewModel.artistSongs.collectAsStateWithLifecycle()
    val artistImageUrl by viewModel.artistLargeImageUrl.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val topTracksLoading by viewModel.topTracksLoading.collectAsStateWithLifecycle()
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    var contextLbSong by remember { mutableStateOf<SongEntity?>(null) }
    var contextSongDto by remember { mutableStateOf<SongDto?>(null) }
    val contextSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Fade-in factor for the floating top bar (0=hero visible, 1=scrolled past)
    val heroHeightPx = with(LocalDensity.current) { HeroHeight.toPx() }
    val topBarAlpha by remember(listState) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / heroHeightPx).coerceIn(0f, 1f)
        }
    }

    fun playTracks(shuffle: Boolean, startIndex: Int = 0, preservePriority: Boolean = false) {
        if (topTracks.isEmpty()) return
        scope.launch {
            val config = viewModel.getServerConfig().first()
            playbackViewModel.playFromSongDtos(topTracks, config, shuffle = shuffle, startIndex = startIndex, preservePriorityQueue = preservePriority)
        }
    }

    fun playAllArtistSongs(shuffle: Boolean) {
        scope.launch {
            val songs = viewModel.allArtistSongsForPlayback()
            if (songs.isEmpty()) return@launch
            val config = viewModel.getServerConfig().first()
            playbackViewModel.playFromSongDtos(songs, config, shuffle = shuffle)
        }
    }

    fun playQueueForTopTrack(tappedSong: SongEntity) {
        scope.launch {
            val (dtos, startIndex) = viewModel.buildQueueForTopTrack(tappedSong)
            if (dtos.isEmpty()) return@launch
            val config = viewModel.getServerConfig().first()
            playbackViewModel.playFromSongDtos(dtos, config, startIndex = startIndex, preservePriorityQueue = true)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(TorstenColor.Background)) {

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {

            // ── 1. Hero ───────────────────────────────────────────────────────
            item {
                ArtistHero(
                    artistName = artist?.name ?: "",
                    albumCount = albums.size,
                    imageUrl = artistImageUrl,
                    isLoading = isLoading && artist == null,
                    modifier = Modifier.fillMaxWidth().height(HeroHeight),
                )
            }

            // ── 2. Action row ─────────────────────────────────────────────────
            item {
                ActionRow(
                    isLoading = isLoading,
                    hasTopTracks = artistSongs.isNotEmpty(),
                    onPlay = { playAllArtistSongs(shuffle = false) },
                    onShuffle = { playAllArtistSongs(shuffle = true) },
                    onInstantMix = {
                        val seed = topTracks.firstOrNull()
                            ?: artistSongs.firstOrNull()?.let { song ->
                                SongDto(
                                    id = song.id,
                                    title = song.title,
                                    artistId = song.artistId,
                                    albumId = song.albumId,
                                    duration = song.duration,
                                    coverArt = albums.firstOrNull { it.id == song.albumId }?.coverArtId,
                                )
                            }
                        seed?.let(onStartInstantMix)
                    },
                )
            }

            // ── 3. Top Tracks ─────────────────────────────────────────────────
            if (topTracksLoading) {
                item { SectionHeader(title = "Top Tracks") }
                items(count = 5) { ShimmerTrackRow() }
            } else if (displayTopTracks.isNotEmpty()) {
                item { SectionHeader(title = "Top Tracks") }
                itemsIndexed(displayTopTracks) { index, track ->
                    LbTopTrackRow(
                        number = index + 1,
                        song = track,
                        coverArtUrl = viewModel.getCoverArtUrlForSong(track, 150),
                        isOnline = isOnline,
                        onClick = { playQueueForTopTrack(track) },
                        onMenuClick = { contextLbSong = track },
                    )
                }
            } else if (topTracks.isNotEmpty()) {
                item { SectionHeader(title = "Top Tracks") }
                itemsIndexed(topTracks) { index, track ->
                    TopTrackRow(
                        number = index + 1,
                        song = track,
                        coverArtUrl = track.coverArt?.let { viewModel.getCoverArtUrl(it, 150) },
                        isOnline = isOnline,
                        onClick = { playTracks(shuffle = false, startIndex = index, preservePriority = true) },
                        onMenuClick = { contextSongDto = track },
                    )
                }
            }

            // ── 4. Albums ─────────────────────────────────────────────────────
            item {
                HorizontalDivider(
                    modifier = Modifier.padding(top = if (topTracks.isNotEmpty() || topTracksLoading) 8.dp else 0.dp),
                    color = TorstenColor.Surface,
                )
                SectionHeader(title = "Albums")
            }

            if (isLoading && albums.isEmpty()) {
                items(count = 2) { ShimmerAlbumRow() }
            } else if (albums.isNotEmpty()) {
                val rows = albums.chunked(2)
                itemsIndexed(rows, key = { _, row -> row.first().id }) { _, row ->
                    AlbumGridRow(
                        albums = row,
                        isOnline = isOnline,
                        onAlbumClick = onAlbumClick,
                        getCoverArtUrl = { album -> album.coverArtId?.let { viewModel.getCoverArtUrl(it, 300) } },
                    )
                }
            } else if (!isLoading) {
                item {
                    EmptyState(
                        message = "No albums found",
                        modifier = Modifier.padding(vertical = 40.dp),
                    )
                }
            }
        }

        // ── Context menu: LB top track (SongEntity) ──────────────────────────
        if (contextLbSong != null) {
            ModalBottomSheet(
                onDismissRequest = { contextLbSong = null },
                sheetState = contextSheetState,
                containerColor = TorstenColor.Surface,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            ) {
                val song = contextLbSong!!
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
                            contextLbSong = null
                            scope.launch {
                                val config = viewModel.getServerConfig().first()
                                val coverArtUrl = viewModel.getCoverArtUrlForSong(song, 300)
                                val stubAlbum = AlbumEntity(
                                    id = song.albumId.ifEmpty { "stub_${song.id}" },
                                    title = "",
                                    artistId = song.artistId,
                                    artistName = artist?.name.orEmpty(),
                                    year = null,
                                    genre = null,
                                    songCount = 1,
                                    duration = song.duration,
                                    coverArtId = null,
                                    starred = false,
                                    downloadState = DownloadState.NONE,
                                    downloadProgress = 0,
                                    downloadedAt = null,
                                    lastUpdated = System.currentTimeMillis(),
                                )
                                playbackViewModel.enqueueNextSong(song, stubAlbum, config, coverArtUrl)
                                snackbarHostState.showSnackbar("Added to queue")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null, tint = Color.White.copy(alpha = 0.7f))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Play next", color = Color.White, modifier = Modifier.weight(1f))
                    }
                    TextButton(
                        onClick = {
                            val songId = contextLbSong?.id ?: return@TextButton
                            contextLbSong = null
                            onAddToPlaylist(songId)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null, tint = Color.White.copy(alpha = 0.7f))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Add to playlist", color = Color.White, modifier = Modifier.weight(1f))
                    }
                    TextButton(
                        onClick = {
                            val s = contextLbSong ?: return@TextButton
                            contextLbSong = null
                            onStartInstantMix(
                                SongDto(
                                    id = s.id,
                                    title = s.title,
                                    artist = artist?.name.orEmpty(),
                                    artistId = s.artistId,
                                    album = "",
                                    albumId = s.albumId,
                                    duration = s.duration,
                                    coverArt = albums.firstOrNull { it.id == s.albumId }?.coverArtId,
                                    genre = null,
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                    ) {
                        Icon(Icons.Filled.Shuffle, null, tint = Color.White.copy(alpha = 0.7f))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Start instant mix", color = Color.White, modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // ── Context menu: Subsonic top track (SongDto) ────────────────────────
        if (contextSongDto != null) {
            ModalBottomSheet(
                onDismissRequest = { contextSongDto = null },
                sheetState = contextSheetState,
                containerColor = TorstenColor.Surface,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            ) {
                val song = contextSongDto!!
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
                            contextSongDto = null
                            scope.launch {
                                val config = viewModel.getServerConfig().first()
                                playbackViewModel.enqueueNext(song, config)
                                snackbarHostState.showSnackbar("Added to queue")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null, tint = Color.White.copy(alpha = 0.7f))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Play next", color = Color.White, modifier = Modifier.weight(1f))
                    }
                    TextButton(
                        onClick = {
                            val songId = contextSongDto?.id ?: return@TextButton
                            contextSongDto = null
                            onAddToPlaylist(songId)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null, tint = Color.White.copy(alpha = 0.7f))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Add to playlist", color = Color.White, modifier = Modifier.weight(1f))
                    }
                    TextButton(
                        onClick = {
                            val s = contextSongDto ?: return@TextButton
                            contextSongDto = null
                            onStartInstantMix(s)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                    ) {
                        Icon(Icons.Filled.Shuffle, null, tint = Color.White.copy(alpha = 0.7f))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Start instant mix", color = Color.White, modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // ── Snackbar ──────────────────────────────────────────────────────────
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        // ── Floating top bar (back arrow + fading title) ──────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TorstenColor.Background.copy(alpha = topBarAlpha))
                .statusBarsPadding()
                .padding(vertical = 4.dp)
                .align(Alignment.TopStart),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onNavigateUp) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                )
            }
            Text(
                text = artist?.name ?: "",
                color = Color.White.copy(alpha = topBarAlpha),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 16.dp),
            )
        }
    }
}

// ─── Hero section ─────────────────────────────────────────────────────────────

@Composable
private fun ArtistHero(
    artistName: String,
    albumCount: Int,
    imageUrl: String?,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize().background(shimmerBrush()))
        } else {
            // Background: hero image or initials fallback
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // Dark gradient fallback with artist initials
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color(0xFF1A1A2E),
                                1f to TorstenColor.Background,
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = artistInitials(artistName),
                        fontSize = 72.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.12f),
                    )
                }
            }

            // Gradient overlay: transparent → #CC000000
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color(0xCC000000),
                        ),
                    ),
            )

            // Artist name + album count
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 20.dp, bottom = 20.dp),
            ) {
                Text(
                    text = artistName,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = TorstenColor.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (albumCount > 0) {
                    Text(
                        text = "$albumCount ${if (albumCount == 1) "album" else "albums"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TorstenColor.TextSecondary,
                    )
                }
            }
        }
    }
}

// ─── Action row ───────────────────────────────────────────────────────────────

@Composable
private fun ActionRow(
    isLoading: Boolean,
    hasTopTracks: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onInstantMix: () -> Unit,
) {
    val shimmer = shimmerBrush()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (isLoading) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(50))
                        .background(shimmer),
                )
            }
        } else {
            val enabled = hasTopTracks
            // Play
            Button(
                onClick = onPlay,
                enabled = enabled,
                modifier = Modifier.weight(1f).height(36.dp),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black,
                    disabledContainerColor = TorstenColor.ElevatedSurface,
                    disabledContentColor = TorstenColor.TextTertiary,
                ),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Play", style = MaterialTheme.typography.labelMedium)
            }
            // Shuffle
            OutlinedButton(
                onClick = onShuffle,
                enabled = enabled,
                modifier = Modifier.weight(1f).height(36.dp),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color.White,
                    disabledContentColor = TorstenColor.TextTertiary,
                ),
            ) {
                Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Shuffle", style = MaterialTheme.typography.labelMedium)
            }
            // Instant Mix
            OutlinedButton(
                onClick = onInstantMix,
                enabled = enabled,
                modifier = Modifier.weight(1f).height(36.dp),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color.White,
                    disabledContentColor = TorstenColor.TextTertiary,
                ),
            ) {
                Text("Mix", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

// ─── Top track row ────────────────────────────────────────────────────────────

@Composable
private fun TopTrackRow(
    number: Int,
    song: SongDto,
    coverArtUrl: String?,
    isOnline: Boolean,
    onClick: () -> Unit,
    onMenuClick: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Track number
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = TorstenColor.TextTertiary,
            modifier = Modifier.width(24.dp),
        )

        Spacer(Modifier.width(8.dp))

        // Cover art
        AsyncImage(
            model = coverArtImageRequest(context, coverArtUrl, song.coverArt, isOnline),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(TorstenColor.ElevatedSurface),
        )

        Spacer(Modifier.width(12.dp))

        // Title + artist
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!song.artist.isNullOrEmpty()) {
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = TorstenColor.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Duration
        val duration = song.duration
        if (duration != null && duration > 0) {
            Text(
                text = formatDuration(duration),
                style = MaterialTheme.typography.bodySmall,
                color = TorstenColor.TextTertiary,
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

// ─── LB top track row (SongEntity) ────────────────────────────────────────────

@Composable
private fun LbTopTrackRow(
    number: Int,
    song: SongEntity,
    coverArtUrl: String?,
    isOnline: Boolean,
    onClick: () -> Unit,
    onMenuClick: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = TorstenColor.TextTertiary,
            modifier = Modifier.width(24.dp),
        )
        Spacer(Modifier.width(8.dp))
        AsyncImage(
            model = coverArtImageRequest(context, coverArtUrl, null, isOnline),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(TorstenColor.ElevatedSurface),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = song.title,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (song.duration > 0) {
            Text(
                text = formatDuration(song.duration),
                style = MaterialTheme.typography.bodySmall,
                color = TorstenColor.TextTertiary,
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

// ─── Album grid row (2 cards) ─────────────────────────────────────────────────

@Composable
private fun AlbumGridRow(
    albums: List<AlbumEntity>,
    isOnline: Boolean,
    onAlbumClick: (albumId: String, albumTitle: String) -> Unit,
    getCoverArtUrl: (AlbumEntity) -> String?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        albums.forEach { album ->
            AlbumCardItem(
                album = album,
                coverArtUrl = getCoverArtUrl(album),
                isOnline = isOnline,
                onClick = onAlbumClick,
                onOfflineBlocked = {},
                subtitle = album.year?.takeIf { it > 0 }?.toString() ?: "",
                modifier = Modifier.weight(1f),
            )
        }
        // Fill empty slot if odd number of albums
        if (albums.size == 1) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

// ─── Shimmer placeholders ─────────────────────────────────────────────────────

@Composable
private fun ShimmerTrackRow() {
    val shimmer = shimmerBrush()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)).background(shimmer))
        Spacer(Modifier.width(12.dp))
        Box(modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(shimmer))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.65f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(shimmer),
            )
            Spacer(Modifier.height(5.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(11.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(shimmer),
            )
        }
        Spacer(Modifier.width(24.dp))
        Box(modifier = Modifier.width(32.dp).height(11.dp).clip(RoundedCornerShape(4.dp)).background(shimmer))
    }
}

@Composable
private fun ShimmerAlbumRow() {
    val shimmer = shimmerBrush()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(2) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(shimmer),
            )
        }
    }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

private fun artistInitials(name: String): String =
    name.split(" ")
        .filter { it.isNotBlank() }
        .take(2)
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .joinToString("")

private fun formatDuration(seconds: Int): String =
    "%d:%02d".format(seconds / 60, seconds % 60)
