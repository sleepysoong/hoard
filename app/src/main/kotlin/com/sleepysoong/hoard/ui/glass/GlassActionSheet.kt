package com.sleepysoong.hoard.ui.glass

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.LocalIndication
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

data class GlassSheetAction(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit
)

/**
 * Inside a popup: play the exit animation, then run the given action.
 * Use for every button/row that closes the popup so it never just vanishes.
 */
val LocalPopupCloser = staticCompositionLocalOf<(() -> Unit) -> Unit> { { it() } }

/**
 * Shared anchor-aware glass overlay.
 *
 * In-window overlay (never a `Popup`) so the Backdrop shader stays valid:
 * a separate window cannot sample the Activity's captured layer. The card is
 * measured first, then anchored above/below the pressed element and clamped
 * to the screen. The dimmed scrim swallows outside taps.
 */
@Composable
fun GlassAnchoredOverlay(
    anchor: Rect?,
    onDismiss: () -> Unit,
    maxCardW: Dp = GlassTokens.popupWidth,
    content: @Composable ColumnScope.() -> Unit
) {
    val density = LocalDensity.current
    val gapPx = with(density) { 8.dp.toPx() }
    val minMarginPx = with(density) { 12.dp.toPx() }

    var cardW by remember { mutableStateOf(0) }
    var cardH by remember { mutableStateOf(0) }
    var placedX by remember { mutableStateOf(0) }
    var placedY by remember { mutableStateOf(0) }
    var origin by remember { mutableStateOf(TransformOrigin(0.5f, 0.5f)) }

    // 0 = gone, 1 = at rest. Enters with a bouncy overshoot from the anchor, leaves
    // fast without bounce; the caller's state only changes after the exit finishes.
    val appear = remember { Animatable(0f, visibilityThreshold = GlassMotion.SCALE_THRESHOLD) }
    val fade = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        launch { fade.animateTo(1f, GlassMotion.fade()) }
        appear.animateTo(1f, GlassMotion.bouncy())
    }
    val currentDismiss by rememberUpdatedState(onDismiss)
    val closer: (() -> Unit) -> Unit = remember {
        { after ->
            if (!closing) {
                closing = true
                scope.launch {
                    launch { fade.animateTo(0f, GlassMotion.leave()) }
                    appear.animateTo(0f, GlassMotion.leave())
                    after()
                }
            }
        }
    }

    // In-window overlay has no Dialog window to consume Back: close it here.
    BackHandler { closer { currentDismiss() } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val totalW = constraints.maxWidth.toFloat()
        val totalH = constraints.maxHeight.toFloat()

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = fade.value }
                // The overlay lives inside the padded NavHost; paint the scrim past
                // our bounds so it reaches the screen edges instead of leaving a frame.
                .drawBehind {
                    val bleed = 4_000f
                    drawRect(
                        Color.Black.copy(alpha = 0.22f),
                        topLeft = Offset(-bleed, -bleed),
                        size = Size(size.width + 2 * bleed, size.height + 2 * bleed)
                    )
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { closer { currentDismiss() } }
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .widthIn(max = maxCardW)
                .padding(horizontal = 10.dp)
                .onGloballyPositioned {
                    cardW = it.size.width
                    cardH = it.size.height
                    if (anchor != null) {
                        val spaceAbove = anchor.top
                        val spaceBelow = totalH - anchor.bottom
                        val showAbove = spaceAbove >= cardH + gapPx ||
                            (spaceAbove >= spaceBelow && spaceBelow < cardH + gapPx)
                        val y = if (showAbove) {
                            (anchor.top - gapPx - cardH).coerceAtLeast(minMarginPx)
                        } else {
                            (anchor.bottom + gapPx).coerceAtLeast(minMarginPx)
                        }.coerceAtMost(totalH - cardH - minMarginPx)
                        val anchorCenterX = anchor.left + anchor.width / 2f
                        val x = (anchorCenterX - cardW / 2f)
                            .coerceIn(minMarginPx, totalW - cardW - minMarginPx)
                        origin = TransformOrigin(
                            pivotFractionX = if (cardW > 0) {
                                ((anchorCenterX - x) / cardW).coerceIn(0f, 1f)
                            } else 0.5f,
                            pivotFractionY = if (showAbove) 1f else 0f
                        )
                        placedX = x.roundToInt()
                        placedY = y.roundToInt()
                    } else {
                        origin = TransformOrigin(0.5f, 0.5f)
                        placedX = ((totalW - cardW) / 2f).roundToInt()
                        placedY = ((totalH - cardH) / 2f).roundToInt()
                    }
                }
                .offset { IntOffset(placedX, placedY) }
                .graphicsLayer {
                    // Grows out of the pressed element; the spring's overshoot
                    // makes it pop slightly past full size before settling.
                    val p = appear.value
                    scaleX = 0.82f + 0.18f * p
                    scaleY = 0.82f + 0.18f * p
                    alpha = fade.value
                    transformOrigin = origin
                }
                // After the layer so tests measure the animated (scaled) bounds.
                .testTag("glass-popup"),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CompositionLocalProvider(LocalPopupCloser provides closer) { content() }
        }
    }
}

