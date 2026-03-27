package com.torsten.app.ui.home

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.torsten.app.data.api.dto.AlbumDto
import com.torsten.app.ui.common.AlbumCoverArt
import com.torsten.app.ui.common.DarkBackground
import com.torsten.app.ui.common.SectionHeader
import com.torsten.app.ui.theme.Radius
import com.torsten.app.ui.theme.TorstenColor

private val CardWidth    = 150.dp
private val CardSpacing  = 12.dp
private val RowPadding   = 16.dp
private val HeroArtSize  = 104.dp
private val SectionGap   = 28.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onAlbumClick: (albumId: String, albumTitle: String) -> Unit,
    onGenreClick: (genre: String) -> Unit = {},
    onSeeAll: (listType: String, title: String) -> Unit = { _, _ -> },
    onSettingsClick: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(containerColor = DarkBackground) { innerPadding ->
        when {
            state.error != null -> ErrorState(
                message = state.error!!,
                onRetry = viewModel::loadFeed,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .statusBarsPadding(),
            )

            else -> {
                PullToRefreshBox(
                    isRefreshing = state.isLoading,
                    onRefresh = viewModel::loadFeed,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .statusBarsPadding(),
                ) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {

                        // ── Header ────────────────────────────────────────────
                        item {
                            Spacer(Modifier.height(20.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = RowPadding, end = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Torsten",
                                    fontSize = 30.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                )
                                IconButton(onClick = onSettingsClick) {
                                    Icon(
                                        imageVector = Icons.Filled.Settings,
                                        contentDescription = "Settings",
                                        tint = Color.White.copy(alpha = 0.7f),
                                    )
                                }
                            }
                            Spacer(Modifier.height(28.dp))
                        }

                        // ── Hero: Continue Listening ───────────────────────────
                        item {
                            val hero = state.recentlyPlayed.firstOrNull()
                            if (state.isLoading || hero != null) {
                                SectionHeader(title = "Continue Listening")
                                if (state.isLoading) {
                                    HeroSkeleton()
                                } else {
                                    ContinueListeningHero(
                                        album = hero!!,
                                        coverArtUrl = hero.coverArt?.let {
                                            viewModel.getCoverArtUrl(it, 600)
                                        },
                                        onAlbumClick = onAlbumClick,
                                    )
                                }
                                Spacer(Modifier.height(SectionGap))
                            }
                        }

                        // ── Recently Played ────────────────────────────────────
                        item {
                            AlbumRow(
                                title          = "Recently Played",
                                albums         = state.recentlyPlayed,
                                isLoading      = state.isLoading,
                                getCoverArtUrl = viewModel::getCoverArtUrl,
                                onAlbumClick   = onAlbumClick,
                                onSeeAll       = { onSeeAll("recent", "Recently Played") },
                            )
                        }

                        // ── New Additions ──────────────────────────────────────
                        item {
                            AlbumRow(
                                title          = "New Additions",
                                albums         = state.newAdditions,
                                isLoading      = state.isLoading,
                                getCoverArtUrl = viewModel::getCoverArtUrl,
                                onAlbumClick   = onAlbumClick,
                                onSeeAll       = { onSeeAll("newest", "New Additions") },
                            )
                        }

                        // ── Most Played ────────────────────────────────────────
                        item {
                            AlbumRow(
                                title          = "Most Played",
                                albums         = state.mostPlayed,
                                isLoading      = state.isLoading,
                                getCoverArtUrl = viewModel::getCoverArtUrl,
                                onAlbumClick   = onAlbumClick,
                                onSeeAll       = { onSeeAll("frequent", "Most Played") },
                            )
                        }

                        item { Spacer(Modifier.height(20.dp)) }
                    }
                }
            }
        }
    }
}

// ─── Hero card ────────────────────────────────────────────────────────────────

