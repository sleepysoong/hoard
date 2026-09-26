package com.sleepysoong.hoard.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.sleepysoong.hoard.ui.theme.IOSGreen
import com.sleepysoong.hoard.ui.theme.IOSSegmentThumbDark
import com.sleepysoong.hoard.ui.theme.LocalHoardDarkTheme
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Liquid glass controls (iOS 26), modelled on Kyant's catalog LiquidToggle.
 *
 * Shared idea — the "liquid thumb":
 *   at rest   → a solid, softly shadowed capsule (reads like the classic iOS thumb)
 *   pressed   → it swells and turns into clear glass: blur fades out, lens refraction,
 *               ambient rim highlight and inner shadow fade in, and it refracts the
 *               track underneath (the track is recorded into its own layer).
 *   moving    → it stretches along its travel, then springs back round.
 *
 * Layer rule (LIQUID_GLASS.md §4): the track records into `trackLayer`; the thumb is its
 * *sibling* and only reads it — never draw the thumb inside the recorded track.
 */

private val Capsule = RoundedCornerShape(50)

/** 0 = solid thumb at rest, 1 = fully liquid (pressed). Read inside draw/layer blocks only. */
@Composable
private fun Modifier.liquidThumb(
    trackLayer: LayerBackdrop,
    press: () -> Float,
    restColor: Color,
    shape: Shape = Capsule,
    /**
     * How the recorded track is squeezed into the lens (p = press). The switch
     * (Kyant's recipe) hides the track at rest and shows it at 0.75 when pressed;
     * a wide track (segmented) must not be squeezed, or its edges get pulled into
     * the lens as a dark, colour-fringed void.
     */
    squeezeTrack: Boolean = true,
    /**
     * Scale/stretch of the thumb. Must go through drawBackdrop's layerBlock, not an
     * outer graphicsLayer: an outer scale is invisible to the backdrop's position
     * mapping, so the refracted track would be drawn shifted inside the lens.
     */
    layerBlock: GraphicsLayerScope.() -> Unit = {}
): Modifier {
    val global = LocalGlassBackdrop.current
    val mode = LocalGlassMode.current
    if (global == null || mode == GlassMode.Off) {
        // Glassmorphism fallback: solid thumb that turns translucent while pressed.
        return this
            .graphicsLayer(layerBlock)
            .shadow(3.dp, shape, clip = false)
            .clip(shape)
            .drawBehind { drawRect(restColor.copy(alpha = 1f - 0.45f * press())) }
    }
    val refracted = rememberCombinedBackdrop(
        global,
        rememberBackdrop(trackLayer) { drawTrack ->
            // At rest the track is squeezed away (solid thumb); pressed, it fills the lens.
            val p = press()
            if (squeezeTrack) scale(0.667f + 0.083f * p, 0.75f * p) { drawTrack() } else drawTrack()
        }
    )
    return drawBackdrop(
        backdrop = refracted,
        shape = { shape },
        effects = {
            val p = press()
            blur(8.dp.toPx() * (1f - p))
            if (mode == GlassMode.Full) {
                lens(
                    refractionHeight = 5.dp.toPx() * p,
                    refractionAmount = 10.dp.toPx() * p,
                    depthEffect = false,
                    chromaticAberration = true
                )
            }
        },
        highlight = {
            val p = press()
            Highlight.Ambient.copy(
                width = Highlight.Ambient.width / 1.5f,
                blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                alpha = p
            )
        },
        shadow = { Shadow(radius = 4.dp, offset = DpOffset(0.dp, 1.dp), color = Color.Black, alpha = 0.14f) },
        innerShadow = { InnerShadow(radius = 4.dp * press(), offset = DpOffset(0.dp, 1.dp), color = Color.Black, alpha = press()) },
        layerBlock = layerBlock,
        onDrawSurface = { drawRect(restColor.copy(alpha = 1f - press())) }
    )
}

/**
 * iOS 26 liquid switch. Tap toggles; the thumb can also be dragged. While the
 * finger is down the thumb swells into clear glass refracting the (green) track.
 */
