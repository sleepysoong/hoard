package com.sleepysoong.hoard.ui.glass

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight

enum class GlassMode { Full, BlurOnly, Off }

/** Always choose the strongest effect supported by the running Android version. */
fun resolveGlassMode(sdk: Int): GlassMode = when {
    sdk < 31 -> GlassMode.Off
    sdk < 33 -> GlassMode.BlurOnly
    else -> GlassMode.Full
}

val LocalGlassMode = staticCompositionLocalOf { GlassMode.Off }
internal val LocalGlassBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

@Composable
fun GlassTheme(content: @Composable () -> Unit) {
    val mode = if (LocalInspectionMode.current) GlassMode.Off else resolveGlassMode(Build.VERSION.SDK_INT)
    CompositionLocalProvider(LocalGlassMode provides mode, content = content)
}

/**
 * Records a separate background sibling so glass descendants never sample themselves.
 * Background is a solid theme color (never a gradient — product rule).
 */
@Composable
fun GlassHost(
    modifier: Modifier = Modifier,
    fillWindow: Boolean = true,
    backgroundShape: Shape? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val mode = LocalGlassMode.current
    val colors = MaterialTheme.colorScheme
    val backdrop = if (mode != GlassMode.Off) {
        rememberLayerBackdrop {
            drawRect(Color.White)
            drawContent()
        }
    } else {
        null
    }
    Box(if (fillWindow) modifier.fillMaxSize() else modifier) {
        Box(
            Modifier.matchParentSize()
                .then(backgroundShape?.let { Modifier.clip(it) } ?: Modifier)
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .background(colors.background)
        )
        CompositionLocalProvider(
            LocalGlassBackdrop provides backdrop,
            LocalContentColor provides colors.onBackground
        ) { content() }
    }
}

/** Background only: content is drawn after this modifier, so text never enters the shader. */
@Composable
internal fun Modifier.glassMaterial(
    shape: Shape = RoundedCornerShape(28.dp),
    tint: Color = MaterialTheme.colorScheme.surface,
    compact: Boolean = false,
    enabled: Boolean = true,
    outlineColor: Color? = null
): Modifier {
    val backdrop = LocalGlassBackdrop.current
    val mode = LocalGlassMode.current
    val scheme = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    val surface = if (enabled) tint else scheme.surfaceContainerHighest
    val opacity = if (compact) .38f else .27f
    val outline = outlineColor ?: scheme.outline.copy(alpha = if (enabled) .58f else .32f)
    val material = if (backdrop == null || mode == GlassMode.Off) {
        // Glassmorphism fallback: translucent solid tint + outline, no blur shader.
        val fallback = if (dark) surface.copy(alpha = 0.72f) else Color.White.copy(alpha = 0.82f)
        Modifier.background(if (enabled) fallback else surface.copy(alpha = 1f), shape)
    } else {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                colorControls(saturation = 1.04f)
                blur((if (compact || mode == GlassMode.BlurOnly) 6.dp else 10.dp).toPx())
                if (mode == GlassMode.Full) {
                    lens(minOf(12.dp.toPx(), size.minDimension / 4f), minOf(18.dp.toPx(), size.minDimension / 2f))
                }
            },
            highlight = { Highlight.Default.copy(alpha = if (enabled) .75f else .35f) },
            shadow = null,
            onDrawSurface = { drawRect(surface.copy(alpha = opacity)) }
        )
    }
    return then(material).border(1.dp, outline, shape).clip(shape)
}
