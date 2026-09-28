package com.sleepysoong.hoard.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sleepysoong.hoard.data.ChatMessage
import com.sleepysoong.hoard.data.Goal
import com.sleepysoong.hoard.data.GoalStatus
import com.sleepysoong.hoard.ui.glass.GlassMotion
import com.sleepysoong.hoard.ui.glass.GlassPillButton
import com.sleepysoong.hoard.ui.glass.GlassPillTint
import com.sleepysoong.hoard.ui.glass.GlassPopup
import com.sleepysoong.hoard.ui.glass.GlassTone
import com.sleepysoong.hoard.ui.glass.glassMaterial
import com.sleepysoong.hoard.ui.glass.liquidClickable

/** One-line goal status under the top bar; tap for details and the user's controls. */
@Composable
fun GoalBar(goal: Goal?, notice: String?, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AnimatedVisibility(goal != null, enter = expandVertically(GlassMotion.sizeSmooth()) + fadeIn(GlassMotion.fade()), exit = shrinkVertically(GlassMotion.sizeSmooth()) + fadeOut(GlassMotion.fade())) {
            if (goal != null) {
                val (label, color) = statusLabel(goal)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .liquidClickable(pressedScale = 0.97f, onClick = onOpen)
                        .heightIn(min = 40.dp)
                        .glassMaterial(RoundedCornerShape(50), color.copy(alpha = 0.18f), tone = GlassTone.Thin, lifted = false)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .testTag("goal-bar"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(Modifier.size(8.dp).background(color, CircleShape))
                    Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
                    Text(
                        goal.objective,
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        notice?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = scheme.error, modifier = Modifier.padding(horizontal = 14.dp).testTag("goal-notice"))
        }
    }
}

@Composable
private fun statusLabel(g: Goal): Pair<String, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (g.status) {
        GoalStatus.Active -> (if (g.continuationSuppressed) "목표 대기 · ${g.usedTurns}/${g.maxTurns}턴" else "목표 진행 중 · ${g.usedTurns}/${g.maxTurns}턴") to scheme.primary
        GoalStatus.Paused -> "목표 일시정지" to scheme.onSurfaceVariant
        GoalStatus.Blocked -> "목표 막힘" to scheme.error
        GoalStatus.Completed -> "목표 달성" to scheme.tertiary
        GoalStatus.BudgetLimited -> "예산 소진" to scheme.error
        GoalStatus.Cleared -> "목표 해제" to scheme.onSurfaceVariant
    }
}

/** Goal details + the user's lifecycle controls (the model can't pause/resume/clear). */
@Composable
fun GoalSheet(goal: Goal?, onPause: () -> Unit, onResume: () -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    GlassPopup(
        onDismiss = onDismiss,
        title = "목표",
        message = goal?.let { statusLabel(it).first } ?: "목표가 없습니다 · /goal <목표> 로 설정"
    ) {
        if (goal == null) return@GlassPopup
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            WorkCard("목표", goal.objective, scheme.onSurface, collapsedBodyLines = 6)
            goal.verification?.let { WorkCard("검증", it, scheme.onSurface) }
            goal.constraints?.let { WorkCard("제약", it, scheme.onSurface) }
            goal.blockedReason?.let { WorkCard("막힌 이유", it, scheme.onSurface, titleColor = scheme.error) }
            goal.evidence?.let { WorkCard("근거", it, scheme.onSurface, collapsedBodyLines = 6) }
            WorkCard(
                "사용량",
                "자동 턴 ${goal.usedTurns}/${goal.maxTurns} · 토큰 ${goal.usedTokens}/${goal.maxTokens} · 최대 ${goal.maxDurationMs / 60_000}분" +
                    (if (goal.continuationSuppressed) "\n진전 없는 자동 턴이라 자동 진행을 멈춤 · 메시지를 보내면 재개" else ""),
                scheme.onSurface
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                when (goal.status) {
                    GoalStatus.Active -> GlassPillButton("일시정지", onClick = { onPause(); onDismiss() }, modifier = Modifier.testTag("goal-pause"))
                    GoalStatus.Paused, GoalStatus.Blocked, GoalStatus.BudgetLimited ->
                        GlassPillButton("재개", onClick = { onResume(); onDismiss() }, tint = GlassPillTint.Accent, modifier = Modifier.testTag("goal-resume"))
                    else -> Unit
                }
                GlassPillButton("지우기", onClick = { onClear(); onDismiss() }, tint = GlassPillTint.Destructive, modifier = Modifier.testTag("goal-clear"))
            }
        }
    }
}

/** A turn the runtime started (goal set, wakeup, scheduled run): a compact notice, not a bubble. */
@Composable
fun TriggerNotice(message: ChatMessage, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val label = when (message.trigger) {
        "goal" -> "목표 설정"
        "wakeup" -> "깨우기"
        "schedule" -> "예약 실행"
        else -> "자동"
    }
    Box(modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .glassMaterial(RoundedCornerShape(50), scheme.primary.copy(alpha = 0.10f), tone = GlassTone.Thin, lifted = false)
                .padding(horizontal = 14.dp, vertical = 7.dp)
                .testTag("trigger-notice"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = scheme.primary)
            Text(message.text, style = MaterialTheme.typography.labelMedium, color = scheme.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}
