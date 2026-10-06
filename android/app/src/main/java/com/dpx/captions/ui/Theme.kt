package com.dpx.captions.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

/** The same yellow the captions highlight with by default, so the app looks like its own output. */
val Accent = Color(0xFFFFE629)
val Surface0 = Color(0xFF0B0D10)
val Surface1 = Color(0xFF14171C)
val Surface2 = Color(0xFF1C2027)
val Surface3 = Color(0xFF262B34)
val OnSurfaceDim = Color(0xFF9AA3B0)
val Danger = Color(0xFFFF5A5F)
val CaptionBlue = Color(0xFF3F6FD6)
val CaptionBlueSelected = Color(0xFF5B8CFF)
val Waveform = Color(0xFF4CC38A)

private val scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF161200),
    primaryContainer = Color(0xFF3A3300),
    onPrimaryContainer = Accent,
    secondary = Color(0xFF8FB4FF),
    background = Surface0,
    onBackground = Color(0xFFEDEFF3),
    surface = Surface1,
    onSurface = Color(0xFFEDEFF3),
    surfaceVariant = Surface2,
    onSurfaceVariant = OnSurfaceDim,
    surfaceContainer = Surface2,
    surfaceContainerHigh = Surface3,
    surfaceContainerHighest = Surface3,
    outline = Color(0xFF3A404B),
    outlineVariant = Color(0xFF2A2F38),
    error = Danger,
)

private val typography = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

private val shapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
)

@Composable
fun DpxTheme(content: @Composable () -> Unit) {
    // The editor is a dark, video-first surface; a light variant would only wash out the preview.
    MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
}
