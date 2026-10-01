package com.sleepysoong.hoard.ui.chat

import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.testTag
import com.sleepysoong.hoard.ui.glass.GlassAnimatedVisibility
import com.sleepysoong.hoard.ui.glass.GlassMotion
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.animateContentSize
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Stop
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
import com.sleepysoong.hoard.data.UiAttachment
import com.sleepysoong.hoard.skills.InstalledSkill
import com.sleepysoong.hoard.engine.AttachmentEncoder
import com.sleepysoong.hoard.ui.glass.GlassSurface
import com.sleepysoong.hoard.ui.glass.GlassTone
import com.sleepysoong.hoard.ui.glass.glassMaterial
import com.sleepysoong.hoard.ui.glass.liquidClickable
import java.util.UUID

private val ControlSize = 48.dp // Material minimum touch target

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
    /** A reply is streaming in this session: the send button becomes a stop button. */
    replying: Boolean = false,
    onStop: () -> Unit = {},
    /** The session's goal: decides which /goal commands the "/" menu offers. */
    goal: com.sleepysoong.hoard.data.Goal? = null,
    modifier: Modifier = Modifier,
    skills: List<InstalledSkill> = emptyList(),
    /** Offline: retain the editable draft, but disable every send entry point. */
    sendEnabled: Boolean = true
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val context = androidx.compose.ui.platform.LocalContext.current
    // Real name / MIME / size from the provider (the worker later reads the bytes).
    fun describe(uri: Uri, fallback: String) = AttachmentEncoder.describe(
        context.contentResolver, uri, "att-" + UUID.randomUUID().toString().take(6), fallback
    )
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) {
            onAttachmentsChange(attachments + uris.mapIndexed { i, uri: Uri -> describe(uri, "사진 ${attachments.size + i + 1}.jpg") })
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            onAttachmentsChange(attachments + uris.map { uri -> describe(uri, uri.lastPathSegment?.substringAfterLast('/') ?: "파일") })
        }
    }
    val canSend = sendEnabled && (value.isNotBlank() || attachments.isNotEmpty())
    val slash = SlashCommands.matching(value, goal, skills)

    GlassSurface(modifier = modifier, shape = RoundedCornerShape(28.dp), tone = GlassTone.Thick) {
        // The bar grows/shrinks on a spring as attachment chips come and go.
        Column(
            Modifier.animateContentSize(GlassMotion.sizeSmooth()).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (attachments.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    attachments.forEach { a ->
                        key(a.id) {
                        val pop = remember { Animatable(0f, visibilityThreshold = GlassMotion.SCALE_THRESHOLD) }
                        LaunchedEffect(Unit) { pop.animateTo(1f, GlassMotion.bouncy()) }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier
                                .graphicsLayer {
                                    scaleX = 0.5f + 0.5f * pop.value
                                    scaleY = 0.5f + 0.5f * pop.value
                                    alpha = pop.value.coerceIn(0f, 1f)
                                }
                                // Thin glass chip, like the rest of the composer chrome.
                                .glassMaterial(RoundedCornerShape(50), scheme.surfaceContainerHighest, tone = GlassTone.Thin, lifted = false)
                                .testTag("attachment-chip")
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
            }

            // "/" menu: the commands that apply right now; tapping fills the field (send runs it).
            GlassAnimatedVisibility(
                visible = slash.isNotEmpty(),
                enter = fadeIn(GlassMotion.fade()) + expandVertically(GlassMotion.sizeSmooth(), expandFrom = Alignment.Bottom),
                exit = fadeOut(GlassMotion.fade()) + shrinkVertically(GlassMotion.sizeSmooth(), shrinkTowards = Alignment.Bottom)
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .glassMaterial(RoundedCornerShape(20.dp), scheme.surface, tone = GlassTone.Regular, lifted = false)
                        .testTag("slash-menu")
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp)
                ) {
                    // Keep the last non-empty list while the menu animates out.
                    var shown by remember { mutableStateOf(slash) }
                    if (slash.isNotEmpty()) shown = slash
                    shown.forEachIndexed { i, cmd ->
                        if (i > 0) {
                            HorizontalDivider(modifier = Modifier.padding(start = 12.dp), thickness = 0.5.dp, color = scheme.onSurface.copy(alpha = 0.08f))
                        }
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .liquidClickable {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onValueChange(cmd.insert)
                                }
                                .padding(horizontal = 12.dp, vertical = 9.dp)
                                .testTag("slash-item"),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(cmd.label, style = MaterialTheme.typography.bodyMedium, color = scheme.primary,
                                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(
                                cmd.description, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
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
                    // Local TextFieldValue so text set from outside (a "/" command, a cleared
                    // draft) puts the cursor at the end instead of where it was.
                    var field by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(value, androidx.compose.ui.text.TextRange(value.length))) }
                    if (field.text != value) field = androidx.compose.ui.text.input.TextFieldValue(value, androidx.compose.ui.text.TextRange(value.length))
                    BasicTextField(
                        value = field,
                        onValueChange = { field = it; if (it.text != value) onValueChange(it.text) },
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
                // Becoming sendable: the button swells past full size and settles.
                val active = canSend || replying
                val sendPop by animateFloatAsState(
                    targetValue = if (active) 1f else 0.9f,
                    animationSpec = if (active) GlassMotion.bouncy() else GlassMotion.exit(),
                    label = "send-pop"
                )
                Box(
                    Modifier
                        .graphicsLayer { scaleX = sendPop; scaleY = sendPop }
                        .size(ControlSize)
                        .clip(CircleShape)
                        .glassMaterial(
                            shape = CircleShape,
                            tint = if (active) scheme.primary else scheme.surface,
                            tone = if (active) GlassTone.Regular else GlassTone.Thin
                        )
                        .liquidClickable(enabled = active) { if (replying) onStop() else onSend() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (replying) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
                        contentDescription = if (replying) "답변 중지" else "보내기",
                        tint = if (active) scheme.onPrimary else scheme.onSurfaceVariant.copy(alpha = 0.5f),
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
