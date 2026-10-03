package com.sleepysoong.hoard.ui.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.sleepysoong.hoard.browser.BrowserPreviewTarget
import com.sleepysoong.hoard.browser.BrowserPreviews
import com.sleepysoong.hoard.browser.BrowserControl
import com.sleepysoong.hoard.browser.DesktopInput
import com.sleepysoong.hoard.ui.glass.GlassIconButton
import com.sleepysoong.hoard.ui.glass.GlassHost
import com.sleepysoong.hoard.ui.glass.GlassPillButton
import com.sleepysoong.hoard.ui.glass.GlassPillTint
import com.sleepysoong.hoard.ui.glass.GlassSurface
import com.sleepysoong.hoard.ui.glass.GlassSlider
import com.sleepysoong.hoard.ui.glass.liquidClickable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val PREVIEW_JPEG_QUALITIES = listOf(20, 35, 55, 75, 90)
private val PREVIEW_QUALITY_LABELS = listOf("매우 낮음", "낮음", "보통", "높음", "매우 높음")

/** One bounded live frame shared by the compact view and its full-window viewer. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun BrowserLivePreview(
    target: BrowserPreviewTarget,
    qualityLevel: Int,
    onQualityChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val keyboard = LocalSoftwareKeyboardController.current
    var image by remember(target) { mutableStateOf<ImageBitmap?>(null) }
    var remoteWidth by remember(target) { mutableIntStateOf(0) }
    var remoteHeight by remember(target) { mutableIntStateOf(0) }
    var problem by remember(target) { mutableStateOf<String?>(null) }
    var inputProblem by remember(target) { mutableStateOf<String?>(null) }
    var control by remember(target) { mutableStateOf<BrowserControl?>(null) }
    val inputs = remember(target) { Channel<DesktopInput>(64) }
    var expanded by rememberSaveable(target) { mutableStateOf(false) }
    var quality by remember(target, qualityLevel) { mutableIntStateOf(qualityLevel.coerceIn(1, 5)) }
    val currentQuality by rememberUpdatedState(quality)
    var viewport by remember(target) { mutableStateOf(IntSize.Zero) }
    var resizing by remember(target) { mutableStateOf(false) }
    val currentResizing by rememberUpdatedState(resizing)

    DisposableEffect(target) { onDispose { target.browser.stopPreview() } }

    LaunchedEffect(target, lifecycle) {
        // Hidden chats, a dismissed preview and background activities have no
        // capture loop. The preview never starts a second SSH/Chrome connection.
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            target.browser.startPreview()
            try {
                while (isActive) {
                    try {
                        val jpegQuality = PREVIEW_JPEG_QUALITIES[currentQuality - 1]
                        val frame = withContext(Dispatchers.IO) {
                            target.browser.previewFrame(jpegQuality)?.let(::decodePreview)
                        }
                        if (!currentResizing) {
                            image = frame?.image
                            remoteWidth = frame?.width ?: 0
                            remoteHeight = frame?.height ?: 0
                        }
                        problem = null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        image = null
                        problem = error.message ?: "전체 Chrome 화면에 연결하지 못했습니다"
                    }
                    // At most ten updates a second. Keep one request in flight:
                    // slow links lower the rate rather than building a frame backlog.
                    delay(100)
                }
            } finally {
                image = null
                target.browser.stopPreview()
            }
        }
    }
    LaunchedEffect(target, control, viewport) {
        val lease = control ?: return@LaunchedEffect
        if (viewport.width <= 0 || viewport.height <= 0) return@LaunchedEffect
        resizing = true
        image = null
        while (inputs.tryReceive().isSuccess) { /* Old geometry must not replay on the resized window. */ }
        try {
            lease.resizeViewport(viewport.width, viewport.height)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { inputProblem = error.message ?: "Chrome 창 크기를 맞추지 못했습니다" }
        finally { resizing = false }
    }

    LaunchedEffect(target, expanded, lifecycle) {
        if (expanded && target.browser.supportsInput) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var lease: BrowserControl? = null
            try {
                lease = target.browser.acquireControl()
                control = lease
                for (event in inputs) {
                    try { lease.input(event); inputProblem = null }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        inputProblem = error.message ?: "직접 조작 연결이 끊겼습니다"
                        while (inputs.tryReceive().isSuccess) { /* Never replay stale input after reconnecting. */ }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { inputProblem = error.message ?: "직접 조작을 시작하지 못했습니다" }
            finally {
                control = null
                while (inputs.tryReceive().isSuccess) { /* Closing discards pending UI-only events. */ }
                withContext(NonCancellable) { lease?.release() }
            }
        }
    }
    val send: (DesktopInput) -> Unit = { event ->
        if (control != null && image != null && !resizing && inputs.trySend(event).isFailure) {
            inputProblem = "입력 연결이 느려 직접 조작을 중지했습니다"
            expanded = false // release all held keys/buttons rather than losing an up event
        }
    }

    GlassSurface(
        modifier = modifier.fillMaxWidth().testTag("browser-live-preview"),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.padding(10.dp)) {
            BoxWithConstraints {
                val thumbnailWidth = (maxWidth * 0.36f).coerceAtMost(160.dp)
                Row(modifier = Modifier.fillMaxWidth().liquidClickable {
                    keyboard?.hide()
                    expanded = true
                }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PreviewImage(image, Modifier.width(thumbnailWidth).height(78.dp).clip(RoundedCornerShape(10.dp)), compact = true)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("브라우저 실시간", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (image != null) "누르면 전체화면" else problem ?: "화면 연결 중",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Rounded.Fullscreen, contentDescription = "전체화면 열기",
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    }
                    GlassIconButton(onClick = { BrowserPreviews.dismiss(target) }, modifier = Modifier.testTag("browser-preview-close")) {
                        Icon(Icons.Rounded.Close, contentDescription = "브라우저 미리보기 닫기")
                    }
                }
            }
            PreviewQualityControl(quality, { quality = it }, { onQualityChange(quality) })
        }
    }

    if (expanded) {
        Dialog(
            onDismissRequest = { expanded = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            // Record an opaque, dialog-local sibling. Cross-window backdrop
            // sampling is invalid; local recording keeps every control liquid.
            GlassHost(Modifier.testTag("browser-preview-fullscreen")) {
                val window = LocalWindowInfo.current.containerSize
                val landscape = window.width > window.height
                val imeVisible = WindowInsets.isImeVisible
                Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("브라우저 실시간", style = MaterialTheme.typography.titleMedium)
                            Text(if (!target.browser.supportsInput) "전체 Chrome 화면" else if (control == null) "AI 작업 마무리 대기" else "직접 조작 · AI 브라우저 대기", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (target.browser.supportsInput) GlassPillButton("AI 계속", onClick = { expanded = false }, tint = GlassPillTint.Accent,
                            modifier = Modifier.semantics { role = Role.Button })
                        GlassIconButton(onClick = { expanded = false }) {
                            Icon(Icons.Rounded.Close, contentDescription = "전체화면 닫기")
                        }
                    }
                    val desktop: @Composable (Modifier) -> Unit = { area ->
                        BrowserDesktopSurface(image, remoteWidth, remoteHeight, control != null && !resizing, send,
                            area.onSizeChanged {
                                // Do not rearrange Chrome while typing into the phone's IME.
                                if (!imeVisible && it.width > 0 && it.height > 0) viewport = it
                            }, waiting = problem ?: "전체 Chrome 화면을 기다리는 중")
                    }
                    val controls: @Composable () -> Unit = {
                        inputProblem?.let { Text(it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp)) }
                        if (target.browser.supportsInput) BrowserInputControls(control != null && image != null && !resizing, send,
                            showShortcuts = !imeVisible)
                        if (!imeVisible) PreviewQualityControl(quality, { quality = it }, { onQualityChange(quality) },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                    if (landscape) Row(Modifier.weight(1f).fillMaxWidth()) {
                        desktop(Modifier.weight(1f).fillMaxSize())
                        Column(Modifier.width(280.dp).verticalScroll(rememberScrollState())) { controls() }
                    } else {
                        desktop(Modifier.weight(1f).fillMaxWidth())
                        controls()
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewQualityControl(
    level: Int,
    onLevelChange: (Int) -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("화질 · ${PREVIEW_QUALITY_LABELS[level - 1]}", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("$level/5", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        val sliderModifier = Modifier.fillMaxWidth().testTag("browser-preview-quality")
            .semantics { contentDescription = "브라우저 미리보기 화질, 5단계" }
        val change: (Float) -> Unit = { onLevelChange(it.roundToInt().coerceIn(1, 5)) }
        // Three intermediate stops + both endpoints = exactly five quality levels.
        GlassSlider(value = level.toFloat(), onValueChange = change, valueRange = 1f..5f, steps = 3,
            onValueChangeFinished = onFinished, modifier = sliderModifier)
        Text("낮출수록 전송량 감소", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PreviewImage(image: ImageBitmap?, modifier: Modifier, compact: Boolean, waiting: String = "연결 대기") {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (image != null) {
            Image(image, contentDescription = "현재 브라우저 화면", modifier = Modifier.fillMaxSize().testTag("browser-preview-image"),
                contentScale = ContentScale.Fit)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                if (!compact) Text(waiting, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

/** Decode off the UI thread, bound even a large VPS viewport to a modest bitmap. */
private data class DecodedPreview(val image: ImageBitmap, val width: Int, val height: Int)

private fun decodePreview(bytes: ByteArray): DecodedPreview {
    require(bytes.size in 1..5 * 1024 * 1024) { "미리보기 이미지 크기 제한 초과" }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 64_000_000) {
        "미리보기 화면을 읽을 수 없습니다"
    }
    var sample = 1
    while (bounds.outWidth / sample > 1_600 || bounds.outHeight / sample > 1_600) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val image = (BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        ?: error("미리보기 화면을 읽을 수 없습니다")).asImageBitmap()
    return DecodedPreview(image, bounds.outWidth, bounds.outHeight)
}
