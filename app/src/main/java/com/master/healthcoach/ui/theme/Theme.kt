package com.master.healthcoach.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF276749),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8F3E5),
    onPrimaryContainer = Color(0xFF092D20),
    secondary = Color(0xFF52645A),
    secondaryContainer = Color(0xFFD5E8DB),
    tertiary = Color(0xFF5D5B8D),
    background = Color(0xFFF8FAF8),
    surface = Color(0xFFF8FAF8),
    surfaceVariant = Color(0xFFE7EDE8),
    outline = Color(0xFF727A74),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CD5B7),
    onPrimary = Color(0xFF003824),
    primaryContainer = Color(0xFF0E5135),
    onPrimaryContainer = Color(0xFFD8F3E5),
    secondary = Color(0xFFB9CCBE),
    tertiary = Color(0xFFC6C2FA),
)

@Composable
fun HealthCoachTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}

