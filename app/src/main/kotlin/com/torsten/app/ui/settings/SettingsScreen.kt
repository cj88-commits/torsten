package com.torsten.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.torsten.app.BuildConfig

private val TopBarBackground = Color(0xFF0A0A0A)
private val DarkBackground = Color(0xFF111111)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateUp: (() -> Unit)?,
    onNavigateToServerConfig: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.snackbarEvent.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    if (state.showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissClearCacheDialog,
            title = { Text("Clear image cache?") },
            text = { Text("All cached cover art will be deleted and re-downloaded as needed.") },
            confirmButton = {
                TextButton(
                    onClick = viewModel::clearImageCache,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissClearCacheDialog) { Text("Cancel") }
            },
        )
    }

    if (state.showClearDownloadsDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissClearDownloadsDialog,
            title = { Text("Clear all downloads?") },
            text = { Text("All downloaded albums will be deleted from this device. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = viewModel::clearAllDownloads,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Clear all")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissClearDownloadsDialog) {
                    Text("Cancel")
                }
            },
        )
    }

    Scaffold(
        containerColor = DarkBackground,
        contentColor = Color.White,
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(snackbarData = data)
            }
        },
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    if (onNavigateUp != null) {
                        IconButton(onClick = onNavigateUp) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TopBarBackground,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .imePadding(),
        ) {
            // ═══════════════════════════════════════════════════
            // SERVER
            // ═══════════════════════════════════════════════════
            SectionHeader("SERVER")

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onNavigateToServerConfig)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Server Configuration", style = MaterialTheme.typography.bodyMedium)
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }

            // ═══════════════════════════════════════════════════
            // STREAMING
            // ═══════════════════════════════════════════════════
            SectionDivider()
            SectionHeader("STREAMING")

            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                QualityDropdown(
                    label = "WiFi Quality",
                    selected = state.wifiQuality,
                    options = WifiQuality.entries,
                    labelOf = { it.label },
                    onSelect = viewModel::setWifiQuality,
                )
                QualityDropdown(
                    label = "Mobile Quality",
                    selected = state.mobileQuality,
                    options = MobileQuality.entries,
                    labelOf = { it.label },
                    onSelect = viewModel::setMobileQuality,
                )
            }

            // ═══════════════════════════════════════════════════
            // DOWNLOADS
            // ═══════════════════════════════════════════════════
            SectionDivider()
            SectionHeader("DOWNLOADS")

            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SwitchRow(
                    label = "Download over WiFi only",
                    checked = state.wifiOnlyDownload,
                    onCheckedChange = viewModel::setWifiOnlyDownload,
                )
                QualityDropdown(
                    label = "Download Format",
                    selected = state.downloadFormat,
                    options = DownloadFormat.entries,
                    labelOf = { it.label },
                    onSelect = viewModel::setDownloadFormat,
                )
                if (state.downloadBitRateEnabled) {
                    QualityDropdown(
                        label = "Download Bitrate",
                        selected = state.downloadBitRate,
                        options = DownloadBitRate.entries,
                        labelOf = { it.label },
                        onSelect = viewModel::setDownloadBitRate,
                    )
                }
                OutlinedButton(
                    onClick = viewModel::showClearDownloadsDialog,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Clear all downloads")
                }
            }

            // ═══════════════════════════════════════════════════
            // PLAYBACK
            // ═══════════════════════════════════════════════════
            SectionDivider()
            SectionHeader("PLAYBACK")

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                SwitchRow(
                    label = "Album ReplayGain normalisation",
                    checked = state.replayGainEnabled,
                    onCheckedChange = viewModel::setReplayGainEnabled,
                )
            }

            // ═══════════════════════════════════════════════════
            // SCROBBLING
            // ═══════════════════════════════════════════════════
            SectionDivider()
            SectionHeader("SCROBBLING")

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                SwitchRow(
                    label = "Enable scrobbling",
                    description = "Submit played tracks to your server (last.fm via Subsonic)",
                    checked = state.scrobblingEnabled,
                    onCheckedChange = viewModel::setScrobblingEnabled,
                )
            }

            // ═══════════════════════════════════════════════════
            // IMAGE CACHE
            // ═══════════════════════════════════════════════════
            SectionDivider()
            SectionHeader("IMAGE CACHE")

            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Current size: ${state.currentCacheSizeMb} MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                QualityDropdown(
                    label = "Cache size limit",
                    selected = state.imageCacheSizeLimit,
                    options = ImageCacheSizeLimit.entries,
                    labelOf = { it.label },
                    onSelect = viewModel::setImageCacheSizeLimit,
                )
                Text(
                    text = "Limit takes effect on next app launch",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
                OutlinedButton(
                    onClick = viewModel::showClearCacheDialog,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Clear image cache")
                }
            }

            // ═══════════════════════════════════════════════════
            // ABOUT
            // ═══════════════════════════════════════════════════
            SectionDivider()
            SectionHeader("ABOUT")

            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Torsten", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Version ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ─── Section chrome ───────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(top = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

// ─── Reusable row components ──────────────────────────────────────────────────

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> QualityDropdown(
    label: String,
    selected: T,
    options: List<T>,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = labelOf(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(labelOf(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}
