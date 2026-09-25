package com.sleepysoong.hoard.ui.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

data class GlassSheetAction(
    val icon: ImageVector,
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit
)

/**
 * iPadOS-style action sheet, rendered as real liquid glass.
 *
 * IMPORTANT: it is drawn as an inline overlay (not a Compose `Popup`) on purpose.
 * Backdrop's captured layer belongs to the Activity window, so a Popup/Dialog
 * window cannot sample it — an inline overlay keeps the glass shader valid while
 * still looking and behaving like a modal popover (scrim + dismiss on outside tap).
 */
@Composable
fun BoxScope.GlassActionSheet(
    title: String?,
    message: String?,
    actions: List<GlassSheetAction>,
    onDismiss: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val appear by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
        label = "sheet-appear"
    )

    // Scrim
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
            .align(Alignment.Center)
            .widthIn(max = 340.dp)
            .padding(horizontal = 28.dp)
            .alpha(appear),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (title != null || message != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .glassMaterial(RoundedCornerShape(20.dp), scheme.surface)
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

        // Actions card — the glass surface users interact with.
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .glassMaterial(RoundedCornerShape(20.dp), scheme.surface)
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
                        color = tint
                    )
                }
            }
        }

        // Separate iOS-style cancel button.
        Box(
            Modifier
                .fillMaxWidth()
                .height(54.dp)
                .clip(RoundedCornerShape(20.dp))
                .glassMaterial(RoundedCornerShape(20.dp), scheme.surface, compact = true)
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
