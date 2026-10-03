package com.sleepysoong.hoard.ui.chat

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.sleepysoong.hoard.browser.DesktopInput
import com.sleepysoong.hoard.ui.glass.GlassPillButton
import com.sleepysoong.hoard.ui.glass.GlassPillTint
import com.sleepysoong.hoard.ui.glass.GlassTextField
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlin.math.min

/** Fit/letterbox coordinates use the original remote framebuffer, never a
 * downsampled bitmap or Android density. Captured drags clamp at the edge.
 */
private class DesktopViewport(val view: IntSize, val w: Int, val h: Int) {
    val scale = if (w > 0 && h > 0) min(view.width.toFloat() / w, view.height.toFloat() / h) else 0f
    private val left = (view.width - w * scale) / 2
    private val top = (view.height - h * scale) / 2
    fun point(position: Offset, clamp: Boolean = false): Pair<Int, Int>? {
        if (scale <= 0f) return null
        val x = (position.x - left) / scale; val y = (position.y - top) / scale
        if (!clamp && (x < 0 || y < 0 || x >= w || y >= h)) return null
        return x.toInt().coerceIn(0, w - 1) to y.toInt().coerceIn(0, h - 1)
    }
}

@Composable
internal fun BrowserDesktopSurface(
    image: ImageBitmap?, remoteWidth: Int, remoteHeight: Int,
    enabled: Boolean, send: (DesktopInput) -> Unit, modifier: Modifier = Modifier,
    waiting: String
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val focus = remember { FocusRequester() }
    val heldKeys = remember { mutableMapOf<Int, Int>() }
    var wasFocused by remember { mutableStateOf(false) }
    val currentSend by rememberUpdatedState(send)
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)
        .testTag("browser-desktop-input").onSizeChanged { size = it }.focusRequester(focus)
        .onFocusChanged {
            if (wasFocused && !it.isFocused) { heldKeys.clear(); currentSend(DesktopInput.ReleaseHeld) }
            wasFocused = it.isFocused
        }
        .onPreviewKeyEvent { event ->
            if (!enabled || image == null) return@onPreviewKeyEvent false
            val native = event.nativeKeyEvent
            val down = native.action == AndroidKeyEvent.ACTION_DOWN
            val keysym = if (down) heldKeys.getOrPut(native.keyCode) {
                desktopKeysym(native) ?: return@onPreviewKeyEvent false
            } else heldKeys.remove(native.keyCode) ?: desktopKeysym(native) ?: return@onPreviewKeyEvent false
            currentSend(DesktopInput.Key(keysym, down)); true
        }.focusable(enabled && image != null)
        .pointerInput(enabled, image != null, remoteWidth, remoteHeight, size) {
            if (!enabled || image == null) return@pointerInput
            val viewport = DesktopViewport(size, remoteWidth, remoteHeight)
            var captured = false
            var touchStart: Offset? = null
            var last = Offset.Zero
            var scrolled = false
            var touchX = 0f
            var touchY = 0f
            var wheelX = 0f
            var wheelY = 0f
            try { awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: continue
                    val position = change.position
                    val point = viewport.point(position, captured || touchStart != null) ?: continue
                    val mouse = change.type == PointerType.Mouse
                    fun pointer(mask: Int) { currentSend(DesktopInput.Pointer(point.first, point.second, mask)) }
                    when (event.type) {
                        PointerEventType.Press -> {
                            focus.requestFocus()
                            if (mouse) { captured = true; pointer(event.buttons.desktopMask()) }
                            else { touchStart = position; last = position; scrolled = false; touchX = 0f; touchY = 0f }
                        }
                        PointerEventType.Move -> {
                            if (mouse) pointer(if (captured) event.buttons.desktopMask() else 0)
                            else if (touchStart != null) {
                                if ((position - touchStart!!).getDistance() > viewConfiguration.touchSlop) scrolled = true
                                if (scrolled) {
                                    touchX += (last.x - position.x) / viewport.scale
                                    touchY += (last.y - position.y) / viewport.scale
                                    val dx = (touchX / 80).toInt() * 80; val dy = (touchY / 80).toInt() * 80
                                    if (dx != 0 || dy != 0) currentSend(DesktopInput.Scroll(point.first, point.second, dx, dy))
                                    touchX -= dx; touchY -= dy
                                }
                                last = position
                            }
                        }
                        PointerEventType.Release -> {
                            if (mouse) { pointer(event.buttons.desktopMask()); captured = event.buttons.desktopMask() != 0 }
                            else {
                                if (touchStart != null && !scrolled) { pointer(1); pointer(0) }
                                // Flush the sub-notch remainder: it becomes exactly one wheel click.
                                // A short swipe scrolls once, never one tick per pixel.
                                else if (touchStart != null && (touchX != 0f || touchY != 0f)) {
                                    currentSend(DesktopInput.Scroll(point.first, point.second, touchX.toInt(), touchY.toInt()))
                                }
                                touchStart = null
                            }
                        }
                        PointerEventType.Scroll -> {
                            wheelX += change.scrollDelta.x * 80; wheelY += change.scrollDelta.y * 80
                            val dx = (wheelX / 80).toInt() * 80; val dy = (wheelY / 80).toInt() * 80
                            if (dx != 0 || dy != 0) currentSend(DesktopInput.Scroll(point.first, point.second, dx, dy))
                            wheelX -= dx; wheelY -= dy
                        }
                    }
                    if (captured || touchStart != null || event.type == PointerEventType.Release || event.type == PointerEventType.Scroll) event.changes.forEach { it.consume() }
                }
            } } finally { currentSend(DesktopInput.ReleaseHeld) }
        }, contentAlignment = Alignment.Center) {
        if (image != null) Image(image, "현재 브라우저 화면", Modifier.fillMaxSize().testTag("browser-preview-image"), contentScale = ContentScale.Fit)
        else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            Text(waiting, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
        }
    }
}

