package com.example.mdownload.player.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.example.mdownload.player.lyrics.LyricLine

/**
 * Single animated lyric line matching HiFiDeck's dark cinematic Orbital aesthetic.
 *
 * - Active line illuminates in crisp white with gentle scale emphasis and soft selection wash.
 * - Surrounding past/upcoming lines dim softly for effortless reading focus.
 * - Synchronized lines are directly tappable to seek Media3 playback.
 */
@Composable
fun LyricsLineRow(
    line: LyricLine,
    isActive: Boolean,
    isPast: Boolean,
    isSynced: Boolean,
    onTapLine: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val targetColor = when {
        isActive -> OrbitalDesignTokens.Colors.TextPrimary
        !isSynced -> OrbitalDesignTokens.Colors.TextPrimary.copy(alpha = 0.86f)
        isPast -> OrbitalDesignTokens.Colors.TextSecondary.copy(alpha = 0.40f)
        else -> OrbitalDesignTokens.Colors.TextSecondary.copy(alpha = 0.68f)
    }
    val textColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 220),
        label = "lyricLineColor"
    )
    val scale by animateFloatAsState(
        targetValue = if (isActive) 1.035f else 1.0f,
        animationSpec = tween(durationMillis = 220),
        label = "lyricLineScale"
    )
    val bgColor by animateColorAsState(
        targetValue = if (isActive) {
            OrbitalDesignTokens.Colors.ProgressArcGlow.copy(alpha = 0.11f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(durationMillis = 220),
        label = "lyricLineBg"
    )

    if (line.isInstrumentalBreak) {
        if (isActive) {
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(3) { idx ->
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(
                                OrbitalDesignTokens.Colors.ProgressArcEnd.copy(
                                    alpha = 0.55f + idx * 0.15f
                                )
                            )
                    )
                }
            }
        }
        return
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .then(
                if (onTapLine != null && isSynced) {
                    Modifier
                        .semantics {
                            role = Role.Button
                            selected = isActive
                        }
                        .clickable(onClick = onTapLine)
                } else {
                    Modifier.semantics { selected = isActive }
                }
            )
            .padding(horizontal = 12.dp, vertical = if (isSynced) 10.dp else 7.dp)
    ) {
        Text(
            text = line.text,
            color = textColor,
            fontFamily = FontFamily.SansSerif,
            fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
            fontSize = if (isSynced) 20.sp else 17.sp,
            lineHeight = if (isSynced) 29.sp else 26.sp,
            letterSpacing = if (isActive) (-0.2).sp else 0.sp,
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
        )
    }
}
