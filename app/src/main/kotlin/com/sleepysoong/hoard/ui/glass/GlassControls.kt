package com.sleepysoong.hoard.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.sleepysoong.hoard.ui.theme.IOSGreen

/**
 * Primary action button — iOS-style filled glass pill (primary container tint).
 * 50dp default height, liquid press. All call sites should use this instead
 * of building their own button; it keeps the visual grammar consistent.
 */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    elevated: Boolean = true,
    destructive: Boolean = false,
    shape: Shape = RoundedCornerShape(16.dp),
    content: @Composable RowScope.() -> Unit
) = GlassAction(onClick, modifier, enabled, shape, if (destructive) 2 else 1, elevated, content)

/**
 * Secondary (neutral) action — same form as [GlassButton], calm surface tint.
 */
@Composable
fun GlassSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    elevated: Boolean = false,
    shape: Shape = RoundedCornerShape(16.dp),
    content: @Composable RowScope.() -> Unit
) = GlassAction(onClick, modifier, enabled, shape, 0, elevated, content)

/**
 * Pill-shaped action used by anchored menus / modal dialogs. Rounded to 50%
 * (full capsule), 54dp tall. Used for the "취소" / "저장" pair pattern.
 */
@Composable
fun GlassCapsuleButton(
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    primary: Boolean = false,
    enabled: Boolean = true
) {
    GlassAction(
        onClick = onClick,
        modifier = modifier.heightIn(min = GlassTokens.modalActionHeight),
        enabled = enabled,
        shape = RoundedCornerShape(50),
        level = if (primary) 1 else if (destructive) 2 else 0,
        elevated = false
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1
        )
    }
}

@Composable
private fun GlassAction(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    shape: Shape,
    level: Int, // 0=secondary, 1=primary, 2=destructive
    elevated: Boolean,
    content: @Composable RowScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val tint = when (level) {
        0 -> scheme.surfaceContainerHigh
        1 -> scheme.primaryContainer
        else -> scheme.error
    }
    val contentColor = when (level) {
        0 -> scheme.onSurface
        1 -> scheme.onPrimaryContainer
        else -> scheme.onErrorContainer
    }
    Button(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 50.dp)
            .glassMaterial(shape, tint, tone = GlassTone.Thin, enabled = enabled),
        enabled = enabled,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = contentColor,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = scheme.onSurfaceVariant
        ),
        elevation = null,
        content = content
    )
}

/**
 * Icon-only glass button — 44dp minimum touch, circular. Used in top bars
 * and cards for the "back / add / gear" pattern.
 */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    IconButton(
        onClick = onClick, enabled = enabled,
        modifier = modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp)
            .glassMaterial(RoundedCornerShape(50), tone = GlassTone.Thin, enabled = enabled),
        content = content
    )
}

@Composable
fun GlassFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(50)
) {
    val colors = MaterialTheme.colorScheme
    FilterChip(
        selected = selected, onClick = onClick, label = label, enabled = enabled,
        leadingIcon = if (selected) { { Icon(Icons.Rounded.Check, contentDescription = null) } } else null,
        modifier = modifier.heightIn(min = 44.dp).glassMaterial(
            shape, if (selected) colors.primaryContainer else colors.surfaceContainer,
            tone = GlassTone.Thin, enabled = enabled
        ),
        shape = shape,
        border = null,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent, selectedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent, disabledSelectedContainerColor = Color.Transparent,
            selectedLabelColor = colors.onPrimaryContainer
        )
    )
}

/**
 * iOS-style field: solid system-gray fill, 12dp corners, no outline and no
 * underline. The label floats *inside* the fill (a filled TextField) — an
 * outlined field would cut a notch into the fill for the label.
 * Fills the available width by default so stacked fields line up.
 */
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    shape: Shape = RoundedCornerShape(12.dp)
) {
    val scheme = MaterialTheme.colorScheme
    val fill = scheme.surfaceContainerHighest
    TextField(
        value = value, onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled, readOnly = readOnly, textStyle = textStyle,
        label = label, placeholder = placeholder, leadingIcon = leadingIcon,
        trailingIcon = trailingIcon, supportingText = supportingText, isError = isError,
        visualTransformation = visualTransformation, keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions, singleLine = singleLine, maxLines = maxLines,
        minLines = minLines, shape = shape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = fill,
            unfocusedContainerColor = fill,
            disabledContainerColor = fill,
            errorContainerColor = fill,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
            focusedLabelColor = scheme.primary,
            unfocusedLabelColor = scheme.onSurfaceVariant
        )
    )
}

/**
 * iOS switch: solid green/gray pill, white thumb. No glass — matches iOS,
 * where switches are opaque even on translucent surfaces.
 */
@Composable
fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val scheme = MaterialTheme.colorScheme
    Switch(
        checked = checked, onCheckedChange = onCheckedChange, enabled = enabled,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedTrackColor = IOSGreen,
            uncheckedTrackColor = scheme.surfaceContainerHighest,
            checkedThumbColor = Color.White,
            uncheckedThumbColor = Color.White,
            checkedBorderColor = Color.Transparent,
            uncheckedBorderColor = Color.Transparent,
            disabledCheckedTrackColor = IOSGreen.copy(alpha = 0.4f),
            disabledUncheckedTrackColor = scheme.surfaceContainerHighest,
            disabledCheckedThumbColor = Color.White,
            disabledUncheckedThumbColor = Color.White,
            disabledCheckedBorderColor = Color.Transparent,
            disabledUncheckedBorderColor = Color.Transparent
        )
    )
}

/**
 * iOS slider: 4dp track, white round thumb with shadow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    Slider(
        value = value, onValueChange = onValueChange, modifier = modifier.heightIn(min = 44.dp),
        enabled = enabled, valueRange = valueRange, steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        thumb = {
            Box(
                Modifier
                    .size(28.dp)
                    .shadow(3.dp, CircleShape, clip = false)
                    .background(Color.White, CircleShape)
            )
        },
        track = { state ->
            SliderDefaults.Track(
                sliderState = state, enabled = enabled,
                modifier = Modifier.height(4.dp),
                colors = SliderDefaults.colors(
                    activeTrackColor = scheme.primary,
                    inactiveTrackColor = scheme.surfaceContainerHighest
                )
            )
        }
    )
}
