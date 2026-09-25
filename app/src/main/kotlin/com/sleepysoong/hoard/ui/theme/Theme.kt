package com.sleepysoong.hoard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.sleepysoong.hoard.ui.glass.GlassHost
import com.sleepysoong.hoard.ui.glass.GlassTheme

private val HoardLightScheme = lightColorScheme(
    primary = HoardBlue,
    onPrimary = HoardWhite,
    primaryContainer = HoardBlueSoft,
    onPrimaryContainer = Color(0xFF103A73),
    secondary = Color(0xFF285A96),
    onSecondary = HoardWhite,
    secondaryContainer = HoardBlueSoft,
    onSecondaryContainer = Color(0xFF193B68),
    tertiary = Color(0xFF1468D4),
    onTertiary = HoardWhite,
    tertiaryContainer = HoardBlueTint,
    onTertiaryContainer = Color(0xFF103A73),
    background = HoardCanvas,
    onBackground = HoardInk,
    surface = HoardCanvas,
    onSurface = HoardInk,
    surfaceVariant = HoardSurface,
    onSurfaceVariant = HoardMutedInk,
    surfaceContainerLowest = HoardWhite,
    surfaceContainerLow = HoardSurfaceSubtle,
    surfaceContainer = HoardSurface,
    surfaceContainerHigh = HoardSurfaceRaised,
    surfaceContainerHighest = HoardSurfaceSelected,
    outline = HoardOutline,
    outlineVariant = HoardOutlineSoft,
    error = HoardError,
    onError = HoardWhite,
    errorContainer = HoardErrorSoft,
    onErrorContainer = Color(0xFF7A271A)
)

private val HoardDarkScheme = darkColorScheme(
    primary = Color(0xFF8DB4FF),
    onPrimary = Color(0xFF0A1F3D),
    primaryContainer = HoardDarkSelected,
    onPrimaryContainer = Color(0xFFD9E7FF),
    secondary = Color(0xFF9DB9DD),
    onSecondary = Color(0xFF0A1F3D),
    secondaryContainer = HoardDarkRaised,
    onSecondaryContainer = Color(0xFFD9E7FF),
    tertiary = Color(0xFF8DB4FF),
    onTertiary = Color(0xFF0A1F3D),
    tertiaryContainer = HoardDarkSelected,
    onTertiaryContainer = Color(0xFFD9E7FF),
    background = HoardDarkCanvas,
    onBackground = HoardDarkInk,
    surface = HoardDarkCanvas,
    onSurface = HoardDarkInk,
    surfaceVariant = HoardDarkSurface,
    onSurfaceVariant = HoardDarkMuted,
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = HoardDarkCanvas,
    surfaceContainer = HoardDarkSurface,
    surfaceContainerHigh = HoardDarkRaised,
    surfaceContainerHighest = HoardDarkSelected,
    outline = HoardDarkOutline,
    outlineVariant = HoardDarkOutlineSoft,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

val HoardShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun HoardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) HoardDarkScheme else HoardLightScheme,
        shapes = HoardShapes,
        typography = HoardTypography,
        content = {
            GlassTheme {
                GlassHost { content() }
            }
        }
    )
}
