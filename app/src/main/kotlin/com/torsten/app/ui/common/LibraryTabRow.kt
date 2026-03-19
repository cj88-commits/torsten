package com.torsten.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class LibraryTab { ALBUMS, ARTISTS, PLAYLISTS }

@Composable
fun LibraryTabRow(
    selected: LibraryTab,
    onAlbumsClick: () -> Unit,
    onArtistsClick: () -> Unit,
    onPlaylistsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = Color.White,
        selectedLabelColor = Color.Black,
        containerColor = Color(0xFF1A1A1A),
        labelColor = Color.White.copy(alpha = 0.7f),
    )
    val chipBorder = FilterChipDefaults.filterChipBorder(
        enabled = true,
        selected = false,
        borderColor = Color.White.copy(alpha = 0.15f),
        selectedBorderColor = Color.Transparent,
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == LibraryTab.ALBUMS,
            onClick = onAlbumsClick,
            label = { Text("Albums") },
            colors = chipColors,
            border = chipBorder,
        )
        FilterChip(
            selected = selected == LibraryTab.ARTISTS,
            onClick = onArtistsClick,
            label = { Text("Artists") },
            colors = chipColors,
            border = chipBorder,
        )
        FilterChip(
            selected = selected == LibraryTab.PLAYLISTS,
            onClick = onPlaylistsClick,
            label = { Text("Playlists") },
            colors = chipColors,
            border = chipBorder,
        )
    }
}
