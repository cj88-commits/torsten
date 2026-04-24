package com.torsten.app.ui.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.torsten.app.data.api.dto.AlbumDto
import com.torsten.app.data.api.dto.ArtistDto
import com.torsten.app.data.api.dto.SongDto
import com.torsten.app.data.db.entity.SongEntity
import com.torsten.app.ui.common.DarkBackground
import com.torsten.app.ui.common.coverArtImageRequest
import com.torsten.app.ui.playback.PlaybackViewModel
import com.torsten.app.ui.theme.Radius
import com.torsten.app.ui.theme.TorstenColor
import com.torsten.app.ui.theme.TorstenTypography
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun ExploreScreen(
    viewModel: ExploreViewModel,
    playbackViewModel: PlaybackViewModel,
    onNavigateToNowPlaying: () -> Unit,
    onNavigateToArtist: (String) -> Unit = {},
    onNavigateToAlbum: (String, String) -> Unit = { _, _ -> },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground),
    ) {
        when (val state = uiState) {
            is ExploreUiState.Loading -> {
                CircularProgressIndicator(
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            is ExploreUiState.Error -> {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TorstenColor.TextSecondary,
                    )
                    TextButton(onClick = { viewModel.reroll() }) {
                        Text("Retry", color = Color.White)
                    }
                }
            }

            is ExploreUiState.Ready -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding(),
                ) {
                    // ── Header ─────────────────────────────────────────────────
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Explore,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Explore",
                            style = TorstenTypography.sectionTitle,
                            color = Color.White,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { viewModel.reroll() }) {
                            Icon(
                                imageVector = Icons.Rounded.Shuffle,
                                contentDescription = "Re-roll",
                                tint = Color.White,
                            )
                        }
                    }

                    // ── Artist card ────────────────────────────────────────────
                    var isArtistLoading by remember { mutableStateOf(false) }
                    ArtistExploreCard(
                        artist = state.artist,
                        topTracks = state.topTracks,
                        topTracksError = state.topTracksError,
                        coverArtUrl = viewModel.getCoverArtUrl(state.artist.coverArt, 400),
                        isLoading = isArtistLoading,
                        onNavigate = { onNavigateToArtist(state.artist.id) },
                        onPlay = {
                            if (isArtistLoading) return@ArtistExploreCard
                            isArtistLoading = true
                            scope.launch {
                                try {
                                    val config = viewModel.getServerConfig().first()
                                    val dtos = viewModel.buildArtistQueue(state.topTracks)
                                    if (dtos.isNotEmpty()) {
                                        playbackViewModel.playFromSongDtos(dtos, config)
                                        onNavigateToNowPlaying()
                                    }
                                } finally {
                                    isArtistLoading = false
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                    )

                    // ── Album card ─────────────────────────────────────────────
                    var isAlbumLoading by remember { mutableStateOf(false) }
                    AlbumExploreCard(
                        album = state.album,
                        coverArtUrl = viewModel.getCoverArtUrl(state.album.coverArt, 400),
                        isLoading = isAlbumLoading,
                        onNavigate = { onNavigateToAlbum(state.album.id, state.album.name) },
                        onPlay = {
                            if (isAlbumLoading) return@AlbumExploreCard
                            isAlbumLoading = true
                            scope.launch {
                                try {
                                    val config = viewModel.getServerConfig().first()
                                    val songs = viewModel.getAlbumSongs(state.album.id)
                                    if (songs.isNotEmpty()) {
                                        playbackViewModel.playFromSongDtos(songs, config)
                                        onNavigateToNowPlaying()
                                    }
                                } finally {
                                    isAlbumLoading = false
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
                    )
                }
            }
        }
    }
}

// ─── Artist card ──────────────────────────────────────────────────────────────

@Composable
private fun ArtistExploreCard(
    artist: ArtistDto,
    topTracks: List<SongEntity>,
    topTracksError: Boolean,
    coverArtUrl: String?,
    isLoading: Boolean,
    onNavigate: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val imageRequest = remember(coverArtUrl, artist.coverArt) {
        coverArtImageRequest(context, coverArtUrl, artist.coverArt, isOnline = true)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.card))
            .background(TorstenColor.Surface)
            .clickable(onClick = onNavigate),
    ) {
        // Cover art
        AsyncImage(
            model = imageRequest,
            contentDescription = artist.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // Placeholder icon when no art
        if (coverArtUrl == null) {
            Icon(
                imageVector = Icons.Rounded.Person,
                contentDescription = null,
                tint = TorstenColor.TextTertiary,
                modifier = Modifier
                    .size(64.dp)
                    .align(Alignment.Center),
            )
        }

        // Gradient overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.45f to Color.Black.copy(alpha = 0.2f),
                            1.0f to Color.Black.copy(alpha = 0.88f),
                        ),
                    ),
                ),
        )

        // Label chip
        ExploreLabel(
            label = "Artist",
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
        )

        // Bottom: name + play button
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = artist.name,
                style = TorstenTypography.heroTitle,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!topTracksError) {
                    ExplorePlayButton(isLoading = isLoading, onClick = onPlay)
                } else {
                    Text(
                        text = "Top tracks unavailable",
                        style = MaterialTheme.typography.labelSmall,
                        color = TorstenColor.TextSecondary,
                    )
                }
            }
        }
    }
}

// ─── Album card ───────────────────────────────────────────────────────────────

@Composable
private fun AlbumExploreCard(
    album: AlbumDto,
    coverArtUrl: String?,
    isLoading: Boolean,
    onNavigate: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val imageRequest = remember(coverArtUrl, album.coverArt) {
        coverArtImageRequest(context, coverArtUrl, album.coverArt, isOnline = true)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.card))
            .background(TorstenColor.Surface)
            .clickable(onClick = onNavigate),
    ) {
        // Cover art
        AsyncImage(
            model = imageRequest,
            contentDescription = album.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // Gradient overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.45f to Color.Black.copy(alpha = 0.2f),
                            1.0f to Color.Black.copy(alpha = 0.88f),
                        ),
                    ),
                ),
        )

        // Label chip
        ExploreLabel(
            label = "Album",
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
        )

        // Bottom: album title + artist + play button
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = album.name,
                style = TorstenTypography.heroTitle,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!album.artist.isNullOrBlank()) {
                Text(
                    text = album.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            ExplorePlayButton(isLoading = isLoading, onClick = onPlay)
        }
    }
}

// ─── Shared sub-composables ───────────────────────────────────────────────────

@Composable
private fun ExplorePlayButton(isLoading: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !isLoading,
        shape = RoundedCornerShape(50),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 0.dp),
        modifier = Modifier.height(36.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White,
            contentColor = Color.Black,
            disabledContainerColor = TorstenColor.ElevatedSurface,
            disabledContentColor = TorstenColor.TextTertiary,
        ),
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = TorstenColor.TextTertiary,
                strokeWidth = 2.dp,
                modifier = Modifier.size(14.dp),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text("Play", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ExploreLabel(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.75f),
        )
    }
}
