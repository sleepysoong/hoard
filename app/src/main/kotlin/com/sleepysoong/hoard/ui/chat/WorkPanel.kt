package com.sleepysoong.hoard.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import com.sleepysoong.hoard.ui.glass.GlassTokens
import com.sleepysoong.hoard.ui.theme.LocalHoardDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sleepysoong.hoard.data.RouteAttempt
import com.sleepysoong.hoard.data.RoutingInfo
import com.sleepysoong.hoard.data.StepKind
import com.sleepysoong.hoard.data.ThinkingStep
import com.sleepysoong.hoard.data.formatElapsed
import com.sleepysoong.hoard.ui.glass.GlassMotion
import com.sleepysoong.hoard.ui.glass.GlassTone
import com.sleepysoong.hoard.ui.glass.glassMaterial
import com.sleepysoong.hoard.ui.glass.liquidClickable

/*
 * The reply's "how it got here" UI, all liquid glass:
 *  - 작업 pill → reasoning cards and tool-call cards, in two labelled groups
 *  - # pill (answering model) → the router log, one card per attempt
 * Cards are the session-list glass cards, scaled down for a bubble.
 */

private val Capsule = RoundedCornerShape(50)
private val CardShape = RoundedCornerShape(16.dp)

/** Liquid glass capsule used for both pills. */
@Composable
private fun GlassPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color,
    content: @Composable () -> Unit
) {
    Box(
        modifier
            .liquidClickable(pressedScale = 0.9f, onClick = onClick)
            .height(30.dp)
            .glassMaterial(Capsule, accent.copy(alpha = 0.22f), tone = GlassTone.Thin, lifted = false)
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

@Composable
private fun Chevron(open: Boolean, tint: Color) = Icon(
    if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
    contentDescription = null,
    modifier = Modifier.size(16.dp),
    tint = tint
)

private val panelEnter = expandVertically(GlassMotion.sizeSmooth(), expandFrom = Alignment.Top) +
    fadeIn(GlassMotion.fade()) + scaleIn(GlassMotion.bouncy(), initialScale = 0.96f, transformOrigin = TransformOrigin(0f, 0f))
private val panelExit = shrinkVertically(GlassMotion.sizeSmooth(), shrinkTowards = Alignment.Top) + fadeOut(GlassMotion.fade())

// ---------------------------------------------------------------- 작업

/** "작업 N단계" pill; opens reasoning + tool-use groups of glass cards. */
@Composable
fun WorkPanel(steps: List<ThinkingStep>, textColor: Color) {
    val scheme = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val running = steps.any { it.running }
    Column(Modifier.testTag("work-panel"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassPill(onClick = { open = !open }, accent = scheme.primary, modifier = Modifier.testTag("work-pill")) {
            Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(15.dp), tint = scheme.primary)
            Text(
                if (running) "작업 중 · ${steps.size}단계" else "작업 ${steps.size}단계",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.primary
            )
            Chevron(open, scheme.primary.copy(alpha = 0.8f))
        }
        AnimatedVisibility(open, enter = panelEnter, exit = panelExit) {
            val reasoning = steps.filter { it.kind == StepKind.Reasoning }
            val tools = steps.filter { it.kind == StepKind.Tool }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (reasoning.isNotEmpty()) StepGroup("추론", reasoning, textColor)
                if (tools.isNotEmpty()) StepGroup("도구 사용", tools, textColor)
            }
        }
    }
}

@Composable
private fun StepGroup(title: String, steps: List<ThinkingStep>, textColor: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("work-group")) {
        GroupHeader(title, steps.size)
        steps.forEach { StepCard(it, textColor) }
    }
}

@Composable
private fun GroupHeader(title: String, count: Int) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 4.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = scheme.onSurfaceVariant)
        Text("$count", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant.copy(alpha = 0.7f))
    }
}

/** A session-list style glass card: icon badge, title, detail, duration. Long reasoning expands on tap. */
@Composable
private fun StepCard(step: ThinkingStep, textColor: Color) {
    val scheme = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    val accent = when {
        step.failed -> scheme.error
        step.kind == StepKind.Tool -> scheme.tertiary
        else -> scheme.primary
    }
    val icon = stepIcon(step)
    val long = step.kind == StepKind.Reasoning && step.detail.length > 160
    GlassCardBox(
        modifier = Modifier
            .testTag("work-step")
            .then(if (long) Modifier.liquidClickable(pressedScale = 0.97f, haptic = false) { expanded = !expanded } else Modifier)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Badge(icon, accent)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        step.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (step.failed) scheme.error else textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = true)
                    )
                    Text(
                        if (step.running) "실행 중" else formatElapsed(step.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                if (step.detail.isNotBlank()) {
                    Text(
                        step.detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.68f),
                        maxLines = if (expanded) Int.MAX_VALUE else if (step.kind == StepKind.Reasoning) 4 else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.animateContentSize(GlassMotion.sizeSmooth())
                    )
                }
            }
        }
    }
}