@Composable
fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val dark = LocalHoardDarkTheme.current
    val accent = if (dark) Color(0xFF30D158) else IOSGreen
    val trackOff = if (dark) Color(0xFF787880).copy(alpha = 0.36f) else Color(0xFF787878).copy(alpha = 0.2f)
    val trackW = 64.dp
    val trackH = 28.dp
    val thumbW = 40.dp
    val thumbH = 24.dp
    val travel = trackW - thumbW - 4.dp

    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val fraction = remember { Animatable(if (checked) 1f else 0f, visibilityThreshold = 0.001f) }
    val press = remember { Animatable(0f, visibilityThreshold = 0.001f) }
    var dragging by remember { mutableStateOf(false) }
    val currentChecked by rememberUpdatedState(checked)
    val currentOnChange by rememberUpdatedState(onCheckedChange)

    LaunchedEffect(checked) {
        if (!dragging) fraction.animateTo(if (checked) 1f else 0f, spring(dampingRatio = 0.62f, stiffness = 520f, visibilityThreshold = 0.001f))
    }
    val trackLayer = rememberLayerBackdrop()
    val active = enabled && onCheckedChange != null

    Box(
        modifier
            .size(trackW, trackH)
            .alpha(if (enabled) 1f else 0.45f)
            .toggleable(
                value = checked,
                enabled = active,
                role = Role.Switch,
                interactionSource = interaction,
                indication = null,
                onValueChange = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onCheckedChange?.invoke(it)
                }
            )
            .then(
                if (!active) Modifier else Modifier.pointerInput(Unit) {
                    val travelPx = travel.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        scope.launch { press.animateTo(1f, GlassMotion.exit()) }
                        var lastX = down.position.x
                        var moved = 0f
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            val dx = c.position.x - lastX
                            lastX = c.position.x
                            moved += abs(dx)
                            if (moved > viewConfiguration.touchSlop) {
                                dragging = true
                                c.consume() // a drag is not a tap: toggleable won't fire
                                scope.launch { fraction.snapTo((fraction.value + dx / travelPx).coerceIn(0f, 1f)) }
                            }
                        }
                        scope.launch { press.animateTo(0f, GlassMotion.release()) }
                        if (dragging) {
                            val on = fraction.value >= 0.5f
                            dragging = false
                            scope.launch { fraction.animateTo(if (on) 1f else 0f, spring(dampingRatio = 0.62f, stiffness = 520f, visibilityThreshold = 0.001f)) }
                            if (on != currentChecked) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                currentOnChange?.invoke(on)
                            }
                        }
                    }
                }
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        // Track — recorded so the liquid thumb can refract it.
        Box(
            Modifier
                .matchParentSize()
                .layerBackdrop(trackLayer)
                .clip(Capsule)
                .drawBehind { drawRect(lerp(trackOff, accent, fraction.value.coerceIn(0f, 1f))) }
        )
        Box(
            Modifier
                .graphicsLayer { translationX = 2.dp.toPx() + travel.toPx() * fraction.value }
                .testTag("switch-thumb")
                .size(thumbW, thumbH)
                .liquidThumb(trackLayer, press = { press.value }, restColor = Color.White) {
                    // Swell while pressed (1 → 1.5) and stretch with speed, like a drop.
                    val s = 1f + 0.5f * press.value
                    val v = (fraction.velocity / 12f).coerceIn(-0.2f, 0.2f)
                    scaleX = s * (1f + abs(v) * 0.75f)
                    scaleY = s * (1f - abs(v) * 0.25f)
                }
        )
    }
}

/**
 * iOS 26 liquid segmented control: glass track, a liquid thumb that glides between
 * segments with a soft overshoot (stretching while it travels), swells into clear
 * glass while pressed, and can be dragged — release picks the nearest segment.
 */
