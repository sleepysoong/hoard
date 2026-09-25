package com.sleepysoong.hoard.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.Capsule

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    colors: CardColors = CardDefaults.cardColors(),
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.glassMaterial(shape, colors.containerColor), shape = shape,
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
    shape: Shape = RoundedCornerShape(20.dp),
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.glassMaterial(shape, color), shape = shape,
        color = Color.Transparent, contentColor = contentColor,
        tonalElevation = tonalElevation, content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors()
) {
    TopAppBar(
        title = title, navigationIcon = navigationIcon, actions = actions,
        modifier = modifier.glassMaterial(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)),
        colors = colors.copy(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent)
    )
}

/**
 * Interactive glass bottom bar (Liquid Bottom Tabs).
 * Selection pill refracts the bar; press state scales it. Follows the Backdrop
 * interactive-glass-bottom-bar tutorial structure.
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

    val hasFullGlassEffects = LocalGlassMode.current == GlassMode.Full
    val scheme = MaterialTheme.colorScheme
    val accent = scheme.primary
    val capsule = Capsule()
    val selectedPosition = androidx.compose.animation.core.animateFloatAsState(
        targetValue = (if (pressedTabIndex >= 0) pressedTabIndex else selectedTabIndex)
            .coerceIn(0, tabsCount - 1).toFloat(),
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.78f, stiffness = 520f),
        label = "glass-bottom-bar-selection"
    ).value
    val pressProgress = androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressedTabIndex >= 0) 1f else 0f,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 380f),
        label = "glass-bottom-bar-press"
    ).value
    val barMaterial = if (backdrop == null) {
        Modifier.background(scheme.surface.copy(alpha = 0.96f), capsule)
    } else {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { Capsule() },
            effects = {
                vibrancy()
                blur(8.dp.toPx())
                if (hasFullGlassEffects) lens(24.dp.toPx(), 24.dp.toPx())
            },
            onDrawSurface = { drawRect(scheme.surface.copy(alpha = 0.58f)) }
        )
    }

    BoxWithConstraints(
        modifier = modifier
            .heightIn(min = 80.dp)
            .shadow(
                elevation = 18.dp,
                shape = capsule,
                clip = false,
                ambientColor = Color(0x140A2448),
                spotColor = Color(0x220A2448)
            )
            .then(barMaterial)
            .border(1.dp, scheme.outline.copy(alpha = 0.5f), capsule)
            .clip(capsule),
        contentAlignment = androidx.compose.ui.Alignment.CenterStart
    ) {
        val tabWidth = (maxWidth - 8.dp) / tabsCount
        val selectionMaterial = if (backdrop == null) {
            Modifier.background(accent.copy(alpha = 0.12f), Capsule())
        } else {
            Modifier.drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = {
                    if (hasFullGlassEffects) {
                        lens((12.dp + 4.dp * pressProgress).toPx(), (20.dp + 8.dp * pressProgress).toPx())
                    } else {
                        blur(4.dp.toPx())
                    }
                },
                highlight = { Highlight.Default.copy(alpha = 0.55f + 0.4f * pressProgress) },
                layerBlock = {
                    scaleX = 1f + 0.08f * pressProgress
                    scaleY = 1f + 0.08f * pressProgress
                },
                onDrawSurface = { drawRect(accent.copy(alpha = 0.13f)) }
            )
        }

        Box(
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.CenterStart)
                .offset(x = 4.dp + tabWidth * selectedPosition)
                .width(tabWidth)
                .height(72.dp)
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .then(selectionMaterial)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            content = content
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
    shape: Shape = RoundedCornerShape(28.dp)
) {
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = true)) {
        BoxWithConstraints(Modifier.widthIn(max = 560.dp).fillMaxWidth().safeDrawingPadding().imePadding()) {
            GlassHost(
                modifier = modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight),
                fillWindow = false,
                backgroundShape = shape
            ) {
                GlassSurface(modifier = Modifier.fillMaxWidth(), shape = shape) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        if (title != null) {
                            Box(Modifier.semantics { heading() }) {
                                ProvideTextStyle(MaterialTheme.typography.headlineSmall) { title() }
                            }
                        }
                        if (text != null) {
                            Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                                ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
                            }
                        }
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End),
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