private fun PointerButtons.desktopMask(): Int =
    (if (isPrimaryPressed) 1 else 0) or (if (isTertiaryPressed) 2 else 0) or (if (isSecondaryPressed) 4 else 0)

@Composable
internal fun BrowserInputControls(enabled: Boolean, send: (DesktopInput) -> Unit, showShortcuts: Boolean = true) {
    var text by remember { mutableStateOf("") } // UI-only, deliberately not saved or logged
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassTextField(text, { text = it.take(2000) }, enabled = enabled, singleLine = true,
                placeholder = { Text("입력할 텍스트") }, modifier = Modifier.weight(1f).testTag("browser-control-text"))
            GlassPillButton("입력", enabled = enabled && text.isNotEmpty(), tint = GlassPillTint.Accent,
                modifier = Modifier.semantics { role = Role.Button },
                onClick = { send(DesktopInput.Text(text)); text = ""; keyboard?.hide(); focus.clearFocus() })
        }
        if (showShortcuts) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("주소창" to listOf(0xffe3, 'l'.code), "Tab" to listOf(0xff09), "Enter" to listOf(0xff0d),
                "Esc" to listOf(0xff1b), "삭제" to listOf(0xff08), "전체 선택" to listOf(0xffe3, 'a'.code)).forEach { (label, keys) ->
                GlassPillButton(label, enabled = enabled, modifier = Modifier.semantics { role = Role.Button },
                    onClick = { send(DesktopInput.Chord(keys)) })
            }
        }
    }
}

private fun desktopKeysym(event: AndroidKeyEvent): Int? {
    val named = when (event.keyCode) {
        AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> 0xff0d
        AndroidKeyEvent.KEYCODE_TAB -> 0xff09
        AndroidKeyEvent.KEYCODE_ESCAPE -> 0xff1b
        AndroidKeyEvent.KEYCODE_DEL -> 0xff08
        AndroidKeyEvent.KEYCODE_FORWARD_DEL -> 0xffff
        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> 0xff51
        AndroidKeyEvent.KEYCODE_DPAD_UP -> 0xff52
        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> 0xff53
        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> 0xff54
        AndroidKeyEvent.KEYCODE_MOVE_HOME -> 0xff50
        AndroidKeyEvent.KEYCODE_MOVE_END -> 0xff57
        AndroidKeyEvent.KEYCODE_PAGE_UP -> 0xff55
        AndroidKeyEvent.KEYCODE_PAGE_DOWN -> 0xff56
        AndroidKeyEvent.KEYCODE_SHIFT_LEFT -> 0xffe1
        AndroidKeyEvent.KEYCODE_SHIFT_RIGHT -> 0xffe2
        AndroidKeyEvent.KEYCODE_CTRL_LEFT -> 0xffe3
        AndroidKeyEvent.KEYCODE_CTRL_RIGHT -> 0xffe4
        AndroidKeyEvent.KEYCODE_ALT_LEFT -> 0xffe9
        AndroidKeyEvent.KEYCODE_ALT_RIGHT -> 0xffea
        AndroidKeyEvent.KEYCODE_META_LEFT -> 0xffeb
        AndroidKeyEvent.KEYCODE_META_RIGHT -> 0xffec
        in AndroidKeyEvent.KEYCODE_F1..AndroidKeyEvent.KEYCODE_F12 -> 0xffbe + event.keyCode - AndroidKeyEvent.KEYCODE_F1
        else -> null
    }
    if (named != null) return named
    val code = event.getUnicodeChar(event.metaState and (AndroidKeyEvent.META_SHIFT_MASK or AndroidKeyEvent.META_CAPS_LOCK_ON))
    return when (code) { in 32..255 -> code; in 256..0x10ffff -> 0x01000000 or code; else -> null }
}
