package io.texne.g1.hub.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = SkyTeal,
    onPrimary = ShadowInk,
    primaryContainer = OceanBlue,
    onPrimaryContainer = MoonlightPearl,
    secondary = SunlitGold,
    onSecondary = ShadowInk,
    secondaryContainer = BurnishedGold,
    onSecondaryContainer = MoonlightPearl,
    tertiary = SunlitGold,
    onTertiary = ShadowInk,
    tertiaryContainer = OceanBlue,
    onTertiaryContainer = MoonlightPearl,
    background = MidnightBlue,
    onBackground = MoonlightPearl,
    surface = OceanBlue,
    onSurface = MoonlightPearl,
    surfaceVariant = MidnightBlue,
    onSurfaceVariant = MoonlightPearl,
    outline = SunlitGold,
    inversePrimary = MistIvory
)

private val LightColorScheme = lightColorScheme(
    primary = OceanBlue,
    onPrimary = MoonlightPearl,
    primaryContainer = SkyTeal,
    onPrimaryContainer = ShadowInk,
    secondary = SunlitGold,
    onSecondary = ShadowInk,
    secondaryContainer = BurnishedGold,
    onSecondaryContainer = MoonlightPearl,
    tertiary = SkyTeal,
    onTertiary = ShadowInk,
    tertiaryContainer = SurfaceMist,
    onTertiaryContainer = OceanBlue,
    background = MistIvory,
    onBackground = ShadowInk,
    surface = SurfaceMist,
    onSurface = ShadowInk,
    surfaceVariant = MistIvory,
    onSurfaceVariant = OceanBlue,
    outline = BurnishedGold,
    inversePrimary = SkyTeal
)

@Composable
fun EyeOfKilroggTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
