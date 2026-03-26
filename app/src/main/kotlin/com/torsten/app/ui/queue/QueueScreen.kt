package com.torsten.app.ui.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.torsten.app.data.queue.QueueTrack
import com.torsten.app.ui.common.EmptyState
import com.torsten.app.ui.common.SectionHeader
import com.torsten.app.ui.playback.PlaybackUiState
import com.torsten.app.ui.playback.PlaybackViewModel
import com.torsten.app.ui.theme.TorstenColor
import kotlin.math.roundToInt

private val DarkBg = Color(0xFF0A0A0A)
private val SurfaceCard = Color(0xFF1A1A1A)
private val ItemBg = Color(0xFF111111)
private val TextSecondary = Color(0xFF8C8C8C)

private fun formatDurationMs(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

@Composable
fun QueueScreen(
    playbackViewModel: PlaybackViewModel,
    navController: NavController,
    onNavigateToLibrary: () -> Unit = {},
) {
    val playbackState by playbackViewModel.state.collectAsStateWithLifecycle()
    val priorityQueue by playbackViewModel.priorityQueue.collectAsStateWithLifecycle()
    val backgroundSequence by playbackViewModel.backgroundSequence.collectAsStateWithLifecycle()
    val backgroundCurrentIndex by playbackViewModel.backgroundCurrentIndex.collectAsStateWithLifecycle()

    val isEmpty = !playbackState.isActive && priorityQueue.isEmpty() && backgroundSequence.isEmpty()
    val showBackArrow = navController.previousBackStackEntry != null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .statusBarsPadding(),
    ) {
        if (isEmpty) {
            EmptyState(
                message = "Nothing queued",
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                subtitle = "Long press any track to add it",
                actionLabel = "Browse music",
                onAction = onNavigateToLibrary,
                modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {

            // ── Drag handle + title ───────────────────────────────────────────
            item {
                // Drag-handle pill — visual cue that Queue can be swiped away
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(4.dp)
                            .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(2.dp)),
                    )
                }

                // Title row — swiping down here dismisses Queue back to Now Playing
                var headerDragY by remember { mutableFloatStateOf(0f) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 16.dp)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { headerDragY = 0f },
                                onDragCancel = { headerDragY = 0f },
                                onDragEnd = {
                                    if (headerDragY > with(density) { 64.dp.toPx() }) {
                                        navController.popBackStack()
                                    }
                                    headerDragY = 0f
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    headerDragY += amount.y
                                },
                            )
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showBackArrow) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White,
                            )
                        }
                    }
                    Text(
                        text = "Queue",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        modifier = Modifier
                            .weight(1f)
                            .padding(
                                start = if (showBackArrow) 4.dp else 16.dp,
                                top = 16.dp,
                                bottom = 16.dp,
                            ),
                    )
                }
            }

            // ── Currently Playing card ────────────────────────────────────────
            if (playbackState.isActive) {
                item {
                    NowPlayingCard(
                        state = playbackState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 16.dp),
                    )
                }
            }

            // ── Up Next (priority queue) ──────────────────────────────────────
            if (priorityQueue.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "Up Next",
                        actionLabel = "Clear",
                        onSeeAll = { playbackViewModel.clearPriorityQueue() },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                // Nested Column for drag-to-reorder (priority queue is typically small)
                item {
                    DraggablePriorityList(
                        tracks = priorityQueue,
                        onMove = { from, to -> playbackViewModel.moveInPriorityQueue(from, to) },
                        onRemove = { index -> playbackViewModel.removeFromQueue(index) },
                    )
                }
            }

            // ── Playing from (background sequence) ───────────────────────────
            if (backgroundSequence.isNotEmpty()) {
                item {
                    HorizontalDivider(
                        modifier = Modifier.padding(top = 8.dp),
                        color = TorstenColor.Surface,
                    )
                    SectionHeader(
                        title = "Playing from",
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                itemsIndexed(
                    items = backgroundSequence,
                    key = { _, track -> track.songId },
                ) { index, track ->
                    BackgroundTrackRow(
                        track = track,
                        isCurrent = index == backgroundCurrentIndex,
                        onClick = { playbackViewModel.seekToBackgroundTrack(index) },
                    )
                }

                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

// ─── Now Playing card ─────────────────────────────────────────────────────────

@Composable
private fun NowPlayingCard(state: PlaybackUiState, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Cover art
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.06f)),
                ) {
                    if (state.coverArtUrl != null) {
                        AsyncImage(
                            model = state.coverArtUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.currentSongTitle.ifEmpty { "Unknown" },
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.artistName.isNotEmpty()) {
                        Text(
                            text = state.artistName,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // Playing indicator
                if (state.isPlaying) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Playing",
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // Progress bar
            if (state.durationMs > 0) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = {
                        (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.15f),
                )
            }
        }
    }
}

// ─── Draggable priority queue ─────────────────────────────────────────────────

@Composable
private fun DraggablePriorityList(
    tracks: List<QueueTrack>,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (index: Int) -> Unit,
) {
    var draggedIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val itemHeightPx = with(density) { 72.dp.toPx() }

    Column {
        tracks.forEachIndexed { index, track ->
            val isDragging = index == draggedIndex
            val visualOffsetPx: Float = when {
                isDragging -> dragOffsetY
                draggedIndex < 0 -> 0f
                else -> {
                    val from = draggedIndex
                    val target = (from + dragOffsetY / itemHeightPx).roundToInt()
                        .coerceIn(0, tracks.size - 1)
                    when {
                        from < target && index in (from + 1)..target -> -itemHeightPx
                        from > target && index in target until from -> itemHeightPx
                        else -> 0f
                    }
                }
            }

            androidx.compose.runtime.key(track.songId) {
                PriorityQueueItem(
                    track = track,
                    modifier = Modifier
                        .height(72.dp)
                        .offset { IntOffset(0, visualOffsetPx.roundToInt()) }
                        .zIndex(if (isDragging) 1f else 0f),
                    onDragStart = {
                        draggedIndex = index
                        dragOffsetY = 0f
                    },
                    onDrag = { dy -> dragOffsetY += dy },
                    onDragEnd = {
                        val from = draggedIndex
                        if (from >= 0) {
                            val to = (from + dragOffsetY / itemHeightPx).roundToInt()
                                .coerceIn(0, tracks.size - 1)
                            draggedIndex = -1
                            dragOffsetY = 0f
                            if (from != to) onMove(from, to)
                        } else {
                            draggedIndex = -1
                            dragOffsetY = 0f
                        }
                    },
                    onDragCancel = {
                        draggedIndex = -1
                        dragOffsetY = 0f
                    },
                    onRemove = { onRemove(index) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PriorityQueueItem(
    track: QueueTrack,
    modifier: Modifier = Modifier,
    onDragStart: () -> Unit,
    onDrag: (dy: Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onRemove: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { it == SwipeToDismissBoxValue.EndToStart },
    )

    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onRemove()
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFB71C1C)),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Remove from queue",
                    tint = Color.White,
                    modifier = Modifier.padding(end = 20.dp),
                )
            }
        },
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(ItemBg)
                .padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Drag handle
            Icon(
                imageVector = Icons.Filled.DragHandle,
                contentDescription = "Drag to reorder",
                tint = Color.White.copy(alpha = 0.3f),
                modifier = Modifier
                    .padding(horizontal = 10.dp)
                    .size(22.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { onDragStart() },
                            onDrag = { _, dragAmount -> onDrag(dragAmount.y) },
                            onDragEnd = onDragEnd,
                            onDragCancel = onDragCancel,
                        )
                    },
            )

            // Cover art
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.05f)),
            ) {
                if (track.coverArtUrl != null) {
                    AsyncImage(
                        model = track.coverArtUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            // Title + artist
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (track.artistName.isNotEmpty()) {
                    Text(
                        text = track.artistName,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Duration
            if (track.durationMs > 0) {
                Text(
                    text = formatDurationMs(track.durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

// ─── Background sequence row ──────────────────────────────────────────────────

@Composable
private fun BackgroundTrackRow(
    track: QueueTrack,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val titleColor = if (isCurrent) Color.White else TorstenColor.TextTertiary
    val metaColor = if (isCurrent) TorstenColor.TextSecondary else TorstenColor.TextTertiary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Current-track indicator column
        Box(modifier = Modifier.width(20.dp)) {
            if (isCurrent) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Now playing",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyMedium,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (track.artistName.isNotEmpty()) {
                Text(
                    text = track.artistName,
                    style = MaterialTheme.typography.bodySmall,
                    color = metaColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (track.durationMs > 0) {
            Text(
                text = formatDurationMs(track.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = metaColor,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
