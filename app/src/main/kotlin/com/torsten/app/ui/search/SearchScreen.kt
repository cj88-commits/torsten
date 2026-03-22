package com.torsten.app.ui.search

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.torsten.app.data.api.dto.AlbumDto
import com.torsten.app.data.api.dto.ArtistDto
import com.torsten.app.data.api.dto.SongDto
import com.torsten.app.ui.common.AlbumCoverArt
import com.torsten.app.ui.common.DarkBackground
import com.torsten.app.ui.playback.PlaybackViewModel
import com.torsten.app.ui.theme.Radius
import com.torsten.app.ui.theme.TorstenColor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val SurfaceVariant = Color(0xFF1A1A1A)
private val TextSecondary  = Color(0xFF8C8C8C)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    playbackViewModel: PlaybackViewModel,
    onAlbumClick: (albumId: String, albumTitle: String) -> Unit,
    onArtistClick: (artistId: String) -> Unit,
    onAddToPlaylist: (songId: String) -> Unit = {},
    onGenreClick: (genre: String) -> Unit = {},
    onStartInstantMix: (seed: SongDto) -> Unit = {},
) {
    val query   by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val recent  by viewModel.recentSearches.collectAsStateWithLifecycle()
    val genres  by viewModel.genres.collectAsStateWithLifecycle()

    val context       = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    var contextSong by remember { mutableStateOf<SongDto?>(null) }
    val contextSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Auto-focus the search field when the screen first appears
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    if (contextSong != null) {
        ModalBottomSheet(
            onDismissRequest = { contextSong = null },
            sheetState = contextSheetState,
            containerColor = TorstenColor.Surface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        ) {
            val song = contextSong!!
            Column(
                modifier = Modifier.padding(bottom = 24.dp),
            ) {
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
                        contextSong = null
                        coroutineScope.launch {
                            val config = viewModel.getServerConfig().first()
                            playbackViewModel.playSong(song, config)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null, tint = Color.White.copy(alpha = 0.7f))
                    Spacer(Modifier.width(12.dp))
                    Text("Play next", color = Color.White, modifier = Modifier.weight(1f))
                }
                TextButton(
                    onClick = {
                        val songId = contextSong?.id ?: return@TextButton
                        contextSong = null
                        onAddToPlaylist(songId)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null, tint = Color.White.copy(alpha = 0.7f))
                    Spacer(Modifier.width(12.dp))
                    Text("Add to playlist", color = Color.White, modifier = Modifier.weight(1f))
                }
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
            }
        }
    }

    Scaffold(
        containerColor = DarkBackground,
        contentColor   = Color.White,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // ── Search bar ─────────────────────────────────────────────────
            OutlinedTextField(
                value       = query,
                onValueChange = viewModel::setQuery,
                placeholder = { Text("Search tracks, albums, artists…", color = TextSecondary) },
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = TextSecondary)
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = viewModel::clearQuery) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear", tint = TextSecondary)
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor        = Color.White,
                    unfocusedTextColor      = Color.White,
                    focusedContainerColor   = TorstenColor.Surface,
                    unfocusedContainerColor = TorstenColor.Surface,
                    focusedBorderColor      = Color.White.copy(alpha = 0.4f),
                    unfocusedBorderColor    = Color.Transparent,
                    cursorColor             = Color.White,
                ),
                shape    = RoundedCornerShape(Radius.card),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .focusRequester(focusRequester),
            )

            // ── Content area ───────────────────────────────────────────────
            when {
                // Idle — show recent searches + browse by genre
                query.isBlank() -> {
                    IdleContent(
                        recentSearches   = recent,
                        genres           = genres,
                        onRecentClick    = viewModel::selectRecentSearch,
                        onRecentRemove   = viewModel::removeRecentSearch,
                        onClearAllRecent = viewModel::clearRecentSearches,
                        onGenreClick     = onGenreClick,
                    )
                }

                // Loading
                results.isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
                    }
                }

                // Error
                results.error != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text  = results.error!!,
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                // Results (or empty)
                results.hasSearched -> {
                    if (results.tracks.isEmpty() && results.albums.isEmpty() && results.artists.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text  = "No results for \"$query\"",
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    } else {
                        ResultsContent(
                            results     = results,
                            getCoverArtUrl = viewModel::getCoverArtUrl,
                            onTrackClick = { song ->
                                coroutineScope.launch {
                                    val config = viewModel.getServerConfig().first()
                                    playbackViewModel.playSong(song, config)
                                }
                            },
                            onTrackLongPress = { song -> contextSong = song },
                            onTrackMenuClick = { song -> contextSong = song },
                            onAlbumClick  = onAlbumClick,
                            onArtistClick = onArtistClick,
                            onSeeAllTracks  = { Toast.makeText(context, "See all tracks — coming soon", Toast.LENGTH_SHORT).show() },
                            onSeeAllAlbums  = { Toast.makeText(context, "See all albums — coming soon", Toast.LENGTH_SHORT).show() },
                            onSeeAllArtists = { Toast.makeText(context, "See all artists — coming soon", Toast.LENGTH_SHORT).show() },
                        )
                    }
                }
            }
        }
    }
}

