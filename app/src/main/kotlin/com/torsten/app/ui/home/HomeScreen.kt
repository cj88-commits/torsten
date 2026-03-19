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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.torsten.app.data.api.dto.GenreDto
import com.torsten.app.ui.common.AlbumCoverArt
import com.torsten.app.ui.common.DarkBackground

private val CardWidth   = 130.dp
private val CardSpacing = 12.dp
private val RowPadding  = 16.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onAlbumClick: (albumId: String, albumTitle: String) -> Unit,
    onGenreClick: (genre: String) -> Unit = {},
    onSeeAll: (listType: String, title: String) -> Unit = { _, _ -> },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = { Text("Home", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A0A0A)),
            )
        },
    ) { innerPadding ->
        when {
            state.error != null -> ErrorState(
                message = state.error!!,
                onRetry = viewModel::loadFeed,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            else -> {
                // Both loading and loaded share the same column structure so the
                // skeleton placeholders animate naturally into real content.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Spacer(Modifier.height(8.dp))

                    AlbumRow(
                        title          = "Recently Played",
                        albums         = state.recentlyPlayed,
                        isLoading      = state.isLoading,
                        getCoverArtUrl = viewModel::getCoverArtUrl,
                        onAlbumClick   = onAlbumClick,
                        onSeeAll       = { onSeeAll("recent", "Recently Played") },
                    )

                    AlbumRow(
                        title          = "New Additions",
                        albums         = state.newAdditions,
                        isLoading      = state.isLoading,
                        getCoverArtUrl = viewModel::getCoverArtUrl,
                        onAlbumClick   = onAlbumClick,
                        onSeeAll       = { onSeeAll("newest", "New Additions") },
                    )

                    AlbumRow(
                        title          = "Most Played",
                        albums         = state.mostPlayed,
                        isLoading      = state.isLoading,
                        getCoverArtUrl = viewModel::getCoverArtUrl,
                        onAlbumClick   = onAlbumClick,
                        onSeeAll       = { onSeeAll("frequent", "Most Played") },
                    )

                    GenreRow(
                        genres       = state.genres,
                        isLoading    = state.isLoading,
                        onGenreClick = onGenreClick,
                    )

                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
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
        SectionHeading(title, onSeeAll = onSeeAll)

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
                color = Color(0xFF8C8C8C),
                modifier = Modifier.padding(horizontal = RowPadding, vertical = 4.dp),
            )
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = RowPadding),
                horizontalArrangement = Arrangement.spacedBy(CardSpacing),
            ) {
                items(albums, key = { it.id }) { album ->
                    HomeAlbumCard(
                        album          = album,
                        coverArtUrl    = album.coverArt?.let { getCoverArtUrl(it, 300) },
                        onAlbumClick   = onAlbumClick,
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
    }
}

// ─── Genre section row ────────────────────────────────────────────────────────

@Composable
private fun GenreRow(
    genres: List<GenreDto>,
    isLoading: Boolean,
    onGenreClick: (String) -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeading("Genres")

        if (isLoading) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = RowPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(8) { SkeletonPill() }
            }
        } else if (genres.isEmpty()) {
            Text(
                text = "No genres found",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF8C8C8C),
                modifier = Modifier.padding(horizontal = RowPadding, vertical = 4.dp),
            )
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = RowPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(genres, key = { it.name }) { genre ->
                    SuggestionChip(
                        onClick = { onGenreClick(genre.name) },
                        label = {
                            Text(
                                text = genre.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                            )
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = Color(0xFF1E1E1E),
                        ),
                        border = SuggestionChipDefaults.suggestionChipBorder(
                            enabled = true,
                            borderColor = Color.White.copy(alpha = 0.2f),
                        ),
                    )
                }
            }
        }
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
                .clip(RoundedCornerShape(6.dp)),
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text      = album.name,
            fontSize  = 12.sp,
            fontWeight = FontWeight.Medium,
            color     = Color.White,
            maxLines  = 1,
            overflow  = TextOverflow.Ellipsis,
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

// ─── Section heading ──────────────────────────────────────────────────────────

@Composable
private fun SectionHeading(text: String, onSeeAll: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = RowPadding, end = 8.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text       = text,
            fontSize   = 18.sp,
            fontWeight = FontWeight.Bold,
            color      = Color.White,
        )
        if (onSeeAll != null) {
            TextButton(onClick = onSeeAll) {
                Text(
                    text  = "See all",
                    color = Color(0xFF8C8C8C),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

// ─── Skeleton placeholders ────────────────────────────────────────────────────

@Composable
private fun SkeletonCard() {
    Column(modifier = Modifier.width(CardWidth)) {
        Box(
            modifier = Modifier
                .size(CardWidth)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF1E1E1E)),
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(13.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0xFF1E1E1E)),
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(12.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0xFF1E1E1E)),
        )
    }
}

@Composable
private fun SkeletonPill() {
    Box(
        modifier = Modifier
            .width(72.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0xFF1E1E1E)),
    )
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
