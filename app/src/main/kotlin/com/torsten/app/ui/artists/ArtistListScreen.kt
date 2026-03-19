package com.torsten.app.ui.artists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.torsten.app.data.db.entity.ArtistEntity
import com.torsten.app.ui.common.DarkBackground
import com.torsten.app.ui.common.LibraryTab
import com.torsten.app.ui.common.LibraryTabRow
import kotlinx.coroutines.launch

private val ThumbnailBackground = Color(0xFF0A0A0A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistListScreen(
    viewModel: ArtistListViewModel,
    onArtistClick: (artistId: String) -> Unit,
    onNavigateToAlbums: () -> Unit = {},
    onNavigateToPlaylists: () -> Unit = {},
) {
    val artists by viewModel.artists.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Build fast-scroll index: distinct letters and their first item positions
    val indexEntries = remember(artists) {
        val result = mutableListOf<Pair<String, Int>>()
        var lastLetter = ""
        artists.forEachIndexed { index, artist ->
            val letter = artistIndexLetter(artist.name)
            if (letter != lastLetter) {
                result.add(letter to index)
                lastLetter = letter
            }
        }
        result
    }

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = { Text("Artists", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A0A0A)),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            LibraryTabRow(
                selected = LibraryTab.ARTISTS,
                onAlbumsClick = onNavigateToAlbums,
                onArtistsClick = {},
                onPlaylistsClick = onNavigateToPlaylists,
            )
        Box(
            modifier = Modifier
                .fillMaxSize(),
        ) {
            if (artists.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No artists found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }
            } else {
                // Leave room for the fast-scroll index column (16dp)
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = 20.dp),
                ) {
                    items(artists, key = { it.id }) { artist ->
                        ArtistRow(
                            artist = artist,
                            onClick = { onArtistClick(artist.id) },
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    }
                }

                // Fast-scroll alphabet index pinned to the right.
                // Each letter gets weight(1f) so all entries share the available height equally —
                // this guarantees every letter is visible on any screen size without overflow.
                if (indexEntries.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .background(Color.Transparent)
                            .padding(end = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        indexEntries.forEach { (letter, itemIndex) ->
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        scope.launch { listState.scrollToItem(itemIndex) }
                                    }
                                    .padding(horizontal = 4.dp),
                            ) {
                                Text(
                                    text = letter,
                                    fontSize = 9.sp,
                                    color = Color.White.copy(alpha = 0.6f),
                                )
                            }
                        }
                    }
                }
            }
        }
        } // end Column
    }
}

@Composable
private fun ArtistRow(
    artist: ArtistEntity,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtistThumbnail(
            url = artist.artistImageUrl?.takeIf { it.isNotEmpty() },
            contentDescription = artist.name,
        )

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = artist.name,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
            )
            Text(
                text = "${artist.albumCount} ${if (artist.albumCount == 1) "album" else "albums"}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun ArtistThumbnail(url: String?, contentDescription: String) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(ThumbnailBackground),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            imageVector = Icons.Rounded.Person,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.25f),
            modifier = Modifier.size(28.dp),
        )
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(url)
                .crossfade(true)
                .build(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp)),
        )
    }
}