@Composable
private fun ContinueListeningHero(
    album: AlbumDto,
    coverArtUrl: String?,
    onAlbumClick: (albumId: String, albumTitle: String) -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(horizontal = RowPadding)
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .background(TorstenColor.ElevatedSurface)
            .clickable { onAlbumClick(album.id, album.name) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumCoverArt(
            coverArtUrl        = coverArtUrl,
            coverArtId         = album.coverArt,
            contentDescription = album.name,
            isOnline           = true,
            modifier           = Modifier
                .size(HeroArtSize)
                .clip(RoundedCornerShape(Radius.card)),
        )

        Spacer(Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = album.name,
                fontSize   = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color      = Color.White,
                maxLines   = 2,
                overflow   = TextOverflow.Ellipsis,
            )
            if (!album.artist.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text     = album.artist,
                    fontSize = 13.sp,
                    color    = TorstenColor.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Icon(
            imageVector        = Icons.Filled.PlayArrow,
            contentDescription = "Play",
            tint               = Color.White,
            modifier           = Modifier.size(36.dp),
        )
    }
}

@Composable
private fun HeroSkeleton() {
    Box(
        modifier = Modifier
            .padding(horizontal = RowPadding)
            .fillMaxWidth()
            .height(HeroArtSize + 24.dp)
            .clip(RoundedCornerShape(Radius.card))
            .background(TorstenColor.ElevatedSurface),
    )
}

// ─── Album section row ────────────────────────────────────────────────────────

@Composable
private fun AlbumRow(
    title: String,
    albums: List<AlbumDto>,
    isLoading: Boolean,
    getCoverArtUrl: (id: String, size: Int) -> String?,
    onAlbumClick: (albumId: String, albumTitle: String) -> Unit,
    onSeeAll: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = title, onSeeAll = onSeeAll)

        if (isLoading) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = RowPadding),
                horizontalArrangement = Arrangement.spacedBy(CardSpacing),
            ) {
                items(6) { SkeletonCard() }
            }
        } else if (albums.isEmpty()) {
            Text(
                text = "Nothing here yet",
                style = MaterialTheme.typography.bodySmall,
                color = TorstenColor.TextSecondary,
                modifier = Modifier.padding(horizontal = RowPadding, vertical = 4.dp),
            )
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = RowPadding),
                horizontalArrangement = Arrangement.spacedBy(CardSpacing),
            ) {
                items(albums, key = { it.id }) { album ->
                    HomeAlbumCard(
                        album       = album,
                        coverArtUrl = album.coverArt?.let { getCoverArtUrl(it, 300) },
                        onAlbumClick = onAlbumClick,
                    )
                }
            }
        }

        Spacer(Modifier.height(SectionGap))
    }
}

// ─── Individual album card ────────────────────────────────────────────────────

@Composable
private fun HomeAlbumCard(
    album: AlbumDto,
    coverArtUrl: String?,
    onAlbumClick: (albumId: String, albumTitle: String) -> Unit,
) {
    Column(
        modifier = Modifier
            .width(CardWidth)
            .clickable { onAlbumClick(album.id, album.name) },
    ) {
        AlbumCoverArt(
            coverArtUrl        = coverArtUrl,
            coverArtId         = album.coverArt,
            contentDescription = album.name,
            isOnline           = true,
            modifier           = Modifier
                .size(CardWidth)
                .clip(RoundedCornerShape(Radius.card)),
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text       = album.name,
            fontSize   = 12.sp,
            fontWeight = FontWeight.Medium,
            color      = Color.White,
            maxLines   = 1,
            overflow   = TextOverflow.Ellipsis,
        )

        Text(
            text     = album.artist.orEmpty(),
            fontSize = 11.sp,
            color    = Color(0xFF8C8C8C),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ─── Skeleton placeholders ────────────────────────────────────────────────────

@Composable
private fun SkeletonCard() {
    Column(modifier = Modifier.width(CardWidth)) {
        Box(
            modifier = Modifier
                .size(CardWidth)
                .clip(RoundedCornerShape(Radius.card))
                .background(TorstenColor.ElevatedSurface),
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(13.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(TorstenColor.ElevatedSurface),
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(12.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(TorstenColor.ElevatedSurface),
        )
    }
}

// ─── Error state ──────────────────────────────────────────────────────────────

@Composable
private fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text  = message,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.6f),
            )
            TextButton(onClick = onRetry) {
                Text("Retry", color = Color.White)
            }
        }
    }
}
