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
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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

// ─── Top result (best match for "All" view) ───────────────────────────────────

private sealed class TopResult {
    data class Track(val song: SongDto) : TopResult()
    data class Artist(val artist: ArtistDto) : TopResult()
    data class Album(val album: AlbumDto) : TopResult()
}

private fun computeTopResult(results: SearchUiState, query: String): TopResult? {
    val q = query.trim()
    // Exact name match wins
    results.artists.firstOrNull { it.name.equals(q, ignoreCase = true) }
        ?.let { return TopResult.Artist(it) }
    results.tracks.firstOrNull { it.title.equals(q, ignoreCase = true) }
        ?.let { return TopResult.Track(it) }
    results.albums.firstOrNull { it.name.equals(q, ignoreCase = true) }
        ?.let { return TopResult.Album(it) }
    // Fallback: first available result across categories
    results.artists.firstOrNull()?.let { return TopResult.Artist(it) }
    results.tracks.firstOrNull()?.let { return TopResult.Track(it) }
    results.albums.firstOrNull()?.let { return TopResult.Album(it) }
    return null
}

// ─── Screen ───────────────────────────────────────────────────────────────────

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

    val context        = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    var contextSong by remember { mutableStateOf<SongDto?>(null) }
    val contextSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    // Context menu bottom sheet
    if (contextSong != null) {
        ModalBottomSheet(
            onDismissRequest = { contextSong = null },
            sheetState = contextSheetState,
            containerColor = TorstenColor.Surface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        ) {
            val song = contextSong!!
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
            // ── Search bar ────────────────────────────────────────────────
            OutlinedTextField(
                value         = query,
                onValueChange = viewModel::setQuery,
                placeholder   = { Text("Search…", color = TextSecondary) },
                leadingIcon   = {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = TextSecondary)
                },
                trailingIcon  = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = viewModel::clearQuery) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear", tint = TextSecondary)
                        }
                    }
                },
                singleLine      = true,
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

            // ── Filter chips (visible whenever there's an active query) ───
            if (query.isNotBlank()) {
                FilterChipRow(
                    activeFilter   = results.activeFilter,
                    onFilterChange = viewModel::setFilter,
                )
            }

            // ── Content area ──────────────────────────────────────────────
            when {
                query.isBlank() -> IdleContent(
                    recentSearches   = recent,
                    genres           = genres,
                    onRecentClick    = viewModel::selectRecentSearch,
                    onRecentRemove   = viewModel::removeRecentSearch,
                    onClearAllRecent = viewModel::clearRecentSearches,
                    onGenreClick     = onGenreClick,
                )

                results.isLoading -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
                }

                results.error != null -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text  = results.error!!,
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                results.hasSearched -> {
                    val empty = results.tracks.isEmpty() && results.albums.isEmpty() && results.artists.isEmpty()
                    if (empty) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text  = "No results for \"$query\"",
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    } else {
                        FilteredResults(
                            query          = query,
                            results        = results,
                            getCoverArtUrl = viewModel::getCoverArtUrl,
                            onTrackClick   = { song ->
                                coroutineScope.launch {
                                    val config = viewModel.getServerConfig().first()
                                    playbackViewModel.playSong(song, config)
                                }
                            },
                            onTrackLongPress = { song -> contextSong = song },
                            onTrackMenuClick = { song -> contextSong = song },
                            onAlbumClick     = onAlbumClick,
                            onArtistClick    = onArtistClick,
                            onFilterChange   = viewModel::setFilter,
                        )
                    }
                }
            }
        }
    }
}

// ─── Filter chip row ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterChipRow(
    activeFilter: SearchFilter,
    onFilterChange: (SearchFilter) -> Unit,
) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SearchFilter.entries.forEach { filter ->
            FilterChip(
                selected = activeFilter == filter,
                onClick  = { onFilterChange(filter) },
                label    = { Text(filter.name, style = MaterialTheme.typography.labelMedium) },
                colors   = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color.White,
                    selectedLabelColor     = Color.Black,
                    containerColor         = TorstenColor.Surface,
                    labelColor             = Color.White,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled          = true,
                    selected         = activeFilter == filter,
                    borderColor      = Color.White.copy(alpha = 0.2f),
                    selectedBorderColor = Color.Transparent,
                ),
            )
        }
    }
}

