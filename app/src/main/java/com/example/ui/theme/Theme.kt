package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val CosmicColorScheme = lightColorScheme(
    primary = AccentBlue,
    secondary = AccentOrange,
    tertiary = AccentGreen,
    background = CosmicBackground,
    surface = CosmicSurface,
    onPrimary = CosmicSurface,
    onSecondary = CosmicSurface,
    onTertiary = CosmicSurface,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = CosmicColorScheme,
        typography = Typography,
        content = content
    )
}
