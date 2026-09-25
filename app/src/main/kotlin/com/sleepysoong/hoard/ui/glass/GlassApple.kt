package com.sleepysoong.hoard.ui.glass

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sleepysoong.hoard.ui.theme.IOSSegmentThumbDark

/** iOS Large Title. */
@Composable
fun LargeTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.displaySmall, modifier = modifier)
}

/** iOS section header: small gray label above a grouped section. */
@Composable
fun IOSSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 16.dp, bottom = 6.dp)
    )
}

/** iOS inset-grouped container: solid card, 12dp corners, content rows inside. */
@Composable
fun IOSGroupedSection(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        androidx.compose.foundation.layout.Column(content = content)
    }
}

/** iOS separator with 16dp leading inset. */
@Composable
fun ColumnScope.IOSRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
    )
}

/**
 * iOS segmented control: gray track, sliding solid thumb, no ripple.
 */
@Composable
fun IOSSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (options.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    BoxWithConstraints(
        modifier
            .height(36.dp)
            .background(scheme.surfaceContainerHighest, RoundedCornerShape(10.dp))
            .padding(2.dp)
    ) {
        val segWidth = (maxWidth - 4.dp) / options.size
        val thumbX by animateDpAsState(
            targetValue = segWidth * selectedIndex.coerceIn(0, options.lastIndex),
            animationSpec = spring(dampingRatio = 0.85f, stiffness = 700f),
            label = "ios-segment"
        )
        Box(
            Modifier
                .offset(x = thumbX)
                .width(segWidth)
                .fillMaxHeight()
                .shadow(2.dp, RoundedCornerShape(8.dp))
                .background(
                    if (dark) IOSSegmentThumbDark else Color.White,
                    RoundedCornerShape(8.dp)
                )
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                val selected = i == selectedIndex
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                            onClick = { onSelect(i) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                        ),
                        color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * Scope-agnostic AnimatedVisibility. Inside a Column/Row lambda the stock
 * `AnimatedVisibility` resolves to the scoped extension, so overlays that are
 * placed in a Box inside a Column need this wrapper.
 */
@Composable
fun GlassAnimatedVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    enter: EnterTransition = fadeIn(),
    exit: ExitTransition = fadeOut(),
    content: @Composable () -> Unit
) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = enter,
        exit = exit
    ) { content() }
}

/** iMessage-style typing indicator: three pulsing dots. */
@Composable
fun IOSTypingDots(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "typing")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "typing-t"
    )
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) { i ->
            val phase = ((t - i + 3f) % 3f) / 3f
            Box(
                Modifier
                    .size(8.dp)
                    .alpha(0.35f + 0.65f * (1f - kotlin.math.abs(phase - 0.5f) * 2f).coerceIn(0f, 1f))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
            )
        }
    }
}