// ─── Filtered results dispatcher ─────────────────────────────────────────────

@Composable
private fun FilteredResults(
    query: String,
    results: SearchUiState,
    getCoverArtUrl: (id: String, size: Int) -> String?,
    onTrackClick: (SongDto) -> Unit,
    onTrackLongPress: (SongDto) -> Unit,
    onTrackMenuClick: (SongDto) -> Unit,
    onAlbumClick: (albumId: String, albumTitle: String) -> Unit,
    onArtistClick: (artistId: String) -> Unit,
    onFilterChange: (SearchFilter) -> Unit,
) {
    when (results.activeFilter) {
        SearchFilter.All -> AllResultsContent(
            query          = query,
            results        = results,
            getCoverArtUrl = getCoverArtUrl,
            onTrackClick   = onTrackClick,
            onTrackLongPress = onTrackLongPress,
            onTrackMenuClick = onTrackMenuClick,
            onAlbumClick   = onAlbumClick,
            onArtistClick  = onArtistClick,
            onShowAllTracks  = { onFilterChange(SearchFilter.Tracks) },
            onShowAllArtists = { onFilterChange(SearchFilter.Artists) },
            onShowAllAlbums  = { onFilterChange(SearchFilter.Albums) },
        )
        SearchFilter.Tracks -> TrackListContent(
            tracks         = results.tracks,
            getCoverArtUrl = getCoverArtUrl,
            onTrackClick   = onTrackClick,
            onTrackLongPress = onTrackLongPress,
            onTrackMenuClick = onTrackMenuClick,
        )
        SearchFilter.Artists -> ArtistListContent(
            artists        = results.artists,
            getCoverArtUrl = getCoverArtUrl,
            onArtistClick  = onArtistClick,
        )
        SearchFilter.Albums -> AlbumListContent(
            albums         = results.albums,
            getCoverArtUrl = getCoverArtUrl,
            onAlbumClick   = onAlbumClick,
        )
    }
}

// ─── "All" view ───────────────────────────────────────────────────────────────

@Composable
private fun AllResultsContent(
    query: String,
    results: SearchUiState,
    getCoverArtUrl: (id: String, size: Int) -> String?,
    onTrackClick: (SongDto) -> Unit,
    onTrackLongPress: (SongDto) -> Unit,
    onTrackMenuClick: (SongDto) -> Unit,
    onAlbumClick: (albumId: String, albumTitle: String) -> Unit,
    onArtistClick: (artistId: String) -> Unit,
    onShowAllTracks: () -> Unit,
    onShowAllArtists: () -> Unit,
    onShowAllAlbums: () -> Unit,
) {
    val topResult = remember(results, query) { computeTopResult(results, query) }

    LazyColumn(
        modifier       = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
    ) {
        // ── Top Result ────────────────────────────────────────────────────
        if (topResult != null) {
            item {
                TopResultCard(
                    topResult      = topResult,
                    getCoverArtUrl = getCoverArtUrl,
                    onTrackClick   = onTrackClick,
                    onAlbumClick   = onAlbumClick,
                    onArtistClick  = onArtistClick,
                    modifier       = Modifier.padding(bottom = 8.dp),
                )
            }
        }

        // ── Artists (up to 3) ─────────────────────────────────────────────
        if (results.artists.isNotEmpty()) {
            item {
                TappableSectionHeader(
                    title   = "Artists",
                    showChevron = results.artists.size > 3,
                    onClick = onShowAllArtists,
                )
            }
            items(results.artists.take(3), key = { "ar_${it.id}" }) { artist ->
                ArtistResultRow(
                    artist      = artist,
                    coverArtUrl = artist.coverArt?.let { getCoverArtUrl(it, 150) },
                    onClick     = { onArtistClick(artist.id) },
                )
            }
            item { Spacer(Modifier.height(4.dp)) }
        }

        // ── Albums (up to 3) ──────────────────────────────────────────────
        if (results.albums.isNotEmpty()) {
            item {
                TappableSectionHeader(
                    title   = "Albums",
                    showChevron = results.albums.size > 3,
                    onClick = onShowAllAlbums,
                )
            }
            items(results.albums.take(3), key = { "al_${it.id}" }) { album ->
                AlbumResultRow(
                    album       = album,
                    coverArtUrl = album.coverArt?.let { getCoverArtUrl(it, 300) },
                    onClick     = { onAlbumClick(album.id, album.name) },
                )
            }
            item { Spacer(Modifier.height(4.dp)) }
        }

        // ── Tracks (up to 5) ──────────────────────────────────────────────
        if (results.tracks.isNotEmpty()) {
            item {
                TappableSectionHeader(
                    title   = "Tracks",
                    showChevron = results.tracks.size > 5,
                    onClick = onShowAllTracks,
                )
            }
            items(results.tracks.take(5), key = { "t_${it.id}" }) { song ->
                TrackResultRow(
                    song         = song,
                    coverArtUrl  = song.coverArt?.let { getCoverArtUrl(it, 150) },
                    onClick      = { onTrackClick(song) },
                    onLongPress  = { onTrackLongPress(song) },
                    onMenuClick  = { onTrackMenuClick(song) },
                )
            }
        }
    }
}