// ─── Idle content: recent searches + genre grid ───────────────────────────────

@Composable
private fun IdleContent(
    recentSearches: List<String>,
    genres: List<com.torsten.app.data.api.dto.GenreDto>,
    onRecentClick: (String) -> Unit,
    onRecentRemove: (String) -> Unit,
    onClearAllRecent: () -> Unit,
    onGenreClick: (String) -> Unit = {},
) {
    LazyColumn(
        modifier      = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        // Recent searches
        if (recentSearches.isNotEmpty()) {
            item {
                SectionRow(
                    title     = "Recent Searches",
                    actionLabel = "Clear all",
                    onAction  = onClearAllRecent,
                )
            }
            items(recentSearches, key = { it }) { query ->
                RecentSearchRow(
                    query    = query,
                    onClick  = { onRecentClick(query) },
                    onRemove = { onRecentRemove(query) },
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        // Browse by genre
        if (genres.isNotEmpty()) {
            item {
                SectionRow(title = "Browse by Genre")
                Spacer(Modifier.height(4.dp))
            }
            item {
                GenreGrid(genres = genres, onGenreClick = { genre -> onGenreClick(genre.name) })
            }
        }
    }
}

@Composable
private fun RecentSearchRow(
    query: String,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.History,
            contentDescription = null,
            tint     = TextSecondary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text     = query,
            color    = Color.White,
            style    = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Clear, contentDescription = "Remove", tint = TextSecondary, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun GenreGrid(
    genres: List<com.torsten.app.data.api.dto.GenreDto>,
    onGenreClick: (com.torsten.app.data.api.dto.GenreDto) -> Unit,
) {
    Column(
        modifier            = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        genres.chunked(2).forEach { pair ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                pair.forEach { genre ->
                    GenreCard(
                        genre    = genre,
                        onClick  = { onGenreClick(genre) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Fill the second cell if the row has only one item
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun GenreCard(
    genre: com.torsten.app.data.api.dto.GenreDto,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(72.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(TorstenColor.ElevatedSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column {
            Text(
                text      = genre.name,
                color     = Color.White,
                style     = MaterialTheme.typography.bodyMedium,
                maxLines  = 1,
                overflow  = TextOverflow.Ellipsis,
            )
            Text(
                text    = "${genre.albumCount} albums",
                color   = TextSecondary,
                style   = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        }
    }
}

// ─── Search results ───────────────────────────────────────────────────────────

@Composable
private fun ResultsContent(
    results: SearchUiState,
    getCoverArtUrl: (id: String, size: Int) -> String?,
    onTrackClick: (SongDto) -> Unit,
    onTrackLongPress: (SongDto) -> Unit = {},
    onTrackMenuClick: (SongDto) -> Unit = {},
    onAlbumClick: (albumId: String, albumTitle: String) -> Unit,
    onArtistClick: (artistId: String) -> Unit,
    onSeeAllTracks: () -> Unit,
    onSeeAllAlbums: () -> Unit,
    onSeeAllArtists: () -> Unit,
) {
    LazyColumn(
        modifier       = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        // ── Tracks ────────────────────────────────────────────────────────
        if (results.tracks.isNotEmpty()) {
            item {
                SectionRow(title = "Tracks", actionLabel = "See all", onAction = onSeeAllTracks)
            }
            items(results.tracks, key = { "t_${it.id}" }) { song ->
                TrackResultRow(
                    song = song,
                    onClick = { onTrackClick(song) },
                    onLongPress = { onTrackLongPress(song) },
                    onMenuClick = { onTrackMenuClick(song) },
                )
            }
            item { ResultSectionDivider() }
        }

        // ── Artists ───────────────────────────────────────────────────────
        if (results.artists.isNotEmpty()) {
            item {
                SectionRow(title = "Artists", actionLabel = "See all", onAction = onSeeAllArtists)
            }
            items(results.artists, key = { "ar_${it.id}" }) { artist ->
                ArtistResultRow(
                    artist       = artist,
                    coverArtUrl  = artist.coverArt?.let { getCoverArtUrl(it, 150) },
                    onClick      = { onArtistClick(artist.id) },
                )
            }
            item { ResultSectionDivider() }
        }

        // ── Albums ────────────────────────────────────────────────────────
        if (results.albums.isNotEmpty()) {
            item {
                SectionRow(title = "Albums", actionLabel = "See all", onAction = onSeeAllAlbums)
            }
            items(results.albums, key = { "a_${it.id}" }) { album ->
                AlbumResultRow(
                    album          = album,
                    coverArtUrl    = album.coverArt?.let { getCoverArtUrl(it, 300) },
                    onClick        = { onAlbumClick(album.id, album.name) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackResultRow(
    song: SongDto,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
    onMenuClick: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text      = song.title,
                color     = Color.White,
                style     = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines  = 1,
                overflow  = TextOverflow.Ellipsis,
            )
            Text(
                text    = song.artist.orEmpty(),
                color   = TextSecondary,
                style   = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (song.duration != null && song.duration > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                text  = formatDuration(song.duration),
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        IconButton(onClick = onMenuClick, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "More options",
                tint = TextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun AlbumResultRow(album: AlbumDto, coverArtUrl: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumCoverArt(
            coverArtUrl        = coverArtUrl,
            coverArtId         = album.coverArt,
            contentDescription = album.name,
            isOnline           = true,
            modifier           = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text      = album.name,
                color     = Color.White,
                style     = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines  = 1,
                overflow  = TextOverflow.Ellipsis,
            )
            Text(
                text    = album.artist.orEmpty(),
                color   = TextSecondary,
                style   = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ArtistResultRow(artist: ArtistDto, coverArtUrl: String?, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Circle avatar: initials as fallback, image loaded on top
        Box(
            modifier         = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(TorstenColor.ElevatedSurface),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text       = artist.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                color      = TorstenColor.TextSecondary,
                style      = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (coverArtUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(coverArtUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = artist.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text      = artist.name,
                color     = Color.White,
                style     = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines  = 1,
                overflow  = TextOverflow.Ellipsis,
            )
            if (artist.albumCount > 0) {
                Text(
                    text    = "${artist.albumCount} albums",
                    color   = TextSecondary,
                    style   = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

// ─── Shared chrome ────────────────────────────────────────────────────────────

@Composable
private fun SectionRow(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier             = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment    = Alignment.CenterVertically,
    ) {
        Text(
            text       = title,
            fontSize   = 16.sp,
            fontWeight = FontWeight.Bold,
            color      = Color.White,
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(
                    text  = actionLabel,
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun ResultSectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(top = 8.dp),
        color    = Color.White.copy(alpha = 0.08f),
    )
}

private fun formatDuration(seconds: Int): String =
    "%d:%02d".format(seconds / 60, seconds % 60)
