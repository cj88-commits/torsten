package com.torsten.app.ui.random

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.torsten.app.ui.common.AlbumCardItem
import com.torsten.app.ui.common.DarkBackground

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RandomScreen(
    viewModel: RandomViewModel,
    onAlbumClick: (albumId: String, albumTitle: String) -> Unit,
) {
    val albums by viewModel.randomAlbums.collectAsStateWithLifecycle()
    val isShuffling by viewModel.isShuffling.collectAsStateWithLifecycle()
    val gridAlpha by animateFloatAsState(
        targetValue = if (isShuffling) 0.35f else 1f,
        animationSpec = tween(durationMillis = 150),
        label = "shuffle_alpha",
    )

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = { Text("Random", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A0A0A)),
                actions = {
                    IconButton(onClick = viewModel::shuffle) {
                        Icon(
                            imageVector = Icons.Filled.Shuffle,
                            contentDescription = "Shuffle",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        if (albums.isEmpty() && !isShuffling) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No albums in library",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .alpha(gridAlpha),
            ) {
                items(albums, key = { it.id }) { album ->
                    AlbumCardItem(
                        album = album,
                        coverArtUrl = album.coverArtId?.let { viewModel.getCoverArtUrl(it, 300) },
                        isOnline = true,
                        onClick = onAlbumClick,
                        onOfflineBlocked = {},
                        subtitle = album.artistName,
                    )
                }
            }
        }
    }
}
