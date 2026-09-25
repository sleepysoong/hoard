package com.sleepysoong.hoard.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/**
 * Glass bottom sheet (Backdrop tutorial).
 * The sheet samples the activity backdrop and re-exports its own surface so
 * controls inside the sheet (buttons, sliders) refract the sheet itself.
 * Never use layerBackdrop here — it would create a RenderThread loop.
 */
@Composable
fun BoxScope.GlassBottomSheetPanel(
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    if (backdrop == null || LocalGlassMode.current == GlassMode.Off) {
        Column(
            modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding()
                .padding(12.dp)
                .glassMaterial(shape, scheme.surface)
                .padding(20.dp),
            content = content
        )
        return
    }
    val sheetBackdrop = rememberLayerBackdrop()
    val fullGlass = LocalGlassMode.current == GlassMode.Full
    Column(
        modifier
            .fillMaxWidth()
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .imePadding()
            .padding(12.dp)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(10.dp.toPx())
                    if (fullGlass) lens(24.dp.toPx(), 48.dp.toPx())
                },
                exportedBackdrop = sheetBackdrop,
                onDrawSurface = { drawRect(scheme.surface.copy(alpha = 0.55f)) }
            )
            .clip(shape)
            .padding(20.dp)
    ) {
        CompositionLocalProvider(LocalGlassBackdrop provides sheetBackdrop) {
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = scheme.surface.copy(alpha = 0.92f),
        contentColor = scheme.onSurface,
        dragHandle = null
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            // Solid grabber (no gradient). Note: ModalBottomSheet lives in its own
            // window, so it must NOT sample the Activity backdrop (cross-window
            // GraphicsLayer access). Keep this grabber solid.
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 12.dp)
                    .size(width = 36.dp, height = 5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(scheme.onSurface.copy(alpha = 0.25f))
            )
            content()
            Spacer(Modifier.height(12.dp))
        }
    }
}
