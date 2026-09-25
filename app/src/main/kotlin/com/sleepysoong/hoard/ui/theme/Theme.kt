package com.sleepysoong.hoard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
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
    primary = IOSBlueLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E9FF),
    onPrimaryContainer = Color(0xFF003A8C),
    secondary = IOSGreen,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDFF7E4),
    onSecondaryContainer = Color(0xFF0B4D1C),
    tertiary = IOSGreen,
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

private val HoardDarkScheme = darkColorScheme(
    primary = IOSBlueDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1C3A5E),
    onPrimaryContainer = Color(0xFFD6E9FF),
    secondary = IOSGreen,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF12341C),
    onSecondaryContainer = Color(0xFFDFF7E4),
    tertiary = IOSGreen,
    onTertiary = Color.White,
    background = IOSGroupedBgDark,
    onBackground = Color.White,
    surface = IOSCardDark,
    onSurface = Color.White,
    surfaceVariant = IOSCardDark,
    onSurfaceVariant = IOSSecondaryLabelDark,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color.Black,
    surfaceContainer = IOSCardDark,
    surfaceContainerHigh = Color(0xFF2C2C2E),
    surfaceContainerHighest = IOSFieldGrayDark,
    outline = IOSSeparatorDark,
    outlineVariant = Color(0xFF2C2C2E),
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
