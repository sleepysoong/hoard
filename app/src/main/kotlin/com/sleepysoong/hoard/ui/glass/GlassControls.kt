package com.sleepysoong.hoard.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.Capsule

/** Material owns gestures, focus and semantics; the modifier owns only the material. */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(50),
    content: @Composable RowScope.() -> Unit
) = GlassAction(onClick, modifier, enabled, shape, true, content)

@Composable
fun GlassSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(50),
    content: @Composable RowScope.() -> Unit
) = GlassAction(onClick, modifier, enabled, shape, false, content)

@Composable
private fun GlassAction(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    shape: Shape,
    primary: Boolean,
    content: @Composable RowScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val tint = if (primary) scheme.primaryContainer else scheme.surfaceContainerHigh
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).glassMaterial(shape, tint, compact = true, enabled = enabled),
        enabled = enabled,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = if (primary) scheme.onPrimaryContainer else scheme.onSurface,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = scheme.onSurfaceVariant
        ),
        elevation = null,
        content = content
    )
}

@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    IconButton(
        onClick = onClick, enabled = enabled,
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .glassMaterial(RoundedCornerShape(50), compact = true, enabled = enabled),
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
        leadingIcon = if (selected) { { Icon(Icons.Default.Check, contentDescription = null) } } else null,
        modifier = modifier.heightIn(min = 48.dp).glassMaterial(
            shape, if (selected) colors.primaryContainer else colors.surfaceContainer,
            compact = true, enabled = enabled
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
    shape: Shape = RoundedCornerShape(16.dp)
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val scheme = MaterialTheme.colorScheme
    val outlineColor = when {
        isError -> scheme.error
        !enabled -> scheme.outlineVariant.copy(alpha = .35f)
        focused -> scheme.primary
        else -> scheme.outlineVariant.copy(alpha = .65f)
    }
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        modifier = modifier.glassMaterial(
            shape, compact = true, enabled = enabled, outlineColor = outlineColor
        ),
        enabled = enabled, readOnly = readOnly, textStyle = textStyle,
        label = label, placeholder = placeholder, leadingIcon = leadingIcon,
        trailingIcon = trailingIcon, supportingText = supportingText, isError = isError,
        visualTransformation = visualTransformation, keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions, singleLine = singleLine, maxLines = maxLines,
        minLines = minLines, shape = shape, interactionSource = interactions,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent, errorContainerColor = Color.Transparent,
            focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
            disabledBorderColor = Color.Transparent, errorBorderColor = Color.Transparent
        )
    )
}

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
        modifier = modifier.glassMaterial(Capsule(), scheme.surfaceContainerHigh, compact = true, enabled = enabled),
        colors = SwitchDefaults.colors(
            checkedTrackColor = scheme.primaryContainer.copy(alpha = .35f),
            uncheckedTrackColor = scheme.surfaceContainerHighest,
            checkedThumbColor = Color.Transparent, uncheckedThumbColor = Color.Transparent,
            disabledCheckedThumbColor = Color.Transparent, disabledUncheckedThumbColor = Color.Transparent
        ),
        thumbContent = {
            Box(Modifier.size(24.dp).glassMaterial(
                RoundedCornerShape(50), if (checked) scheme.primary else scheme.outline,
                compact = true, enabled = enabled
            ))
        }
    )
}

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
    val interactions = remember { MutableInteractionSource() }
    val scheme = MaterialTheme.colorScheme
    val baseBackdrop = LocalGlassBackdrop.current
    val trackBackdrop = rememberLayerBackdrop()
    val pressed by interactions.collectIsPressedAsState()
    val mode = LocalGlassMode.current
    Slider(
        value = value, onValueChange = onValueChange, modifier = modifier.heightIn(min = 56.dp),
        enabled = enabled, valueRange = valueRange, steps = steps,
        onValueChangeFinished = onValueChangeFinished, interactionSource = interactions,
        thumb = {
            val thumbMaterial = if (baseBackdrop == null || mode == GlassMode.Off) {
                Modifier.background(scheme.primaryContainer, Capsule())
            } else {
                Modifier.drawBackdrop(
                    backdrop = rememberCombinedBackdrop(baseBackdrop, trackBackdrop),
                    shape = { Capsule() },
                    effects = {
                        blur(if (pressed) 2.dp.toPx() else 5.dp.toPx())
                        if (mode == GlassMode.Full) lens(10.dp.toPx(), 16.dp.toPx(), chromaticAberration = true)
                    },
                    highlight = { Highlight.Default.copy(alpha = .9f) },
                    onDrawSurface = { drawRect(scheme.surface.copy(alpha = if (pressed) .30f else .48f)) }
                )
            }
            Box(
                Modifier.size(width = 44.dp, height = 34.dp)
                    .shadow(5.dp, Capsule())
                    .then(thumbMaterial)
                    .border(1.dp, scheme.primary.copy(alpha = .50f), Capsule())
            ) {
                Box(
                    Modifier.align(Alignment.TopCenter)
                        .padding(top = 5.dp).size(width = 20.dp, height = 3.dp)
                        .background(scheme.onSurface.copy(alpha = .55f), Capsule())
                )
            }
        },
        track = { state ->
            SliderDefaults.Track(
                sliderState = state, enabled = enabled,
                modifier = Modifier
                    .heightIn(min = 12.dp)
                    .layerBackdrop(trackBackdrop)
                    .glassMaterial(Capsule(), scheme.surfaceContainerHigh, compact = false, enabled = enabled),
                colors = SliderDefaults.colors(
                    activeTrackColor = scheme.primary,
                    inactiveTrackColor = scheme.surfaceContainerHighest
                )
            )
        }
    )
}