private fun stepIcon(step: ThinkingStep): ImageVector = when {
    step.failed -> Icons.Rounded.ErrorOutline
    step.kind == StepKind.Reasoning -> Icons.Rounded.Psychology
    step.title.startsWith("웹 검색") -> Icons.Rounded.TravelExplore
    step.title.startsWith("페이지 읽기") -> Icons.Rounded.Language
    else -> Icons.Rounded.Build
}

@Composable
private fun Badge(icon: ImageVector, accent: Color) {
    Box(
        Modifier.size(28.dp).background(accent.copy(alpha = 0.16f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun cardFill(): Color {
    val dark = LocalHoardDarkTheme.current
    return if (dark) Color.White.copy(alpha = 0.07f) else Color.White.copy(alpha = 0.72f)
}

@Composable
private fun GlassCardBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            // Nested inside the glass bubble (and an animated layer): a backdrop
            // shader here draws nothing, so the card is a frosted inset instead —
            // translucent surface + hairline rim, like the session cards scaled down.
            .clip(CardShape)
            .background(cardFill(), CardShape)
            .border(GlassTokens.hairline, MaterialTheme.colorScheme.onSurface.copy(alpha = if (LocalHoardDarkTheme.current) 0.16f else 0.10f), CardShape)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) { content() }
}

// ---------------------------------------------------------------- # (router log)

/** "# model" pill; opens the router log (every candidate tried, why it failed). */
@Composable
fun RoutingPanel(routing: RoutingInfo, textColor: Color) {
    val scheme = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val failures = routing.failures.size
    val selected = routing.selectedModel
    val accent = if (selected == null) scheme.error else scheme.primary
    val name = selected?.substringAfterLast('/') ?: "응답 없음"
    val description = buildString {
        append(selected?.let { "응답 모델 $it" } ?: "응답한 모델 없음")
        if (failures > 0) append(" · ${failures}개 실패")
        if (routing.requestedModel.isNotBlank()) append(" · 요청 ${routing.requestedModel}")
    }
    Column(Modifier.testTag("routing-panel"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassPill(
            onClick = { open = !open },
            accent = accent,
            modifier = Modifier.testTag("routing-summary").semantics { contentDescription = description }
        ) {
            Text("#", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = accent)
            Text(name, style = MaterialTheme.typography.labelLarge, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (failures > 0) {
                Box(
                    Modifier.background(scheme.error.copy(alpha = 0.16f), Capsule).padding(horizontal = 6.dp, vertical = 1.dp)
                ) { Text("실패 $failures", style = MaterialTheme.typography.labelSmall, color = scheme.error) }
            }
            Chevron(open, accent.copy(alpha = 0.8f))
        }
        AnimatedVisibility(open, enter = panelEnter, exit = panelExit) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GroupHeader("라우팅 로그" + (routing.requestedModel.takeIf { it.isNotBlank() }?.let { " · 요청 $it" } ?: ""), routing.attempts.size)
                routing.attempts.forEach { AttemptCard(it, textColor) }
            }
        }
    }
}

@Composable
private fun AttemptCard(a: RouteAttempt, textColor: Color) {
    val scheme = MaterialTheme.colorScheme
    val (label, color) = when (a.outcome) {
        "succeeded" -> "성공" to scheme.primary
        "streaming" -> "응답 중" to scheme.primary
        "failed" -> "실패" to scheme.error
        "skipped" -> "건너뜀" to scheme.onSurfaceVariant
        "incomplete" -> "잘림" to scheme.error
        else -> a.outcome to scheme.onSurfaceVariant
    }
    GlassCardBox(Modifier.testTag("routing-attempt")) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(28.dp).background(color.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                Text("${a.index}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = color)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.model, style = MaterialTheme.typography.labelLarge, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = true))
                    Box(Modifier.padding(start = 8.dp).background(color.copy(alpha = 0.14f), Capsule).padding(horizontal = 7.dp, vertical = 1.dp)) {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
                    }
                }
                val detail = listOfNotNull(
                    a.statusCode?.let { "HTTP $it" },
                    // Skipped candidates were never sent: their class is meaningless ("unknown").
                    a.errorClass?.takeIf { a.outcome == "failed" || a.outcome == "incomplete" },
                    a.durationMs.takeIf { it > 0 }?.let { formatElapsed(it) }
                ).joinToString(" · ")
                if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.labelSmall, color = textColor.copy(alpha = 0.68f))
                a.reason?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = textColor.copy(alpha = 0.68f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
