package com.sleepysoong.hoard.ui.glass

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import com.sleepysoong.hoard.ui.theme.LocalHoardDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
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
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow

enum class GlassMode { Full, BlurOnly, Off }

/** Material weight. Drives blur radius, lens strength, tint density and depth. */
enum class GlassTone { Thin, Regular, Thick }

/** Single source of truth for the liquid-glass material metrics. */
object GlassTokens {
    val cardRadius: Dp = 22.dp
    val sheetRadius: Dp = 28.dp
    val fieldRadius: Dp = 20.dp
    val controlRadius: Dp = 16.dp
    val bubbleRadius: Dp = 19.dp
    val tailRadius: Dp = 5.dp
    val touchMin: Dp = 44.dp
    val barHeight: Dp = 64.dp
    val hairline: Dp = 0.5.dp
    /** iOS modal buttons: full-width, 54dp, separated from content. */
    val modalActionHeight: Dp = 54.dp
    /** Max width of every popup (menus, pickers, dialogs). */
    val popupWidth: Dp = 360.dp
    /**
     * How far a lifted card's drop shadow reaches below it (radius 18 + offset 6).
     * Scrolling containers clip at their edges, so pad them by this much or the
     * shadow is cut into a hard line ("painted white" band under the last card).
     */
    val shadowBleed: Dp = 24.dp
}

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

private data class ToneSpec(
    val blur: Dp,
    val lensHeight: Dp,
    val lensAmount: Dp,
    val tintAlpha: Float,
    val innerShadow: Float
)

private fun specFor(tone: GlassTone, mode: GlassMode, dark: Boolean): ToneSpec {
    // Dark mode needs higher tint / inner shadow so the glass still reads as
    // physical material on a near-black canvas. Slightly densify there.
    val base = when (tone) {
        GlassTone.Thin -> ToneSpec(5.dp, 8.dp, 14.dp, 0.26f, 0.05f)
        GlassTone.Regular -> ToneSpec(10.dp, 16.dp, 26.dp, 0.34f, 0.08f)
        GlassTone.Thick -> ToneSpec(16.dp, 30.dp, 52.dp, 0.52f, 0.10f)
    }
    return if (!dark) base else base.copy(
        tintAlpha = (base.tintAlpha * 1.22f).coerceAtMost(0.60f),
        innerShadow = base.innerShadow * 1.4f
    )
}

/**
 * The liquid-glass material.
 *
 * Layer order follows real glass optics: blur + vibrancy for the body, lens for
 * edge refraction, a specular highlight on the rim, an inner shadow for edge
 * thickness, and a soft drop shadow for lift. Text is drawn afterwards, crisp.
 */
@Composable
internal fun Modifier.glassMaterial(
    shape: Shape = RoundedCornerShape(GlassTokens.cardRadius),
    tint: Color = MaterialTheme.colorScheme.surface,
    tone: GlassTone = GlassTone.Regular,
    enabled: Boolean = true,
    outlineColor: Color? = null,
    lifted: Boolean = true
): Modifier {
    val backdrop = LocalGlassBackdrop.current
    val mode = LocalGlassMode.current
    val scheme = MaterialTheme.colorScheme
    val dark = LocalHoardDarkTheme.current
    val spec = specFor(tone, mode, dark)
    val surface = if (enabled) tint else scheme.surfaceContainerHighest
    val alpha = if (enabled) spec.tintAlpha else 0f
    // On a pure-white canvas the glass has nothing to tint, so the rim and the
    // inner/outer shadows do the work. Keep them crisp enough to read as glass.
    val outline = outlineColor ?: scheme.onSurface.copy(
        alpha = if (enabled) (if (dark) 0.18f else 0.13f) else 0.08f
    )

    val material = if (backdrop == null || mode == GlassMode.Off) {
        // Glassmorphism fallback: translucent solid tint + hairline, no shader.
        val fallbackAlpha = if (enabled) (if (dark) 0.72f else 0.86f) else 1f
        Modifier.background(surface.copy(alpha = fallbackAlpha), shape)
    } else {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                colorControls(saturation = 1.06f, brightness = if (dark) 0f else 0.015f)
                blur(spec.blur.toPx())
                if (mode == GlassMode.Full) {
                    // depthEffect off — keeps the tint from becoming a white
                    // band at the card edge (the "painted white bottom" artifact).
                    lens(
                        refractionHeight = minOf(spec.lensHeight.toPx(), size.minDimension / 2.6f),
                        refractionAmount = minOf(spec.lensAmount.toPx(), size.minDimension * 0.45f),
                        depthEffect = false,
                        chromaticAberration = false
                    )
                }
            },
            highlight = null,  // specular washes the tint into a white sheen on light; rim/outline carries the surface instead
            shadow = if (lifted) {
                {
                    Shadow(
                        radius = 18.dp,
                        offset = DpOffset(0.dp, 6.dp),
                        color = if (dark) Color.Black else Color(0xFF1B2A4A),
                        alpha = if (dark) 0.34f else 0.13f
                    )
                }
            } else null,
            innerShadow = if (enabled && tone != GlassTone.Thin) {
                {
                    InnerShadow(
                        radius = 10.dp,
                        offset = DpOffset(0.dp, 1.5.dp),
                        color = Color.Black,
                        alpha = spec.innerShadow
                    )
                }
            } else null,
            onDrawSurface = { drawRect(surface.copy(alpha = if (dark) alpha * 0.86f else alpha)) }
        )
    }
    return then(material)
        .border(GlassTokens.hairline, outline, shape)
        .clip(shape)
}

/**
 * Liquid tap target: subtle squash while pressed, no Material ripple, optional
 * haptic — the full iOS interaction feel for rows and cards.
 */
@Composable
fun Modifier.liquidClickable(
    enabled: Boolean = true,
    pressedScale: Float = 0.98f,
    haptic: Boolean = true,
    onClick: () -> Unit
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 800f),
        label = "liquid-press"
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClick = {
                if (haptic) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
        )
}