// ─── Filter-specific full-list views ─────────────────────────────────────────

@Composable
private fun TrackListContent(
    tracks: List<SongDto>,
    getCoverArtUrl: (id: String, size: Int) -> String?,
    onTrackClick: (SongDto) -> Unit,
    onTrackLongPress: (SongDto) -> Unit,
    onTrackMenuClick: (SongDto) -> Unit,
) {
    LazyColumn(
        modifier       = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
    ) {
        items(tracks, key = { "t_${it.id}" }) { song ->
            TrackResultRow(
                song         = song,
                coverArtUrl  = song.coverArt?.let { getCoverArtUrl(it, 150) },
                onClick      = { onTrackClick(song) },
                onLongPress  = { onTrackLongPress(song) },
                onMenuClick  = { onTrackMenuClick(song) },
            )
        }
    }
}

@Composable
private fun ArtistListContent(
    artists: List<ArtistDto>,
    getCoverArtUrl: (id: String, size: Int) -> String?,
    onArtistClick: (artistId: String) -> Unit,
) {
    LazyColumn(
        modifier       = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
    ) {
        items(artists, key = { "ar_${it.id}" }) { artist ->
            ArtistResultRow(
                artist      = artist,
                coverArtUrl = artist.coverArt?.let { getCoverArtUrl(it, 150) },
                onClick     = { onArtistClick(artist.id) },
            )
        }
    }
}

@Composable
private fun AlbumListContent(
    albums: List<AlbumDto>,
    getCoverArtUrl: (id: String, size: Int) -> String?,
    onAlbumClick: (albumId: String, albumTitle: String) -> Unit,
) {
    LazyColumn(
        modifier       = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
    ) {
        items(albums, key = { "al_${it.id}" }) { album ->
            AlbumResultRow(
                album       = album,
                coverArtUrl = album.coverArt?.let { getCoverArtUrl(it, 300) },
                onClick     = { onAlbumClick(album.id, album.name) },
            )
        }
    }
}

// ─── Top Result card ──────────────────────────────────────────────────────────

