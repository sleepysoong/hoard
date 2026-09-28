package com.sleepysoong.hoard.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sleepysoong.hoard.data.todo.TodoItem
import com.sleepysoong.hoard.data.todo.TodoStatus
import com.sleepysoong.hoard.ui.glass.GlassSurface
import com.sleepysoong.hoard.ui.glass.GlassTone
import com.sleepysoong.hoard.ui.glass.liquidClickable

/** A live projection of committed session state; never inferred from tool-call history. */
@Composable
fun TodoPanel(sessionId: String, todos: List<TodoItem>) {
    if (todos.isEmpty()) return
    var expanded by rememberSaveable(sessionId) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val active = todos.firstOrNull { it.status == TodoStatus.InProgress }
    val completed = todos.count { it.status == TodoStatus.Completed }
    val cancelled = todos.count { it.status == TodoStatus.Cancelled }
    GlassSurface(Modifier.fillMaxWidth().testTag("todo-panel"), tone = GlassTone.Thin, lifted = false) {
        Column {
            Row(
                Modifier.fillMaxWidth().testTag("todo-toggle").liquidClickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text("할 일 · 완료 $completed/${todos.size}" + if (cancelled > 0) " · 취소 $cancelled" else "",
                        style = MaterialTheme.typography.labelLarge, color = scheme.primary)
                    if (!expanded && active != null) Text(active.content, style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, color = scheme.onSurfaceVariant)
                }
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "할 일 접기" else "할 일 펼치기", tint = scheme.primary)
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState())
                    .padding(start = 14.dp, end = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    todos.forEach { todo ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(todo.status.label, style = MaterialTheme.typography.labelMedium,
                                color = if (todo.status == TodoStatus.InProgress) scheme.primary else scheme.onSurfaceVariant,
                                modifier = Modifier.width(48.dp))
                            Text(todo.content, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                                color = if (todo.status == TodoStatus.Cancelled) scheme.onSurfaceVariant else scheme.onSurface)
                        }
                    }
                }
            }
        }
    }
}
