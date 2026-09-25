package com.sleepysoong.hoard.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.sleepysoong.hoard.data.MockData
import com.sleepysoong.hoard.data.UiAttachment
import com.sleepysoong.hoard.ui.glass.GlassIconButton
import com.sleepysoong.hoard.ui.glass.GlassSurface
import com.sleepysoong.hoard.ui.glass.GlassTextField
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    attachments: List<UiAttachment>,
    onAttachmentsChange: (List<UiAttachment>) -> Unit,
    modelName: String,
    onModelClick: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) {
            onAttachmentsChange(
                attachments + uris.map { uri: Uri ->
                    UiAttachment(
                        id = "att-" + UUID.randomUUID().toString().take(6),
                        name = "photo-${uris.indexOf(uri) + 1}.jpg",
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
                        name = uri.lastPathSegment?.substringAfterLast('/') ?: "file",
                        mime = "*/*",
                        sizeBytes = 0,
                        uri = uri
                    )
                }
            )
        }
    }
    val showSlash = value.trimStart().startsWith("/") && !value.contains(" ")

    GlassSurface(modifier = modifier, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (attachments.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    attachments.forEach { a ->
                        FilterChip(
                            selected = false,
                            onClick = {},
                            label = { Text("📎 ${a.name}") },
                            trailingIcon = {
                                androidx.compose.material3.IconButton(
                                    onClick = { onAttachmentsChange(attachments.filterNot { it.id == a.id }) }
                                ) {
                                    androidx.compose.material3.Icon(Icons.Default.Close, contentDescription = "제거")
                                }
                            }
                        )
                    }
                }
            }
            if (showSlash) {
                val query = value.trimStart().removePrefix("/")
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MockData.slashCommands.filter { it.command.removePrefix("/").startsWith(query) }.take(5)
                        .forEach { cmd ->
                            FilterChip(
                                selected = false,
                                onClick = { onValueChange(cmd.command + " ") },
                                label = { Text("${cmd.command} — ${cmd.description}") }
                            )
                        }
                }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassIconButton(onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    androidx.compose.material3.Icon(Icons.Default.Image, contentDescription = "사진 첨부")
                }
                GlassIconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                    androidx.compose.material3.Icon(Icons.Default.AttachFile, contentDescription = "파일 첨부")
                }
                GlassTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Hoard에게 메시지… (/ 입력 시 명령어)") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    maxLines = 6
                )
                GlassIconButton(onClick = onSend, enabled = value.isNotBlank() || attachments.isNotEmpty()) {
                    androidx.compose.material3.Icon(
                        Icons.Default.Send,
                        contentDescription = "보내기",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = true, onClick = onModelClick, label = { Text(modelName) })
                Text(
                    "앱을 나가도 백그라운드에서 답변 계속",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
