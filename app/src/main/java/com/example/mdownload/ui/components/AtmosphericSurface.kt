package com.example.mdownload.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.mdownload.ui.AppText as Text
import com.example.mdownload.ui.design.HiFiMotion
import com.example.mdownload.ui.design.HiFiShapes
import com.example.mdownload.ui.design.HiFiSpacing
import com.example.mdownload.ui.design.HiFiTheme

/**
 * Full-bleed atmospheric backdrop reproducing the reference's pure obsidian void
 * illuminated from above by a soft orchid-plum radial bloom.
 */
@Composable
fun HiFiAtmosphericCanvas(
    modifier: Modifier = Modifier,
    auraTint: Color? = null,
    auraIntensity: Float = 1.0f,
    content: @Composable BoxScope.() -> Unit
) {
    val tokens = HiFiTheme.colors
    val targetPeak = auraTint ?: tokens.auraPeak
    val animatedPeak by animateColorAsState(
        targetValue = targetPeak,
        animationSpec = HiFiMotion.auraColorSpec,
        label = "auraPeak"
    )
    val baseCanvas = tokens.canvas
    val midStop = if (tokens.isDark) {
        animatedPeak.copy(alpha = (0.48f * auraIntensity).coerceIn(0f, 0.72f))
    } else {
        animatedPeak.copy(alpha = (0.58f * auraIntensity).coerceIn(0f, 0.75f))
    }
    val topPeak = if (tokens.isDark) {
        animatedPeak.copy(alpha = (0.82f * auraIntensity).coerceIn(0f, 0.92f))
    } else {
        animatedPeak.copy(alpha = (0.85f * auraIntensity).coerceIn(0f, 0.92f))
    }
    val lowWash = if (tokens.isDark) {
        animatedPeak.copy(alpha = (0.14f * auraIntensity).coerceIn(0f, 0.25f))
    } else {
        animatedPeak.copy(alpha = (0.18f * auraIntensity).coerceIn(0f, 0.28f))
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(baseCanvas)
            .drawBehind {
                // Top-center / slightly upper-right radial bloom matching the Dribbble reference
                val radius = size.width * 1.18f
                val center = Offset(x = size.width * 0.56f, y = -size.height * 0.04f)
                drawRect(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to topPeak,
                            0.38f to midStop,
                            0.70f to lowWash,
                            1.0f to Color.Transparent
                        ),
                        center = center,
                        radius = radius
                    )
                )
                // Gentle vertical veil so the bottom 45% settles cleanly into pure obsidian
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.46f to Color.Transparent,
                            0.72f to baseCanvas.copy(alpha = 0.75f),
                            1.0f to baseCanvas
                        )
                    )
                )
            },
        content = content
    )
}

/**
 * Translucent smoked-glass container with the signature top-lit specular hairline rim
 * seen on the reference's floating bottom dock and mini-player.
 */
@Composable
fun SmokedGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = HiFiShapes.card,
    containerColor: Color = HiFiTheme.colors.glassSurface,
    rimTopColor: Color = HiFiTheme.colors.glassRimTop,
    rimBottomColor: Color = HiFiTheme.colors.glassRimBottom,
    borderWidth: Dp = 1.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val borderBrush = Brush.verticalGradient(
        colors = listOf(rimTopColor, rimBottomColor)
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(containerColor)
            .border(width = borderWidth, brush = borderBrush, shape = shape),
        content = content
    )
}

/**
 * Smoked-glass padded card for compact sections (URL composer, queue controls, dialogs).
 */
@Composable
fun HiFiGlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = HiFiShapes.card,
    containerColor: Color = HiFiTheme.colors.glassSurface,
    contentPadding: Dp = HiFiSpacing.md,
    content: @Composable ColumnScope.() -> Unit
) {
    SmokedGlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        containerColor = containerColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            content = content
        )
    }
}

/**
 * Minimal atmospheric top header with the subtle centered top handle pill from the reference,
 * optional leading action, editorial screen title, and trailing actions.
 */
@Composable
fun HiFiTopChrome(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    showHandlePill: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    val tokens = HiFiTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(
                start = HiFiSpacing.lg,
                end = HiFiSpacing.lg,
                top = HiFiSpacing.xs,
                bottom = HiFiSpacing.sm
            )
    ) {
        if (showHandlePill) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = HiFiSpacing.xs),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(tokens.inkPrimary.copy(alpha = 0.28f))
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(HiFiSpacing.xs))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = tokens.inkPrimary
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.inkSecondary
                    )
                }
            }
            if (trailing != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(HiFiSpacing.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                    content = trailing
                )
            }
        }
    }
}
