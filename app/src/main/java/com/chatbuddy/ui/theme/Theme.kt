package com.chatbuddy.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DB7FF),
    onPrimary = Color(0xFF0B2F73),
    primaryContainer = Color(0xFF244A9A),
    onPrimaryContainer = Color(0xFFDCE5FF),
    secondary = Color(0xFF9DD3C7),
    onSecondary = Color(0xFF00382F),
    background = Color(0xFF0D111C),
    surface = Color(0xFF121827),
    surfaceVariant = Color(0xFF252D3B),
    onSurface = Color(0xFFE5E7F0),
    onSurfaceVariant = Color(0xFFC2C6D3),
    error = Color(0xFFFFB4AB)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF315FCE),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE5FF),
    onPrimaryContainer = Color(0xFF001A4B),
    secondary = Color(0xFF286A5E),
    background = Color(0xFFF8F9FF),
    surface = Color(0xFFF8F9FF),
    surfaceVariant = Color(0xFFE1E5F0),
    onSurface = Color(0xFF181B22),
    onSurfaceVariant = Color(0xFF44474F)
)

@Composable
fun ChatBuddyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = androidx.compose.material3.Typography(),
        content = content
    )
}
