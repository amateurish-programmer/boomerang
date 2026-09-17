package com.boomerang.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFFAA350E), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCD), onPrimaryContainer = Color(0xFF3A0B00),
    background = Color(0xFFFFF9F5), surface = Color(0xFFFFF9F5),
    onSurface = Color(0xFF25221F), onBackground = Color(0xFF25221F),
    secondaryContainer = Color(0xFFFFDBCD), onSecondaryContainer = Color(0xFF3A0B00),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFAC8C), onPrimary = Color(0xFF5E1700),
    primaryContainer = Color(0xFF842600), onPrimaryContainer = Color(0xFFFFDBCD),
    background = Color(0xFF191715), surface = Color(0xFF191715),
    onSurface = Color(0xFFF2E7DF), onBackground = Color(0xFFF2E7DF),
    secondaryContainer = Color(0xFF613B2D), onSecondaryContainer = Color(0xFFFFDBCD),
)

@Composable
fun BoomerangTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
