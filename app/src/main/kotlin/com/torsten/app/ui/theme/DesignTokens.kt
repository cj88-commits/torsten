package com.torsten.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Spacing {
    val screenHorizontal = 20.dp
    val sectionGap = 24.dp
    val itemGap = 12.dp
    val cardPadding = 8.dp
}

object Radius {
    val card = 16.dp
    val chip = 8.dp
    val small = 4.dp
}

object TorstenTypography {
    val heroTitle = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold)
    val sectionTitle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold)
    val itemTitle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    val itemSubtitle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal)
    val label = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal)
}

object TorstenColor {
    val Background = Color(0xFF0A0A0A)
    val Surface = Color(0xFF111111)
    val ElevatedSurface = Color(0xFF1A1A1A)
    val Overlay = Color(0xFF222222)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFF8C8C8C)
    val TextTertiary = Color(0xFF555555)
    val Accent = Color(0xFFFFFFFF)
    val Success = Color(0xFF4CAF50)
    val Warning = Color(0xFFFF9800)
    val Error = Color(0xFFF44336)
}
