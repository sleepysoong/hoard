package com.sleepysoong.hoard.ui.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
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
 * An iPadOS/iOS-style action menu that appears right next to the pressed
 * element (like iMessage long-press), rendered with real liquid glass.
 *
 * It is an in-window overlay (not a `Popup`) by design — Backdrop's captured
 * layer belongs to the Activity window, so a separate window would see nothing.
 * On the first pass we measure the card off-screen, then on subsequent passes
 * position it anchored above/below the bubble it belongs to.
 */
@Composable
fun BoxScope.GlassAnchoredMenu(
    anchor: Rect?,
    title: String?,
    message: String?,
    actions: List<GlassSheetAction>,
    onDismiss: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current

    val gapPx = with(density) { 8.dp.toPx() }
    val minMarginPx = with(density) { 16.dp.toPx() }
    val maxCardW = 340.dp
    val cornerRad = 20.dp

    var cardW by remember { mutableStateOf(0f) }
    var cardH by remember { mutableStateOf(0f) }
    var placedOrigin by remember { mutableStateOf(TransformOrigin(0.5f, 0.5f)) }
    var placedX by remember { mutableStateOf(0) }
    var placedY by remember { mutableStateOf(0) }

    val appear by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f),
        label = "anchor-menu-appear"
    )

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val totalW = constraints.maxWidth.toFloat()
        val totalH = constraints.maxHeight.toFloat()

        // Scrim — tap outside dismisses.
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
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .onGloballyPositioned {
                    cardW = it.size.width.toFloat()
                    cardH = it.size.height.toFloat()
                    if (anchor != null) {
                        val spaceAbove = anchor.top
                        val spaceBelow = totalH - anchor.bottom
                        val showAbove = spaceAbove >= cardH + gapPx ||
                            (spaceAbove >= spaceBelow && spaceBelow < cardH + gapPx)
                        val yTop = if (showAbove) {
                            (anchor.top - gapPx - cardH).coerceAtLeast(minMarginPx)
                        } else {
                            (anchor.bottom + gapPx).coerceAtLeast(minMarginPx)
                        }.coerceAtMost(totalH - cardH - minMarginPx)
                        val anchorCenterX = anchor.left + anchor.width / 2f
                        val xLeft = (anchorCenterX - cardW / 2f).coerceIn(minMarginPx, totalW - cardW - minMarginPx)
                        placedOrigin = TransformOrigin(
                            pivotFractionX = ((anchorCenterX - xLeft) / cardW).coerceIn(0f, 1f),
                            pivotFractionY = if (showAbove) 1f else 0f
                        )
                        placedX = xLeft.roundToInt()
                        placedY = yTop.roundToInt()
                    } else {
                        placedOrigin = TransformOrigin(0.5f, 0.5f)
                        placedX = ((totalW - cardW) / 2f).roundToInt()
                        placedY = ((totalH - cardH) / 2f).roundToInt()
                    }
                }
                .offset { IntOffset(placedX, placedY) }
                .alpha(appear)
                .graphicsLayer {
                    scaleX = 0.90f + 0.10f * appear
                    scaleY = 0.90f + 0.10f * appear
                    transformOrigin = placedOrigin
                },
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
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
                            Text(it, style = MaterialTheme.typography.headlineSmall)
                        }
                        message?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
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
}
