package com.sleepysoong.hoard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sleepysoong.hoard.ui.glass.GlassHost
import com.sleepysoong.hoard.ui.glass.GlassTheme

private val HoardLightScheme = lightColorScheme(
    primary = HoardCocoa,
    onPrimary = Color.White,
    primaryContainer = HoardMatchaPale,
    onPrimaryContainer = HoardCocoaDeep,
    secondary = HoardMatcha,
    onSecondary = Color(0xFF2B3A10),
    secondaryContainer = HoardMatchaPale,
    onSecondaryContainer = HoardCocoaDeep,
    tertiary = HoardMatchaDeep,
    onTertiary = Color.White,
    background = IOSGroupedBgLight,
    onBackground = Color.Black,
    surface = IOSCardLight,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFF5F5F7),
    onSurfaceVariant = IOSSecondaryLabelLight,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFAFC),
    surfaceContainer = Color(0xFFF5F5F7),
    surfaceContainerHigh = Color(0xFFEFEFF2),
    surfaceContainerHighest = IOSFieldGrayLight,
    outline = IOSSeparatorLight,
    outlineVariant = IOSFieldGrayLight,
    error = IOSRedLight,
    onError = Color.White,
    errorContainer = Color(0xFFFFE9E8),
    onErrorContainer = Color(0xFFB3261E)
)

// Dark canvas is not pure black; near-black keeps glass "ice" readable.
val IOSDarkCanvas = Color(0xFF0C0C0F)
val IOSDarkCard = Color(0xFF1A1A1F)

private val HoardDarkScheme = darkColorScheme(
    primary = HoardLatte,
    onPrimary = Color(0xFF2A1609),
    primaryContainer = HoardMatchaNight,
    onPrimaryContainer = Color(0xFFD8EDB0),
    secondary = HoardMatchaLight,
    onSecondary = Color(0xFF1E2A08),
    secondaryContainer = HoardMatchaNight,
    onSecondaryContainer = Color(0xFFD8EDB0),
    tertiary = HoardMatchaLight,
    onTertiary = Color(0xFF1E2A08),
    background = IOSDarkCanvas,
    onBackground = Color.White,
    surface = IOSDarkCard,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF232328),
    onSurfaceVariant = IOSSecondaryLabelDark,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF111114),
    surfaceContainer = Color(0xFF19191C),
    surfaceContainerHigh = Color(0xFF232328),
    surfaceContainerHighest = IOSFieldGrayDark,
    outline = Color(0xFF45454B),
    outlineVariant = Color(0xFF2C2C31),
    error = IOSRedDark,
    onError = Color.White,
    errorContainer = Color(0xFF5A1712),
    onErrorContainer = Color(0xFFFFDAD6)
)

val HoardShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp)
)

/**
 * Whether the *app* is dark (설정 → 화면 스타일), which can differ from the phone.
 * Read this — never isSystemInDarkTheme() — anywhere colours depend on the theme.
 */
val LocalHoardDarkTheme = staticCompositionLocalOf { false }

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
            CompositionLocalProvider(LocalHoardDarkTheme provides darkTheme) {
                GlassTheme {
                    GlassHost { content() }
                }
            }
        }
    )
}
