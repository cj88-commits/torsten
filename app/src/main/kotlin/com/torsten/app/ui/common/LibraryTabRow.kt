package com.torsten.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class LibraryTab { ALBUMS, ARTISTS, PLAYLISTS }

/**
 * "Library" title bar + persistent tab pills (Albums / Artists / Playlists).
 * Drop into the Scaffold topBar slot of every library screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryHeader(
    currentTab: LibraryTab,
    onNavigateToAlbums: () -> Unit = {},
    onNavigateToArtists: () -> Unit = {},
    onNavigateToPlaylists: () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column {
        TopAppBar(
            title = { Text("Library", color = Color.White) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A0A0A)),
            actions = actions,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0A0A0A))
                .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LibraryTabPill(
                label = "Albums",
                selected = currentTab == LibraryTab.ALBUMS,
                onClick = onNavigateToAlbums,
                modifier = Modifier.weight(1f),
            )
            LibraryTabPill(
                label = "Artists",
                selected = currentTab == LibraryTab.ARTISTS,
                onClick = onNavigateToArtists,
                modifier = Modifier.weight(1f),
            )
            LibraryTabPill(
                label = "Playlists",
                selected = currentTab == LibraryTab.PLAYLISTS,
                onClick = onNavigateToPlaylists,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun LibraryTabPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) Color.White.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.5f),
        )
    }
}

// ─── Shared sheet composables (used by sort / jump bottom sheets) ──────────────

@Composable
fun SheetSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = Color.White.copy(alpha = 0.45f),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
fun LibrarySheetOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.65f),
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