@Composable
fun IOSSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 36.dp
) {
    if (options.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val dark = LocalHoardDarkTheme.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val n = options.size
    val selected = selectedIndex.coerceIn(0, n - 1)
    val pos = remember { Animatable(selected.toFloat(), visibilityThreshold = 0.001f) }
    val press = remember { Animatable(0f, visibilityThreshold = 0.001f) }
    var dragging by remember { mutableStateOf(false) }
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentSelected by rememberUpdatedState(selected)

    LaunchedEffect(selected) {
        if (!dragging) pos.animateTo(selected.toFloat(), spring(dampingRatio = 0.7f, stiffness = 420f, visibilityThreshold = 0.001f))
    }
    val trackLayer = rememberLayerBackdrop()
    val trackFill = scheme.surfaceContainerHighest
    val thumbColor = if (dark) IOSSegmentThumbDark else Color.White

    BoxWithConstraints(
        modifier
            .heightIn(min = height)
            .height(height)
            .pointerInput(n) {
                awaitEachGesture {
                    val segW = size.width.toFloat() / n
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Press anywhere on the thumb → it liquefies; elsewhere → just a tap.
                    val onThumb = abs(down.position.x / segW - 0.5f - pos.value) < 0.6f
                    if (onThumb) scope.launch { press.animateTo(1f, GlassMotion.exit()) }
                    var x = down.position.x
                    var moved = 0f
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        moved += abs(c.position.x - x)
                        x = c.position.x
                        if (onThumb && moved > viewConfiguration.touchSlop) {
                            dragging = true
                            c.consume()
                            scope.launch { pos.snapTo((x / segW - 0.5f).coerceIn(0f, (n - 1).toFloat())) }
                        }
                    }
                    scope.launch { press.animateTo(0f, GlassMotion.release()) }
                    val target = if (dragging) pos.value.roundToInt() else (x / segW).toInt().coerceIn(0, n - 1)
                    dragging = false
                    scope.launch { pos.animateTo(target.toFloat(), spring(dampingRatio = 0.7f, stiffness = 420f, visibilityThreshold = 0.001f)) }
                    if (target != currentSelected) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        currentOnSelect(target)
                    }
                }
            }
    ) {
        val segW = maxWidth / n
        // Track: glass capsule, fill recorded for the thumb to refract.
        Box(
            Modifier
                .matchParentSize()
                .glassMaterial(Capsule, trackFill, tone = GlassTone.Thin, lifted = false)
        )
        Box(
            Modifier
                .matchParentSize()
                .layerBackdrop(trackLayer)
                .clip(Capsule)
                .drawBehind { drawRect(trackFill.copy(alpha = 0.9f)) }
        )
        // Liquid thumb.
        Box(
            Modifier
                .graphicsLayer { translationX = segW.toPx() * pos.value }
                .testTag("segment-thumb")
                .width(segW)
                .fillMaxHeight()
                .padding(2.dp)
                .liquidThumb(trackLayer, press = { press.value }, restColor = thumbColor, squeezeTrack = false) {
                    val s = 1f + 0.12f * press.value
                    val stretch = 0.18f * kotlin.math.tanh(abs(pos.velocity) * 0.12f)
                    scaleX = s * (1f + stretch)
                    scaleY = s * (1f - stretch * 0.3f)
                }
        )
        // Labels on top, crisp. The label under the thumb turns bold/primary as it arrives.
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                val isSel = i == selected
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .semantics {
                            role = Role.Tab
                            this.selected = isSel
                            onClick { currentOnSelect(i); true }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val closeness = { (1f - abs(pos.value - i)).coerceIn(0f, 1f) }
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium
                        ),
                        color = if (isSel) scheme.onSurface else scheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.graphicsLayer {
                            val k = 1f + 0.06f * press.value * closeness()
                            scaleX = k; scaleY = k
                        }
                    )
                }
            }
        }
    }
}

enum class GlassPillTint { Neutral, Accent, Destructive }

/**
 * Compact glass capsule button for inline actions inside cards (e.g. "테스트",
 * "제거"). Same press physics as every other button; tint only colours the glass
 * and label — it is still glass, never a bare text link.
 */
@Composable
fun GlassPillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: GlassPillTint = GlassPillTint.Neutral,
    enabled: Boolean = true
) {
    val scheme = MaterialTheme.colorScheme
    val (glass, text) = when (tint) {
        GlassPillTint.Neutral -> scheme.surfaceContainerHigh to scheme.onSurface
        GlassPillTint.Accent -> scheme.primary.copy(alpha = 0.55f) to scheme.primary
        GlassPillTint.Destructive -> scheme.error.copy(alpha = 0.55f) to scheme.error
    }
    Box(
        modifier
            // Press scale first so the whole glass pill sinks, not just its label.
            .liquidClickable(enabled = enabled, pressedScale = 0.9f, onClick = onClick)
            .height(36.dp)
            .glassMaterial(Capsule, glass, tone = GlassTone.Thin, enabled = enabled, lifted = false)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (enabled) text else scheme.onSurfaceVariant, maxLines = 1)
    }
}
