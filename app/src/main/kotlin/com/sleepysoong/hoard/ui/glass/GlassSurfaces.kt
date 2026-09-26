package com.sleepysoong.hoard.ui.glass

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kyant.backdrop.Backdrop

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(GlassTokens.cardRadius),
    colors: CardColors = CardDefaults.cardColors(),
    tone: GlassTone = GlassTone.Regular,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.glassMaterial(shape, colors.containerColor, tone),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent, contentColor = colors.contentColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        content = content
    )
}

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(GlassTokens.cardRadius),
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tone: GlassTone = GlassTone.Regular,
    lifted: Boolean = true,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.glassMaterial(shape, color, tone, lifted = lifted),
        shape = shape,
        color = Color.Transparent,
        contentColor = contentColor,
        tonalElevation = 0.dp,
        content = content
    )
}

/**
 * Floating navigation bar — the iOS-grade header used by all top-level screens.
 *
 * Layout contract:
 * - Left slot is always 44dp wide (nav icon or a blank spacer), so the centered
 *   title stays optically centered.
 * - Title is `headlineSmall`, subtitle `labelSmall` — same style everywhere.
 * - Content of `actions` should use [GlassIconButton] with the same tint scheme.
 *
 * Call sites overriding defaults tune via [navigationIcon], [title]*, and
 * [actions]. Prefer this over composing a new top bar per screen.
 */
@Composable
fun GlassFloatingBar(
    title: String,
    subtitle: String? = null,
    onTitleClick: (() -> Unit)? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    modifier: Modifier = Modifier,
    tone: GlassTone = GlassTone.Thick
) {
    GlassSurface(modifier = modifier, shape = RoundedCornerShape(20.dp), tone = tone) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                navigationIcon?.invoke()
            }
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = if (onTitleClick != null) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onTitleClick
                        )
                    } else Modifier
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier = if (onTitleClick != null) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onTitleClick
                            )
                        } else Modifier
                    )
                }
            }
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                actions?.let { Row(content = it) }
            }
        }
    }
}

/**
 * iOS 26 floating tab bar: one thick glass bar, a liquid capsule that morphs
 * between tabs, and press deformation on the whole bar.
 */
@Composable
fun GlassBottomBar(
    selectedTabIndex: Int,
    tabsCount: Int,
    backdrop: Backdrop?,
    pressedTabIndex: Int,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    if (tabsCount <= 0) return
    val scheme = MaterialTheme.colorScheme
    val capsule = RoundedCornerShape(50)

    BoxWithConstraints(
        modifier = modifier.height(GlassTokens.barHeight),
        contentAlignment = Alignment.CenterStart
    ) {
        // Bar body
        Box(
            Modifier
                .matchParentSize()
                .glassMaterial(capsule, scheme.surface, GlassTone.Thick)
        )

        val tabWidth = maxWidth / tabsCount
        val position by animateFloatAsState(
            targetValue = (if (pressedTabIndex >= 0) pressedTabIndex else selectedTabIndex)
                .coerceIn(0, tabsCount - 1).toFloat(),
            animationSpec = GlassMotion.springSnappy,
            label = "tab-indicator"
        )
        val press by animateFloatAsState(
            targetValue = if (pressedTabIndex >= 0) 1f else 0f,
            animationSpec = GlassMotion.springSnappy,
            label = "tab-press"
        )

        // Liquid selection capsule — thicker refraction, squashes on press.
        Box(
            Modifier
                .offset(x = tabWidth * position)
                .width(tabWidth)
                .height(GlassTokens.barHeight - 12.dp)
                .padding(horizontal = 5.dp, vertical = 6.dp)
                .then(
                    if (backdrop == null) {
                        Modifier.background(scheme.primary.copy(alpha = 0.12f), capsule)
                    } else {
                        Modifier.glassMaterial(
                            shape = capsule,
                            tint = scheme.primary,
                            tone = GlassTone.Thin,
                            lifted = false
                        )
                    }
                )
        )

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            content()
        }
    }
}

/**
 * One tab inside the floating glass bar. Icon + 10sp label, no ripple, haptic
 * on tap, and its press state is reported upward so the bar can deform.
 */
@Composable
fun RowScope.GlassTabItem(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onPressedChange: (Boolean) -> Unit = {},
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current
    val tint by animateColorAsState(
        targetValue = if (selected) scheme.primary else scheme.onSurfaceVariant,
        animationSpec = GlassMotion.fastColor,
        label = "tab-tint"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.04f else 1f,
        animationSpec = GlassMotion.springSnappy,
        label = "tab-icon-scale"
    )

    LaunchedEffect(pressed) { onPressedChange(pressed) }

    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = tint,
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                }
        )
        Text(
            title,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = tint,
            maxLines = 1
        )
    }
}

/** Each Dialog gets its own source. Never reuse the Activity's graphics layer across windows. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GlassDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = RoundedCornerShape(GlassTokens.sheetRadius)
) {
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = true)) {
        BoxWithConstraints(
            Modifier
                .widthIn(max = 400.dp)
                .fillMaxWidth()
                .safeDrawingPadding()
                .imePadding()
        ) {
            GlassHost(
                modifier = modifier.widthIn(max = 400.dp).fillMaxWidth().heightIn(max = maxHeight),
                fillWindow = false,
                backgroundShape = shape
            ) {
                GlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = shape,
                    tone = GlassTone.Thick
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        if (title != null) {
                            Box(Modifier.semantics { heading() }) {
                                ProvideTextStyle(MaterialTheme.typography.titleLarge) { title() }
                            }
                        }
                        if (text != null) {
                            Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                                ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
                            }
                        }
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            dismissButton?.invoke()
                            confirmButton()
                        }
                    }
                }
            }
        }
    }
}

/** iOS grabber for sheet headers. */
@Composable
fun SheetGrabber(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width = 36.dp, height = 5.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f))
    )
}
