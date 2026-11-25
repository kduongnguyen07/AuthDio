package com.example.mdownload.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.mdownload.core.JobStatus
import com.example.mdownload.ui.AppText as Text
import com.example.mdownload.ui.design.HiFiShapes
import com.example.mdownload.ui.design.HiFiSpacing
import com.example.mdownload.ui.design.HiFiTheme

/**
 * Smoked-glass surface panel unified with the Obsidian Orchid design system.
 */
@Composable
fun EditorialCard(
    modifier: Modifier = Modifier,
    containerColor: Color = HiFiTheme.colors.glassSurface,
    content: @Composable ColumnScope.() -> Unit
) {
    HiFiGlassPanel(
        modifier = modifier,
        shape = HiFiShapes.card,
        containerColor = containerColor,
        contentPadding = HiFiSpacing.md,
        content = content
    )
}

@Composable
fun StatusPill(
    text: String,
    backgroundColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(HiFiShapes.pill)
            .background(backgroundColor)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun JobStatusPill(status: JobStatus, modifier: Modifier = Modifier) {
    val tokens = HiFiTheme.colors
    val (bg, fg) = when (status) {
        JobStatus.DONE -> tokens.successContainer to tokens.success
        JobStatus.SKIPPED -> tokens.glassElevated to tokens.inkSecondary
        JobStatus.FAILED -> tokens.errorContainer to tokens.error
        JobStatus.CANCELLED, JobStatus.STOPPING -> tokens.warningContainer to tokens.warning
        JobStatus.RUNNING, JobStatus.RETRYING -> tokens.accentSoft to tokens.accentBright
        JobStatus.QUEUED -> tokens.glassElevated to tokens.inkSecondary
    }
    StatusPill(text = status.label, backgroundColor = bg, contentColor = fg, modifier = modifier)
}

@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val tokens = HiFiTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = tokens.inkPrimary
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.inkSecondary
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}
