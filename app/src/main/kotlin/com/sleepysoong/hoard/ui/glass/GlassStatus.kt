package com.sleepysoong.hoard.ui.glass

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

enum class GlassSeverity { Info, Success, Error }

@Composable
fun GlassBadge(
    text: String,
    modifier: Modifier = Modifier,
    severity: GlassSeverity = GlassSeverity.Info
) {
    val scheme = MaterialTheme.colorScheme
    val tint = when (severity) {
        GlassSeverity.Info -> scheme.secondaryContainer
        GlassSeverity.Success -> scheme.primaryContainer
        GlassSeverity.Error -> scheme.errorContainer
    }
    GlassSurface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = tint
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
fun GlassStatus(
    text: String,
    modifier: Modifier = Modifier,
    severity: GlassSeverity = GlassSeverity.Info,
    action: (@Composable () -> Unit)? = null
) {
    val icon: ImageVector = when (severity) {
        GlassSeverity.Info -> Icons.Default.Info
        GlassSeverity.Success -> Icons.Default.CheckCircle
        GlassSeverity.Error -> Icons.Default.Error
    }
    GlassCard(modifier = modifier) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null)
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            action?.invoke()
        }
    }
}

@Composable
fun GlassEmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    GlassCard(modifier = modifier) {
        Column(
            Modifier.padding(24.dp).sizeIn(minHeight = 120.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
        ) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                ProvideTextStyle(MaterialTheme.typography.titleMedium) { Text(title) }
            }
            ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(color = Color.Gray)) { Text(description) }
            action?.invoke()
        }
    }
}
