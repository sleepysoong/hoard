package com.sleepysoong.hoard.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.sleepysoong.hoard.data.MockData
import com.sleepysoong.hoard.data.UiAttachment
import com.sleepysoong.hoard.ui.glass.GlassSurface
import com.sleepysoong.hoard.ui.glass.GlassTextField
import java.util.UUID

/**
 * iMessage-style input: glass bar, solid field, blue round send button.
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
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) {
            onAttachmentsChange(
                attachments + uris.map { uri: Uri ->
                    UiAttachment(
                        id = "att-" + UUID.randomUUID().toString().take(6),
                        name = "사진 ${attachments.size + 1}.jpg",
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
    val haptics = LocalHapticFeedback.current

    GlassSurface(modifier = modifier, shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (attachments.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    attachments.forEach { a ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(scheme.surfaceContainerHighest)
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text("📎 ${a.name}", style = MaterialTheme.typography.labelMedium)
                            IconButton(
                                onClick = { onAttachmentsChange(attachments.filterNot { it.id == a.id }) },
                                modifier = Modifier.size(22.dp)
                            ) {
                                Icon(Icons.Rounded.Close, contentDescription = "제거", modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
            if (showSlash) {
                val query = value.trimStart().removePrefix("/")
                // iOS-style command suggestion list: solid inset, blue commands.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
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
                                        color = scheme.outline.copy(alpha = 0.5f)
                                    )
                                }
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
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
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(
                    onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "사진 첨부", tint = scheme.primary, modifier = Modifier.size(26.dp))
                }
                IconButton(
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Rounded.AttachFile, contentDescription = "파일 첨부", tint = scheme.primary, modifier = Modifier.size(22.dp))
                }
                GlassTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("메시지") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    maxLines = 6,
                    shape = RoundedCornerShape(20.dp)
                )
                IconButton(
                    onClick = {
                        if (canSend) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSend()
                        }
                    },
                    enabled = canSend,
                    modifier = Modifier
                        .size(40.dp)
                        .padding(2.dp)
                        .background(
                            if (canSend) scheme.primary else scheme.surfaceContainerHighest,
                            CircleShape
                        )
                ) {
                    Icon(
                        Icons.Rounded.ArrowUpward,
                        contentDescription = "보내기",
                        tint = if (canSend) Color.White else scheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
