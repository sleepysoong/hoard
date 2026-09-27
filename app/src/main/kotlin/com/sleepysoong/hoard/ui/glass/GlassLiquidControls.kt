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
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.sleepysoong.hoard.ui.theme.IOSGreen
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
     * Glass at rest too: a frosted lens that shows the track through it, with a
     * specular rim. Off = Kyant's original (solid white at rest, glass only when
     * pressed), which reads as the old opaque iOS knob.
     */
    glassAtRest: Boolean = false,
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
            .drawBehind { drawRect(restColor.copy(alpha = (if (glassAtRest) 0.75f else 1f) - 0.45f * press())) }
    }
    val refracted = rememberCombinedBackdrop(
        global,
        rememberBackdrop(trackLayer) { drawTrack ->
            // At rest the track is squeezed away (solid thumb); pressed, it fills the lens.
            val p = press()
            when {
                // Glass at rest: the lens looks straight through at the track, squeezing in
                // only as it's pressed (squeezing at rest pulls the void past the track's
                // edge into the thumb as a grey crescent).
                glassAtRest -> {
                    val k = 1f - 0.25f * p
                    scale(k, k) { drawTrack() }
                }
                squeezeTrack -> scale(0.667f + 0.083f * p, 0.75f * p) { drawTrack() }
                else -> drawTrack()
            }
        }
    )
    return drawBackdrop(
        backdrop = refracted,
        shape = { shape },
        effects = {
            val p = press()
            // At rest (glassAtRest): lightly frosted lens; pressed: clear, stronger lens.
            val rest = if (glassAtRest) 1f else 0f
            blur(if (glassAtRest) 2.dp.toPx() * (1f - p) else 8.dp.toPx() * (1f - p))
            if (mode == GlassMode.Full) {
                lens(
                    refractionHeight = (4.dp.toPx() * rest * (1f - p)) + 5.dp.toPx() * p,
                    refractionAmount = (2.dp.toPx() * rest * (1f - p)) + 10.dp.toPx() * p,
                    depthEffect = false,
                    chromaticAberration = true
                )
            }
        },
        highlight = {
            val p = press()
            // Glass at rest carries a full-strength specular rim — that's what reads as glass
            // on a flat track; Kyant's pressed-only rim is thinner.
            if (glassAtRest) Highlight.Default.copy(alpha = 0.9f + 0.1f * p)
            else Highlight.Ambient.copy(
                width = Highlight.Ambient.width / 1.5f,
                blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                alpha = p
            )
        },
        shadow = { Shadow(radius = 4.dp, offset = DpOffset(0.dp, 1.dp), color = Color.Black, alpha = 0.14f) },
        innerShadow = {
            val k = if (glassAtRest) 0.35f + 0.65f * press() else press()
            InnerShadow(radius = 4.dp * k, offset = DpOffset(0.dp, 1.dp), color = Color.Black, alpha = k)
        },
        layerBlock = layerBlock,
        // glassAtRest: milky-white frost (still see-through), thinning as it's pressed.
        onDrawSurface = { drawRect(restColor.copy(alpha = if (glassAtRest) 0.42f * (1f - press()) + 0.08f else 1f - press())) }
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
        // Glass under the track, so the (translucent) off state is glass, not flat grey.
        Box(Modifier.matchParentSize().glassMaterial(Capsule, MaterialTheme.colorScheme.surface, GlassTone.Thin, lifted = false))
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
                .liquidThumb(trackLayer, press = { press.value }, restColor = Color.White, glassAtRest = true) {
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
 * iOS 26 liquid segmented control, built like Kyant's LiquidBottomTabs (and our tab bar):
 *  - track: a lifted glass capsule (same material as the floating tab bar)
 *  - pill: tinted glass (primaryContainer) filling the track with a 3dp inset; while
 *    pressed it swells and becomes a clear lens with a specular rim
 *  - labels: an accent copy is recorded into a hidden layer; the pill refracts *that*,
 *    so the label under the pill is accent-coloured and bends through the lens
 *  - glides with a soft overshoot and stretches while travelling; the pill can be
 *    dragged, release snaps to the nearest segment
 */
@Composable
fun IOSSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp
) {
    if (options.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val global = LocalGlassBackdrop.current
    val mode = LocalGlassMode.current
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
    val labelsLayer = rememberLayerBackdrop()
    val accent = scheme.onPrimaryContainer
    val pillTint = scheme.primaryContainer
    val inset = 3.dp
    val labelStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)

    @Composable
    fun labels(color: (Int) -> Color, m: Modifier, accessible: Boolean) {
        Row(m.fillMaxSize().padding(horizontal = inset)) {
            options.forEachIndexed { i, label ->
                val a11y = if (!accessible) Modifier else Modifier.semantics {
                    role = Role.Tab
                    this.selected = i == selected
                    onClick { currentOnSelect(i); true }
                }
                Box(Modifier.weight(1f).fillMaxHeight().then(a11y), contentAlignment = Alignment.Center) {
                    Text(label, style = labelStyle, color = color(i), maxLines = 1)
                }
            }
        }
    }

    BoxWithConstraints(
        modifier
            .height(height)
            .pointerInput(n) {
                awaitEachGesture {
                    val segW = (size.width - 2 * inset.toPx()) / n
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val at = { x: Float -> (x - inset.toPx()) / segW }
                    val onPill = abs(at(down.position.x) - 0.5f - pos.value) < 0.6f
                    if (onPill) scope.launch { press.animateTo(1f, GlassMotion.exit()) }
                    var x = down.position.x
                    var moved = 0f
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        moved += abs(c.position.x - x)
                        x = c.position.x
                        if (onPill && moved > viewConfiguration.touchSlop) {
                            dragging = true
                            c.consume()
                            scope.launch { pos.snapTo((at(x) - 0.5f).coerceIn(0f, (n - 1).toFloat())) }
                        }
                    }
                    scope.launch { press.animateTo(0f, GlassMotion.release()) }
                    val target = if (dragging) pos.value.roundToInt() else at(x).toInt().coerceIn(0, n - 1)
                    dragging = false
                    scope.launch { pos.animateTo(target.toFloat(), spring(dampingRatio = 0.7f, stiffness = 420f, visibilityThreshold = 0.001f)) }
                    if (target != currentSelected) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        currentOnSelect(target)
                    }
                }
            }
    ) {
        val segW = (maxWidth - inset * 2) / n
        // Track: the tab bar's glass capsule.
        Box(Modifier.matchParentSize().glassMaterial(Capsule, scheme.surface, GlassTone.Thick))
        // Accent labels, recorded for the pill to refract, never shown directly.
        // The pill tint lives in this layer, *under* the accent text: tint drawn as the
        // pill's surface would sit on top of the refracted label and wash it out.
        labels(
            { accent },
            Modifier.clearAndSetSemantics {}.alpha(0f).layerBackdrop(labelsLayer).drawBehind { drawRect(pillTint) },
            accessible = false
        )
        // Visible labels: colour follows the pill as it arrives (also the fallback look).
        labels(
            { i -> lerp(scheme.onSurfaceVariant, accent, (1f - abs(pos.value - i)).coerceIn(0f, 1f)) },
            Modifier,
            accessible = true
        )
        // Liquid pill.
        val pillModifier = Modifier
            .graphicsLayer { translationX = (inset + segW * pos.value).toPx() }
            .testTag("segment-thumb")
            .width(segW)
            .fillMaxHeight()
            .padding(vertical = inset)
        val layer: GraphicsLayerScope.() -> Unit = {
            val sc = 1f + 0.1f * press.value
            val stretch = 0.18f * kotlin.math.tanh(abs(pos.velocity) * 0.12f)
            scaleX = sc * (1f + stretch)
            scaleY = sc * (1f - stretch * 0.3f)
        }
        if (global == null || mode == GlassMode.Off) {
            Box(pillModifier.graphicsLayer(layer).clip(Capsule).drawBehind { drawRect(pillTint) })
        } else {
            Box(
                pillModifier.drawBackdrop(
                    backdrop = rememberCombinedBackdrop(global, labelsLayer),
                    shape = { Capsule },
                    effects = {
                        vibrancy()
                        if (mode == GlassMode.Full) {
                            val p = press.value
                            lens(10.dp.toPx() * p, 14.dp.toPx() * p, depthEffect = false, chromaticAberration = true)
                        }
                    },
                    highlight = { Highlight.Default.copy(alpha = 0.35f + 0.65f * press.value) },
                    shadow = { Shadow(radius = 6.dp, offset = DpOffset(0.dp, 2.dp), color = Color.Black, alpha = 0.06f + 0.08f * press.value) },
                    innerShadow = { InnerShadow(radius = 6.dp * press.value, color = Color.Black, alpha = 0.5f * press.value) },
                    layerBlock = layer,
                    // A faint frost while pressed so the lens reads as glass.
                    onDrawSurface = { drawRect(Color.White.copy(alpha = 0.12f * press.value)) }
                )
            )
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
