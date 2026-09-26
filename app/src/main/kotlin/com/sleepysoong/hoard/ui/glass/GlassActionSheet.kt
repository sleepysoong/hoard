package com.sleepysoong.hoard.ui.glass

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
    maxCardW: Dp = 340.dp,
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

    val appear by animateFloatAsState(
        targetValue = 1f,
        animationSpec = GlassMotion.springSnappy,
        label = "anchor-overlay-appear"
    )

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val totalW = constraints.maxWidth.toFloat()
        val totalH = constraints.maxHeight.toFloat()

        Box(
            Modifier
                .fillMaxSize()
                .alpha(appear)
                .background(Color.Black.copy(alpha = 0.22f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
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
                .alpha(appear)
                .graphicsLayer {
                    scaleX = 0.92f + 0.08f * appear
                    scaleY = 0.92f + 0.08f * appear
                    transformOrigin = origin
                },
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

/** Standard iOS-style action list menu, anchored near its source element. */
@Composable
fun GlassAnchoredMenu(
    anchor: Rect?,
    title: String?,
    message: String?,
    actions: List<GlassSheetAction>,
    onDismiss: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val cornerRad = 20.dp

    GlassAnchoredOverlay(anchor = anchor, onDismiss = onDismiss) {
        if (title != null || message != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(cornerRad))
                    .glassMaterial(RoundedCornerShape(cornerRad), scheme.surface)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    title?.let {
                        Text(it, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
                    }
                    message?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 2,
                            modifier = Modifier.padding(top = if (title != null) 4.dp else 0.dp)
                        )
                    }
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(cornerRad))
                .glassMaterial(RoundedCornerShape(cornerRad), scheme.surface)
        ) {
            actions.forEachIndexed { i, action ->
                if (i > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 58.dp),
                        thickness = 0.5.dp,
                        color = scheme.onSurface.copy(alpha = 0.12f)
                    )
                }
                val tint = if (action.destructive) scheme.error else scheme.onSurface
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 54.dp)
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onDismiss()
                            action.onClick()
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Icon(
                        action.icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(21.dp)
                    )
                    Text(
                        action.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = tint,
                        maxLines = 1
                    )
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(54.dp)
                .clip(RoundedCornerShape(cornerRad))
                .glassMaterial(RoundedCornerShape(cornerRad), scheme.surface, tone = GlassTone.Thin)
                .clickable {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onDismiss()
                },
            contentAlignment = Alignment.Center
        ) {
            Text("취소", style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
        }
    }
}
