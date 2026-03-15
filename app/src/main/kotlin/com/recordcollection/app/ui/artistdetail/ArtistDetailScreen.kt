package com.recordcollection.app.ui.artistdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.recordcollection.app.ui.common.AlbumCardItem
import com.recordcollection.app.ui.common.DarkBackground

private val TopBarColor = Color(0xFF0F0F1F)
private val HeroHeight = 280.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDetailScreen(
    viewModel: ArtistDetailViewModel,
    onAlbumClick: (albumId: String, albumTitle: String) -> Unit,
    onNavigateUp: () -> Unit,
) {
    val artist by viewModel.artist.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val artistImageUrl by viewModel.artistLargeImageUrl.collectAsStateWithLifecycle()
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()

    val gridState = rememberLazyGridState()
    val heroHeightPx = with(LocalDensity.current) { HeroHeight.toPx() }

    // 0f = hero fully visible, 1f = hero fully scrolled off
    val collapseFraction by remember(gridState) {
        derivedStateOf {
            if (gridState.firstVisibleItemIndex > 0) 1f
            else (gridState.firstVisibleItemScrollOffset / heroHeightPx).coerceIn(0f, 1f)
        }
    }

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = artist?.name ?: "",
                        color = Color.White.copy(alpha = collapseFraction),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                        )
                    }
                },
                actions = {
                    val starred = artist?.starred ?: false
                    Icon(
                        imageVector = if (starred) Icons.Filled.Star else Icons.Outlined.StarOutline,
                        contentDescription = if (starred) "Starred" else "Not starred",
                        tint = if (starred) Color(0xFFFFC107) else Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.padding(end = 16.dp),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TopBarColor.copy(alpha = collapseFraction),
                    scrolledContainerColor = TopBarColor,
                ),
            )
        },
    ) { innerPadding ->
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(2),
            // Horizontal padding applied here — hero item bleeds through it via layout modifier
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                bottom = 12.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // ── Hero ──────────────────────────────────────────────────────────
            item(span = { GridItemSpan(maxLineSpan) }) {
                ArtistHero(
                    artistName = artist?.name ?: "",
                    albumCount = artist?.albumCount ?: 0,
                    imageUrl = artistImageUrl,
                    // No extra horizontal padding — hero aligns with album card edges,
                    // both inset 12dp from the screen edge via contentPadding.
                    modifier = Modifier
                        .height(HeroHeight)
                        .clip(RoundedCornerShape(12.dp)),
                )
            }

            // ── Empty state ───────────────────────────────────────────────────
            if (albums.isEmpty() && artist != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No albums found for this artist.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.6f),
                        )
                    }
                }
            }

            // ── Album grid ────────────────────────────────────────────────────
            items(albums, key = { it.id }) { album ->
                AlbumCardItem(
                    album = album,
                    coverArtUrl = album.coverArtId?.let { viewModel.getCoverArtUrl(it, 300) },
                    isOnline = isOnline,
                    onClick = onAlbumClick,
                    onOfflineBlocked = {},
                    subtitle = album.year?.takeIf { it > 0 }?.toString() ?: "",
                )
            }
        }
    }
}

// ─── Hero composable ──────────────────────────────────────────────────────────

@Composable
private fun ArtistHero(
    artistName: String,
    albumCount: Int,
    imageUrl: String?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        // Dark placeholder background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0A1A)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Person,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.08f),
                modifier = Modifier.fillMaxSize(0.4f),
            )
        }

        // Hero image (external URL from getArtistInfo2 — loaded directly by Coil)
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // Bottom scrim + artist name / album count
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.75f),
                    ),
                )
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Column {
                Text(
                    text = artistName,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (albumCount > 0) {
                    Text(
                        text = "$albumCount ${if (albumCount == 1) "album" else "albums"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}
