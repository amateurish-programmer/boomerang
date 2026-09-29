package com.boomerang.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF9D302B), onPrimary = Color(0xFFFFFAF1),
    primaryContainer = Color(0xFFEEE0D5), onPrimaryContainer = Color(0xFF75231F),
    secondary = Color(0xFF625D55), onSecondary = Color(0xFFFFFAF1),
    secondaryContainer = Color(0xFFE8E2D7), onSecondaryContainer = Color(0xFF24231F),
    tertiary = Color(0xFF42634E), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDCE8D9), onTertiaryContainer = Color(0xFF203A29),
    background = Color(0xFFF5F1E8), onBackground = Color(0xFF24231F),
    surface = Color(0xFFF5F1E8), onSurface = Color(0xFF24231F),
    surfaceVariant = Color(0xFFE8E2D7), onSurfaceVariant = Color(0xFF625D55),
    surfaceTint = Color.Transparent, outline = Color(0xFF827A6E), outlineVariant = Color(0xFFD9D3C7),
    surfaceContainerLowest = Color(0xFFFCF9F2), surfaceContainerLow = Color(0xFFF5F1E8),
    surfaceContainer = Color(0xFFF0EBE1), surfaceContainerHigh = Color(0xFFECE6DA), surfaceContainerHighest = Color(0xFFE8E2D7),
    inverseSurface = Color(0xFF30312A), inverseOnSurface = Color(0xFFF0ECDF), inversePrimary = Color(0xFFD99A85),
    error = Color(0xFF9D302B), onError = Color.White, errorContainer = Color(0xFFFFDAD4), onErrorContainer = Color(0xFF681B17),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFD99A85), onPrimary = Color(0xFF291A14),
    primaryContainer = Color(0xFF49372F), onPrimaryContainer = Color(0xFFF2CDBE),
    secondary = Color(0xFFBCB8AA), onSecondary = Color(0xFF222520),
    secondaryContainer = Color(0xFF383B33), onSecondaryContainer = Color(0xFFF0ECDF),
    tertiary = Color(0xFFA4BF9E), onTertiary = Color(0xFF203A29),
    tertiaryContainer = Color(0xFF344B35), onTertiaryContainer = Color(0xFFDCE8D9),
    background = Color(0xFF222520), onBackground = Color(0xFFF0ECDF),
    surface = Color(0xFF222520), onSurface = Color(0xFFF0ECDF),
    surfaceVariant = Color(0xFF383B33), onSurfaceVariant = Color(0xFFBCB8AA),
    surfaceTint = Color.Transparent, outline = Color(0xFF8E9586), outlineVariant = Color(0xFF42483E),
    surfaceContainerLowest = Color(0xFF1D201B), surfaceContainerLow = Color(0xFF222520),
    surfaceContainer = Color(0xFF292D27), surfaceContainerHigh = Color(0xFF30352D), surfaceContainerHighest = Color(0xFF383B33),
    inverseSurface = Color(0xFFF0ECDF), inverseOnSurface = Color(0xFF24231F), inversePrimary = Color(0xFF9D302B),
    error = Color(0xFFFFB4A8), onError = Color(0xFF601B17), errorContainer = Color(0xFF74332C), onErrorContainer = Color(0xFFFFDAD4),
)
private val BaseTypography = Typography()
private val InkTypography = Typography(
    displayLarge = BaseTypography.displayLarge.copy(fontFamily = FontFamily.Serif),
    displayMedium = BaseTypography.displayMedium.copy(fontFamily = FontFamily.Serif),
    displaySmall = BaseTypography.displaySmall.copy(fontFamily = FontFamily.Serif),
    headlineLarge = BaseTypography.headlineLarge.copy(fontFamily = FontFamily.Serif),
    headlineMedium = BaseTypography.headlineMedium.copy(fontFamily = FontFamily.Serif),
    headlineSmall = BaseTypography.headlineSmall.copy(fontFamily = FontFamily.Serif),
    titleLarge = BaseTypography.titleLarge.copy(fontFamily = FontFamily.Serif),
    titleMedium = BaseTypography.titleMedium.copy(fontFamily = FontFamily.Serif),
    titleSmall = BaseTypography.titleSmall.copy(fontFamily = FontFamily.Serif),
    bodyLarge = BaseTypography.bodyLarge.copy(fontFamily = FontFamily.SansSerif),
    bodyMedium = BaseTypography.bodyMedium.copy(fontFamily = FontFamily.SansSerif),
    bodySmall = BaseTypography.bodySmall.copy(fontFamily = FontFamily.SansSerif),
)
@Composable
fun BoomerangTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, typography = InkTypography,
        shapes = Shapes(RoundedCornerShape(8.dp), RoundedCornerShape(8.dp), RoundedCornerShape(10.dp),
            RoundedCornerShape(12.dp), RoundedCornerShape(12.dp)), content = content)
}
