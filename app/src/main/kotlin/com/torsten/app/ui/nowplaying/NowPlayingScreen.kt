package com.torsten.app.ui.nowplaying

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.torsten.app.ui.common.DarkBackground
import com.torsten.app.ui.common.coverArtImageRequest
import com.torsten.app.ui.playback.PlaybackUiState
import com.torsten.app.ui.playback.PlaybackViewModel

private fun formatTime(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    playbackViewModel: PlaybackViewModel,
    isOnline: Boolean,
    onNavigateUp: () -> Unit,
    onNavigateToArtist: (artistId: String) -> Unit,
    onNavigateToAlbum: (albumId: String) -> Unit,
    onNavigateToQueue: () -> Unit = {},
    onStartInstantMix: () -> Unit = {},
) {
    val state by playbackViewModel.state.collectAsStateWithLifecycle()
    val currentSongStarred by playbackViewModel.currentSongStarred.collectAsStateWithLifecycle()
    val qualityBadge by playbackViewModel.qualityBadge.collectAsStateWithLifecycle()
    val isMixLoading by playbackViewModel.isMixLoading.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        playbackViewModel.snackbarEvent.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            TopAppBar(
                title = {},
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBackground,
                    navigationIconContentColor = Color.White,
                ),
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                        )
                    }
                },
                actions = {
                    if (qualityBadge.isNotEmpty()) {
                        Text(
                            text = qualityBadge,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.55f),
                            modifier = Modifier
                                .border(
                                    width = 1.dp,
                                    color = Color.White.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(4.dp),
                                )
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                    }
                    if (isMixLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(24.dp)
                                .padding(horizontal = 12.dp),
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = onStartInstantMix) {
                            Icon(
                                imageVector = Icons.Filled.Shuffle,
                                contentDescription = "Instant mix",
                                tint = Color.White,
                            )
                        }
                    }
                    IconButton(onClick = onNavigateToQueue) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = "Queue",
                            tint = Color.White,
                        )
                    }
                },
            )

            // Artwork: weight(1f) fills space between TopAppBar and controls.
            // BoxWithConstraints lets us compute the square side from available space.
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                val sideLength = minOf(maxWidth, maxHeight)
                val context = LocalContext.current
                AsyncImage(
                    model = coverArtImageRequest(context, state.coverArtUrl, state.coverArtId, isOnline),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(sideLength)
                        .clip(RoundedCornerShape(12.dp)),
                )
            }

            // Controls: fixed height, never scrollable
            NowPlayingControls(
                state = state,
                currentSongStarred = currentSongStarred,
                onPlayPause = playbackViewModel::playPause,
                onNext = playbackViewModel::next,
                onPrevious = playbackViewModel::previous,
                onSeek = { playbackViewModel.seekTo(it) },
                onStarClick = playbackViewModel::toggleCurrentSongStar,
                onAlbumClick = { if (state.albumId.isNotEmpty()) onNavigateToAlbum(state.albumId) },
                onArtistClick = { if (state.artistId.isNotEmpty()) onNavigateToArtist(state.artistId) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NowPlayingControls(
    state: PlaybackUiState,
    currentSongStarred: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onStarClick: () -> Unit,
    onAlbumClick: () -> Unit,
    onArtistClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(DarkBackground)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        // Album title — tappable
        Text(
            text = state.albumTitle,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable(
                enabled = state.albumId.isNotEmpty(),
                onClick = onAlbumClick,
            ),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.currentSongTitle,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .weight(1f)
                    .basicMarquee(
                        iterations = Int.MAX_VALUE,
                        initialDelayMillis = 2000,
                        velocity = 40.dp,
                    ),
            )
            IconButton(onClick = onStarClick) {
                Icon(
                    imageVector = if (currentSongStarred) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    contentDescription = if (currentSongStarred) "Remove from favourites" else "Add to favourites",
                    tint = if (currentSongStarred) Color(0xFFFFC107) else Color.White.copy(alpha = 0.6f),
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        // Artist name — tappable
        Text(
            text = state.artistName,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White.copy(alpha = 0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable(
                enabled = state.artistId.isNotEmpty(),
                onClick = onArtistClick,
            ),
        )
        Spacer(modifier = Modifier.height(12.dp))
        SeekBar(
            state = state,
            onSeek = onSeek,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            IconButton(onClick = onPrevious) {
                Icon(
                    imageVector = Icons.Filled.SkipPrevious,
                    contentDescription = "Previous",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp),
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            FilledIconButton(
                onClick = onPlayPause,
                modifier = Modifier.size(64.dp),
                shape = RoundedCornerShape(12.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black,
                ),
            ) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(36.dp),
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            IconButton(onClick = onNext) {
                Icon(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = "Next",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekBar(
    state: PlaybackUiState,
    onSeek: (Long) -> Unit,
) {
    var localValue by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    val progress = if (state.durationMs > 0) {
        state.positionMs.toFloat() / state.durationMs
    } else 0f

    val bufferedFraction = if (state.durationMs > 0) {
        (state.bufferedPositionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
    } else 0f

    val displayValue = if (isDragging) localValue else progress

    LaunchedEffect(progress) {
        if (!isDragging) localValue = progress
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = displayValue,
            onValueChange = { value ->
                isDragging = true
                localValue = value
            },
            onValueChangeFinished = {
                isDragging = false
                if (state.durationMs > 0) onSeek((localValue * state.durationMs).toLong())
            },
            thumb = {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(Color.White, CircleShape),
                )
            },
            track = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                ) {
                    // inactive (full)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(2.dp)),
                    )
                    // buffered
                    if (bufferedFraction > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(bufferedFraction)
                                .height(3.dp)
                                .background(Color.White.copy(alpha = 0.35f), RoundedCornerShape(2.dp)),
                        )
                    }
                    // played
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(displayValue.coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(Color.White, RoundedCornerShape(2.dp)),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatTime(state.positionMs),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.5f),
            )
            Text(
                text = formatTime(state.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.5f),
            )
        }
    }
}
