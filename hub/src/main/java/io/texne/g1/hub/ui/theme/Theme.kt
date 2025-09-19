package io.texne.g1.hub.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val G1DarkColorScheme = darkColorScheme(
    primary = G1Primary,
    onPrimary = G1OnPrimary,
    primaryContainer = G1PrimaryContainer,
    onPrimaryContainer = G1OnPrimaryContainer,
    inversePrimary = G1InversePrimary,
    secondary = G1Secondary,
    onSecondary = G1OnSecondary,
    secondaryContainer = G1SecondaryContainer,
    onSecondaryContainer = G1OnSecondaryContainer,
    tertiary = G1Tertiary,
    onTertiary = G1OnTertiary,
    tertiaryContainer = G1TertiaryContainer,
    onTertiaryContainer = G1OnTertiaryContainer,
    background = G1Background,
    onBackground = G1OnBackground,
    surface = G1Surface,
    onSurface = G1OnSurface,
    surfaceVariant = G1SurfaceVariant,
    onSurfaceVariant = G1OnSurfaceVariant,
    surfaceBright = G1SurfaceBright,
    surfaceDim = G1SurfaceDim,
    surfaceContainerLowest = G1SurfaceContainerLowest,
    surfaceContainerLow = G1SurfaceContainerLow,
    surfaceContainer = G1SurfaceContainer,
    surfaceContainerHigh = G1SurfaceContainerHigh,
    surfaceContainerHighest = G1SurfaceContainerHighest,
    inverseSurface = G1InverseSurface,
    inverseOnSurface = G1InverseOnSurface,
    outline = G1Outline,
    outlineVariant = G1OutlineVariant,
    scrim = G1Scrim,
    error = G1Error,
    onError = G1OnError,
    errorContainer = G1ErrorContainer,
    onErrorContainer = G1OnErrorContainer,
)

@Composable
fun G1HubTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = G1DarkColorScheme,
        typography = Typography,
        content = content
    )
}
