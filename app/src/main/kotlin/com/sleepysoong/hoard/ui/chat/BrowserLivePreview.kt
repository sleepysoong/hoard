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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.sleepysoong.hoard.browser.BrowserPreviewTarget
import com.sleepysoong.hoard.browser.BrowserPreviews
import com.sleepysoong.hoard.ui.glass.GlassIconButton
import com.sleepysoong.hoard.ui.glass.GlassSurface
import com.sleepysoong.hoard.ui.glass.liquidClickable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** One bounded live frame shared by the compact view and its full-window viewer. */
@Composable
internal fun BrowserLivePreview(target: BrowserPreviewTarget, modifier: Modifier = Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val keyboard = LocalSoftwareKeyboardController.current
    var image by remember(target) { mutableStateOf<ImageBitmap?>(null) }
    var problem by remember(target) { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable(target) { mutableStateOf(false) }

    LaunchedEffect(target, lifecycle) {
        // Hidden chats, a dismissed preview and background activities have no
        // capture loop. The preview never starts a second SSH/Chrome connection.
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                while (isActive) {
                    try {
                        image = withContext(Dispatchers.IO) {
                            target.browser.previewFrame()?.let(::decodePreview)
                        }
                        problem = null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        image = null
                        problem = "미리보기 연결을 기다리는 중"
                    }
                    // At most two updates a second; capture latency naturally lowers
                    // this on slow links. No backlog of frames/bitmaps is retained.
                    delay(500)
                }
            } finally {
                image = null
            }
        }
    }

    GlassSurface(
        modifier = modifier.fillMaxWidth().testTag("browser-live-preview").liquidClickable {
            keyboard?.hide()
            expanded = true
        },
        shape = RoundedCornerShape(18.dp)
    ) {
        BoxWithConstraints(Modifier.padding(10.dp)) {
            val thumbnailWidth = (maxWidth * 0.36f).coerceAtMost(160.dp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
    }

    if (expanded) {
        Dialog(
            onDismissRequest = { expanded = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            // A dialog owns another window: keep it opaque and do not use the
            // activity window's glass backdrop shader here.
            Surface(Modifier.fillMaxSize().testTag("browser-preview-fullscreen"), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("브라우저 실시간", style = MaterialTheme.typography.titleMedium)
                            Text("보기 전용", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { expanded = false }) {
                            Icon(Icons.Rounded.Close, contentDescription = "전체화면 닫기")
                        }
                    }
                    PreviewImage(image, Modifier.weight(1f).fillMaxWidth(), compact = false,
                        waiting = problem ?: "브라우저 화면을 기다리는 중")
                }
            }
        }
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
private fun decodePreview(bytes: ByteArray): ImageBitmap {
    require(bytes.size in 1..5 * 1024 * 1024) { "미리보기 이미지 크기 제한 초과" }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 64_000_000) {
        "미리보기 화면을 읽을 수 없습니다"
    }
    var sample = 1
    while (bounds.outWidth / sample > 1_600 || bounds.outHeight / sample > 1_600) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return (BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        ?: error("미리보기 화면을 읽을 수 없습니다")).asImageBitmap()
}