@Composable
private fun TopResultCard(
    topResult: TopResult,
    getCoverArtUrl: (id: String, size: Int) -> String?,
    onTrackClick: (SongDto) -> Unit,
    onAlbumClick: (albumId: String, albumTitle: String) -> Unit,
    onArtistClick: (artistId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val (typeLabel, title, subtitle, onClick) = when (topResult) {
        is TopResult.Track  -> Quadruple(
            "SONG",
            topResult.song.title,
            topResult.song.artist.orEmpty(),
            { onTrackClick(topResult.song) },
        )
        is TopResult.Artist -> Quadruple(
            "ARTIST",
            topResult.artist.name,
            if (topResult.artist.albumCount > 0) "${topResult.artist.albumCount} albums" else "",
            { onArtistClick(topResult.artist.id) },
        )
        is TopResult.Album  -> Quadruple(
            "ALBUM",
            topResult.album.name,
            topResult.album.artist.orEmpty(),
            { onAlbumClick(topResult.album.id, topResult.album.name) },
        )
    }

    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        Text(
            text  = "Top Result",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(SurfaceVariant)
                .clickable(onClick = onClick)
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Art
                when (topResult) {
                    is TopResult.Artist -> {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(TorstenColor.ElevatedSurface),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text       = topResult.artist.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                                fontSize   = 28.sp,
                                fontWeight = FontWeight.Bold,
                                color      = TorstenColor.TextSecondary,
                            )
                            val coverUrl = topResult.artist.coverArt?.let { getCoverArtUrl(it, 300) }
                            if (coverUrl != null) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context).data(coverUrl).crossfade(true).build(),
                                    contentDescription = topResult.artist.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                                )
                            }
                        }
                    }
                    is TopResult.Track -> {
                        val coverUrl = topResult.song.coverArt?.let { getCoverArtUrl(it, 300) }
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(TorstenColor.ElevatedSurface),
                        ) {
                            if (coverUrl != null) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context).data(coverUrl).crossfade(true).build(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                    is TopResult.Album -> {
                        val coverUrl = topResult.album.coverArt?.let { getCoverArtUrl(it, 300) }
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(TorstenColor.ElevatedSurface),
                        ) {
                            if (coverUrl != null) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context).data(coverUrl).crossfade(true).build(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text  = typeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    Text(
                        text       = title,
                        fontSize   = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color      = Color.White,
                        maxLines   = 2,
                        overflow   = TextOverflow.Ellipsis,
                    )
                    if (subtitle.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text     = subtitle,
                            style    = MaterialTheme.typography.bodySmall,
                            color    = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

// ─── Tappable section header (used in "All" view) ────────────────────────────

@Composable
private fun TappableSectionHeader(
    title: String,
    showChevron: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (showChevron) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = 12.dp, top = 16.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Text(
            text       = title,
            fontSize   = 16.sp,
            fontWeight = FontWeight.Bold,
            color      = Color.White,
        )
        if (showChevron) {
            Icon(
                imageVector        = Icons.Filled.ChevronRight,
                contentDescription = "See all $title",
                tint               = TextSecondary,
                modifier           = Modifier.size(20.dp),
            )
        }
    }
}

// ─── Result rows ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackResultRow(
    song: SongDto,
    coverArtUrl: String?,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
    onMenuClick: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Cover art
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(TorstenColor.ElevatedSurface),
        ) {
            if (coverArtUrl != null) {
                AsyncImage(
                    model        = coverArtUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier     = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = song.title,
                color      = Color.White,
                style      = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
            Text(
                text     = song.artist.orEmpty(),
                color    = TextSecondary,
                style    = MaterialTheme.typography.bodySmall,
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
                imageVector        = Icons.Filled.MoreVert,
                contentDescription = "More options",
                tint               = TextSecondary,
                modifier           = Modifier.size(18.dp),
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
                text       = album.name,
                color      = Color.White,
                style      = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
            Text(
                text     = album.artist.orEmpty(),
                color    = TextSecondary,
                style    = MaterialTheme.typography.bodySmall,
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
        Box(
            modifier = Modifier
                .size(44.dp)
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
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = artist.name,
                color      = Color.White,
                style      = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
            if (artist.albumCount > 0) {
                Text(
                    text  = "${artist.albumCount} albums",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

// ─── Idle content ─────────────────────────────────────────────────────────────

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
        modifier       = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        if (recentSearches.isNotEmpty()) {
            item {
                SectionRow(
                    title       = "Recent Searches",
                    actionLabel = "Clear all",
                    onAction    = onClearAllRecent,
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
        Icon(Icons.Filled.History, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(text = query, color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
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
                modifier              = Modifier.fillMaxWidth(),
            ) {
                pair.forEach { genre ->
                    GenreCard(genre = genre, onClick = { onGenreClick(genre) }, modifier = Modifier.weight(1f))
                }
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
            Text(text = genre.name, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text = "${genre.albumCount} albums", color = TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
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
        modifier              = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(text = actionLabel, color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private fun formatDuration(seconds: Int): String =
    "%d:%02d".format(seconds / 60, seconds % 60)

// ─── Helpers ──────────────────────────────────────────────────────────────────

/** Minimal data tuple to avoid repeated destructuring boilerplate in TopResultCard. */
private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
