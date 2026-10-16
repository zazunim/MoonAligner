package com.moonaligner.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = MoonSurface,
    secondary = SunGold,
    tertiary = AccentOrange,
    background = NightSkyBackground,
    surface = SurfaceDark,
    onPrimary = NightSkyBackground,
    onSecondary = NightSkyBackground,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun MoonAlignerTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