/**
 * The one popup layout. Every popup in the app — long-press menus, model picker,
 * session settings, confirm/edit dialogs — is this stack of glass cards:
 *
 *   header card (title + message) · body card (rows or form) · button row
 *
 * Callers only fill the slots; shape, tone, spacing and button style live here.
 * [anchor] = pressed element (null → centered). The body scrolls when it would
 * not fit (e.g. with the keyboard up).
 */
@Composable
fun GlassPopup(
    onDismiss: () -> Unit,
    title: String?,
    message: String? = null,
    anchor: Rect? = null,
    maxWidth: Dp = GlassTokens.popupWidth,
    dismissLabel: String = "취소",
    confirmLabel: String? = null,
    onConfirm: (() -> Unit)? = null,
    confirmDestructive: Boolean = false,
    confirmEnabled: Boolean = true,
    bodyPadding: PaddingValues = PaddingValues(0.dp),
    body: (@Composable ColumnScope.() -> Unit)? = null
) {
    GlassAnchoredOverlay(anchor = anchor, onDismiss = onDismiss, maxCardW = maxWidth) {
        if (title != null || message != null) GlassPopupHeader(title, message)
        if (body != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .popupCard()
                    .verticalScroll(rememberScrollState())
                    .padding(bodyPadding),
                content = body
            )
        }
        GlassPopupButtons(
            dismissLabel = dismissLabel,
            onDismiss = onDismiss,
            confirmLabel = confirmLabel,
            onConfirm = onConfirm,
            confirmDestructive = confirmDestructive,
            confirmEnabled = confirmEnabled
        )
    }
}

@Composable
private fun Modifier.popupCard(): Modifier {
    val shape = RoundedCornerShape(GlassTokens.cardRadius)
    return this.clip(shape).glassMaterial(shape, MaterialTheme.colorScheme.surface)
}

@Composable
private fun GlassPopupHeader(title: String?, message: String?) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .popupCard()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        title?.let {
            Text(
                it,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() }
            )
        }
        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = if (title != null) 4.dp else 0.dp)
            )
        }
    }
}

/** Cancel alone spans the width; cancel + confirm split it evenly. */
@Composable
private fun GlassPopupButtons(
    dismissLabel: String,
    onDismiss: () -> Unit,
    confirmLabel: String?,
    onConfirm: (() -> Unit)?,
    confirmDestructive: Boolean,
    confirmEnabled: Boolean
) {
    val haptics = LocalHapticFeedback.current
    val close = LocalPopupCloser.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        GlassCapsuleButton(
            onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); close { onDismiss() } },
            label = dismissLabel,
            modifier = Modifier.weight(1f)
        )
        if (confirmLabel != null && onConfirm != null) {
            GlassCapsuleButton(
                onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); close { onConfirm() } },
                label = confirmLabel,
                primary = !confirmDestructive,
                destructive = confirmDestructive,
                enabled = confirmEnabled,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** Hairline between rows of a popup body; [inset] aligns it with the row text. */
@Composable
fun GlassPopupDivider(inset: Dp = 16.dp) {
    HorizontalDivider(
        modifier = Modifier.padding(start = inset),
        thickness = GlassTokens.hairline,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    )
}

/** Tappable popup row: optional icon, label (+subtitle), optional check mark. */
@Composable
fun GlassPopupRow(
    label: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    subtitle: String? = null,
    destructive: Boolean = false,
    selected: Boolean = false,
    /** Play the popup's exit animation before [onClick] runs. */
    closesPopup: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val tint = if (destructive) scheme.error else scheme.onSurface
    val close = LocalPopupCloser.current
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                if (closesPopup) close(onClick) else onClick()
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = tint, maxLines = 1)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (selected) {
            Icon(Icons.Rounded.Check, contentDescription = "선택됨", tint = scheme.primary, modifier = Modifier.size(20.dp))
        }
    }
}

/** Long-press action menu: [GlassPopup] with one row per action. */
@Composable
fun GlassAnchoredMenu(
    anchor: Rect?,
    title: String?,
    message: String?,
    actions: List<GlassSheetAction>,
    onDismiss: () -> Unit
) {
    GlassPopup(onDismiss = onDismiss, title = title, message = message, anchor = anchor) {
        actions.forEachIndexed { i, action ->
            if (i > 0) GlassPopupDivider(inset = 51.dp)
            GlassPopupRow(
                label = action.label,
                icon = action.icon,
                destructive = action.destructive,
                closesPopup = true,
                onClick = { onDismiss(); action.onClick() }
            )
        }
    }
}
