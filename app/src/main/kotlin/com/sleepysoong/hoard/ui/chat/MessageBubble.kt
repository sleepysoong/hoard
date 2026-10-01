package com.sleepysoong.hoard.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sleepysoong.hoard.data.ChatMessage
import com.sleepysoong.hoard.data.MessageRole
import com.sleepysoong.hoard.data.formatElapsed
import com.sleepysoong.hoard.ui.glass.GlassMotion
import com.sleepysoong.hoard.ui.glass.GlassTone
import com.sleepysoong.hoard.ui.glass.IOSTypingDots
import com.sleepysoong.hoard.ui.glass.glassMaterial
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.platform.testTag
import com.sleepysoong.hoard.data.RouteAttempt
import com.sleepysoong.hoard.data.RoutingInfo
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.Animatable

/**
 * Messenger-grade bubble: content-sized (never full width), iMessage geometry.
 * My messages are solid system blue; Hoard's are frosted liquid glass so the
 * wallpaper refracts through them. Long-press hands off to the glass action
 * sheet rendered by ChatScreen — no buttons are pinned under the bubble.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: ChatMessage,
    modelName: String?,
    maxBubbleWidth: Dp,
    groupedWithPrevious: Boolean,
    showFooter: Boolean,
    onLongPress: (Rect) -> Unit,
    modifier: Modifier = Modifier,
    /** Pop in on first composition. False for bubbles already there when the chat opened. */
    animateEntrance: Boolean = true,
    exiting: Boolean = false,
    onExitFinished: () -> Unit = {}
) {
    val isUser = message.role == MessageRole.User
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    var bubbleBoundsInRoot by remember { mutableStateOf(Rect.Zero) }
    // Entrance: pops out of its tail corner with a soft overshoot, once per bubble.
    // (animateFloatAsState(targetValue = 1f) would start *at* 1 and never play.)
    val appear = remember(message.id) {
        Animatable(if (animateEntrance) 0f else 1f, visibilityThreshold = GlassMotion.SCALE_THRESHOLD)
    }
    LaunchedEffect(message.id) {
        if (appear.value < 1f) appear.animateTo(1f, GlassMotion.bouncy())
    }
    val retire = remember(message.id) { Animatable(1f) }
    val exitFinished by androidx.compose.runtime.rememberUpdatedState(onExitFinished)
    LaunchedEffect(exiting) {
        if (exiting) {
            retire.animateTo(0f, GlassMotion.leave())
            exitFinished()
        }
    }

    val shape = RoundedCornerShape(
        topStart = 19.dp,
        topEnd = 19.dp,
        bottomStart = if (isUser) 19.dp else 5.dp,
        bottomEnd = if (isUser) 5.dp else 19.dp
    )
    val textColor = if (isUser) scheme.onPrimary else scheme.onSurface

    Box(
        modifier
            .fillMaxWidth()
            // iMessage-ish entrance: new bubbles glide in from their tail corner.
            .graphicsLayer {
                val p = appear.value
                scaleX = (0.6f + 0.4f * p) * (0.92f + 0.08f * retire.value)
                scaleY = (0.6f + 0.4f * p) * (0.92f + 0.08f * retire.value)
                translationY = (1f - p) * 24.dp.toPx()
                alpha = p.coerceIn(0f, 1f) * retire.value
                transformOrigin = TransformOrigin(
                    pivotFractionX = if (isUser) 1f else 0f,
                    pivotFractionY = 0.85f
                )
            },
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = maxBubbleWidth)
        ) {
            // Model name sits on the footer line, not above the bubble.

            // Hoard bubble = liquid glass (frosted neutral so it reads on white);
            // my bubble = solid iOS blue.
            val bubbleModifier = if (isUser) {
                Modifier
                    .clip(shape)
                    .background(scheme.primary)
            } else {
                Modifier
                    .clip(shape)
                    .glassMaterial(shape, scheme.surfaceContainer, tone = GlassTone.Thin)
            }

            Column(
                bubbleModifier
                    .onGloballyPositioned { bubbleBoundsInRoot = it.boundsInRoot() }
            .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongPress(bubbleBoundsInRoot)
                        },
                        onLongClickLabel = "메시지 메뉴"
                    )
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (message.attachments.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        message.attachments.forEach { a ->
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (isUser) scheme.onPrimary.copy(alpha = 0.18f)
                                        else scheme.onSurface.copy(alpha = 0.07f)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Rounded.AttachFile, contentDescription = null,
                                    tint = textColor, modifier = Modifier.size(12.dp))
                                Text(a.name, style = MaterialTheme.typography.labelSmall, color = textColor)
                            }
                        }
                    }
                }

                if (!isUser && message.thinking.isNotEmpty()) WorkPanel(message.thinking, textColor)

                if (message.text.isEmpty() && message.isStreaming) {
                    IOSTypingDots(Modifier.padding(vertical = 6.dp))
                } else if (message.text.isNotEmpty()) {
                    if (isUser) {
                        // What I typed is shown verbatim.
                        Text(message.text, style = MaterialTheme.typography.bodyLarge, color = textColor)
                    } else {
                        MarkdownText(message.text, color = textColor, isStreaming = message.isStreaming)
                    }
                }
                // A failed reply says why instead of leaving an empty bubble.
                message.errorText?.let { err ->
                    Text(
                        "⚠ $err",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.error,
                        modifier = Modifier.testTag("reply-error")
                    )
                }
                if (!isUser) message.routing?.let { RoutingPanel(it, textColor) }
            }

            if (showFooter) {
                Row(
                    modifier = Modifier.padding(top = 3.dp, start = 12.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // The answering model is on the # pill inside the bubble, not repeated here.
                    Text(
                        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.createdAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant
                    )
                    if (!isUser && message.totalTokens > 0) {
                        Text(
                            "· ${formatElapsed(message.elapsedMs)} · ${message.totalTokens} 토큰",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
