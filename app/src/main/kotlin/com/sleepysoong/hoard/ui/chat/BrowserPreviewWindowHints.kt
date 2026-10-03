package com.sleepysoong.hoard.ui.chat

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.sleepysoong.hoard.browser.RemoteBrowsers

/** Supply a first-open aspect before browser_use starts connecting. The viewer
 * refines it from its measured image area; the keyboard never changes this hint.
 */
@Composable
internal fun BrowserPreviewWindowHints() {
    val size = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val insets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val width = size.width - insets.getLeft(density, direction) - insets.getRight(density, direction)
    val height = size.height - insets.getTop(density) - insets.getBottom(density)
    val landscape = width > height
    val panel = with(density) { 280.dp.roundToPx() }
    val chrome = with(density) { (if (landscape) 72.dp else 286.dp).roundToPx() }
    LaunchedEffect(width, height) {
        RemoteBrowsers.setPreviewViewport((width - if (landscape) panel else 0).coerceAtLeast(1),
            (height - chrome).coerceAtLeast(1))
    }
}
