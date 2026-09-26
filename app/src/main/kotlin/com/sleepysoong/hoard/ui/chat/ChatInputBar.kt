package com.sleepysoong.hoard.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.sleepysoong.hoard.data.MockData
import com.sleepysoong.hoard.data.UiAttachment
import com.sleepysoong.hoard.ui.glass.GlassSurface
import com.sleepysoong.hoard.ui.glass.GlassTone
import com.sleepysoong.hoard.ui.glass.glassMaterial
import com.sleepysoong.hoard.ui.glass.liquidClickable
import java.util.UUID

private val ControlSize = 44.dp

/**
 * iMessage-grade composer.
 *
 * Every control is the same 44dp box on a shared bottom baseline, so the attach
 * buttons, the text field and the send button line up exactly. The field has no
 * container of its own — it sits directly on the glass bar like iOS.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    attachments: List<UiAttachment>,
    onAttachmentsChange: (List<UiAttachment>) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) {
            onAttachmentsChange(
                attachments + uris.mapIndexed { i, uri: Uri ->
                    UiAttachment(
                        id = "att-" + UUID.randomUUID().toString().take(6),
                        name = "사진 ${attachments.size + i + 1}.jpg",
                        mime = "image/*",
                        sizeBytes = 0,
                        uri = uri
                    )
                }
            )
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            onAttachmentsChange(
                attachments + uris.map { uri ->
                    UiAttachment(
                        id = "att-" + UUID.randomUUID().toString().take(6),
                        name = uri.lastPathSegment?.substringAfterLast('/') ?: "파일",
                        mime = "*/*",
                        sizeBytes = 0,
                        uri = uri
                    )
                }
            )
        }
    }
    val showSlash = value.trimStart().startsWith("/") && !value.contains(" ")
    val canSend = value.isNotBlank() || attachments.isNotEmpty()

    GlassSurface(modifier = modifier, shape = RoundedCornerShape(28.dp), tone = GlassTone.Thick) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (attachments.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    attachments.forEach { a ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(scheme.surfaceContainerHighest)
                                .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp)
                        ) {
                            Text("📎 ${a.name}", style = MaterialTheme.typography.labelMedium)
                            Box(
                                Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .liquidClickable(haptic = false) {
                                        onAttachmentsChange(attachments.filterNot { it.id == a.id })
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = "첨부 제거",
                                    modifier = Modifier.size(15.dp),
                                    tint = scheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            if (showSlash) {
                val query = value.trimStart().removePrefix("/")
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(scheme.surfaceContainerHigh)
                        .padding(vertical = 4.dp)
                ) {
                    Column {
                        MockData.slashCommands
                            .filter { it.command.removePrefix("/").startsWith(query) }
                            .take(5)
                            .forEachIndexed { i, cmd ->
                                if (i > 0) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 12.dp),
                                        thickness = 0.5.dp,
                                        color = scheme.onSurface.copy(alpha = 0.08f)
                                    )
                                }
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .liquidClickable {
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onValueChange(cmd.command + " ")
                                        }
                                        .padding(horizontal = 12.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        cmd.command,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = scheme.primary
                                    )
                                    Text(
                                        cmd.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = scheme.onSurfaceVariant
                                    )
                                }
                            }
                    }
                }
            }

            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Liquid-glass attach buttons, same footprint as the send button.
                GlassAttachButton(
                    onClick = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "사진 첨부", modifier = Modifier.size(22.dp))
                }
                GlassAttachButton(
                    onClick = { filePicker.launch(arrayOf("*/*")) }
                ) {
                    Icon(Icons.Rounded.AttachFile, contentDescription = "파일 첨부", modifier = Modifier.size(20.dp))
                }

                // Plain field directly on the glass — no nested grey box.
                // Enter inserts a newline; Alt + Enter (or the send button) sends.
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = ControlSize)
                        .padding(horizontal = 4.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (value.isEmpty()) {
                        Text(
                            "메시지 (Alt+Enter 전송)",
                            style = MaterialTheme.typography.bodyLarge,
                            color = scheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onPreviewKeyEvent { ev ->
                                val isSend = ev.type == KeyEventType.KeyDown &&
                                    ev.key == Key.Enter &&
                                    ev.isAltPressed
                                if (isSend && canSend) {
                                    onSend()
                                    true
                                } else {
                                    false
                                }
                            },
                        textStyle = LocalTextStyle.current.merge(
                            MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface)
                        ),
                        cursorBrush = SolidColor(scheme.primary),
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
                    )
                }

                // Send is liquid glass in both states; active just swaps the tint
                // (blue-tinted glass, not a flat painted disc).
                Box(
                    Modifier
                        .size(ControlSize)
                        .clip(CircleShape)
                        .glassMaterial(
                            shape = CircleShape,
                            tint = if (canSend) scheme.primary else scheme.surface,
                            tone = if (canSend) GlassTone.Regular else GlassTone.Thin
                        )
                        .liquidClickable(enabled = canSend) { onSend() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.ArrowUpward,
                        contentDescription = "보내기",
                        tint = if (canSend) scheme.onPrimary else scheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/** Circular liquid-glass icon button used for attachments. */
@Composable
private fun GlassAttachButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(ControlSize)
            .clip(CircleShape)
            .glassMaterial(
                shape = CircleShape,
                tint = scheme.surface,
                tone = GlassTone.Thin
            )
            .liquidClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}
