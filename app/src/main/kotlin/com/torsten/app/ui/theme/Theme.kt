package com.torsten.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TorstenColorScheme = darkColorScheme(
    background = Color(0xFF0A0A0A),
    surface = Color(0xFF141414),
    surfaceVariant = Color(0xFF1E1E1E),
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF8C8C8C),
)

@Composable
fun TorstenTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = TorstenColorScheme,
        content = content,
    )
}
