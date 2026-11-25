package com.example.mdownload.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.mdownload.ui.AppText as Text
import com.example.mdownload.ui.design.HiFiShapes
import com.example.mdownload.ui.design.HiFiSpacing
import com.example.mdownload.ui.design.HiFiTheme
import com.example.mdownload.ui.localText

/**
 * Smoked-glass search & URL input capsule that blends natively with the atmospheric canvas.
 */
@Composable
fun HiFiGlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    leadingIcon: ImageVector? = Icons.Rounded.Search,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 4,
    imeAction: ImeAction = ImeAction.Search,
    onImeAction: () -> Unit = {},
    onClear: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val tokens = HiFiTheme.colors
    val resolvedA11yLabel = (contentDescription?.takeIf { it.isNotBlank() } ?: placeholder).ifBlank {
        localText("Text input")
    }
    SmokedGlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = HiFiShapes.input,
        containerColor = tokens.glassElevated
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = tokens.inkSecondary,
                    modifier = Modifier.size(19.dp)
                )
                Spacer(Modifier.width(10.dp))
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.inkSecondary.copy(alpha = 0.78f),
                        maxLines = if (singleLine) 1 else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = tokens.inkPrimary),
                    cursorBrush = SolidColor(tokens.accent),
                    singleLine = singleLine,
                    minLines = minLines,
                    maxLines = maxLines,
                    keyboardOptions = KeyboardOptions(imeAction = imeAction),
                    keyboardActions = KeyboardActions(
                        onSearch = { onImeAction() },
                        onDone = { onImeAction() },
                        onGo = { onImeAction() }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            this.contentDescription = resolvedA11yLabel
                            if (value.isEmpty() && placeholder.isNotBlank() && placeholder != resolvedA11yLabel) {
                                this.stateDescription = placeholder
                            }
                        }
                )
            }

            if (value.isNotEmpty() && onClear != null) {
                IconButton(
                    onClick = onClear,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Clear,
                        contentDescription = localText("Clear"),
                        tint = tokens.inkSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            if (trailing != null) {
                trailing()
            }
        }
    }
}

/**
 * Restrained pill filter chip replacing boxy Material FilterChips.
 */
@Composable
fun HiFiGlassChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null
) {
    val tokens = HiFiTheme.colors
    val bgColor = when {
        selected -> tokens.accent.copy(alpha = if (tokens.isDark) 0.24f else 0.16f)
        else -> tokens.glassSurface
    }
    val borderBrush = Brush.verticalGradient(
        colors = if (selected) {
            listOf(tokens.accentBright.copy(alpha = 0.65f), tokens.accent.copy(alpha = 0.30f))
        } else {
            listOf(tokens.glassRimTop.copy(alpha = 0.65f), tokens.glassRimBottom)
        }
    )
    val contentColor = when {
        !enabled -> tokens.inkTertiary.copy(alpha = 0.5f)
        selected -> if (tokens.isDark) tokens.inkPrimary else tokens.accent
        else -> tokens.inkSecondary
    }

    Row(
        modifier = modifier
            .clip(HiFiShapes.pill)
            .background(bgColor)
            .border(width = 1.dp, brush = borderBrush, shape = HiFiShapes.pill)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(15.dp)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Luminous pill button for primary actions (Download, Play All, Shuffle).
 */
@Composable
fun HiFiPillActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = true,
    enabled: Boolean = true
) {
    val tokens = HiFiTheme.colors
    val bgBrush = when {
        !enabled -> Brush.horizontalGradient(listOf(tokens.glassElevated, tokens.glassElevated))
        primary -> Brush.horizontalGradient(listOf(tokens.accent, tokens.progressArcStart))
        else -> Brush.horizontalGradient(listOf(tokens.glassElevated, tokens.glassSurface))
    }
    val contentColor = when {
        !enabled -> tokens.inkTertiary
        primary -> tokens.onAccent
        else -> tokens.inkPrimary
    }
    val borderColor = if (primary) Color.Transparent else tokens.glassRimTop

    Row(
        modifier = modifier
            .clip(HiFiShapes.pill)
            .background(bgBrush)
            .border(width = if (primary) 0.dp else 1.dp, color = borderColor, shape = HiFiShapes.pill)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = HiFiSpacing.md, vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(7.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
