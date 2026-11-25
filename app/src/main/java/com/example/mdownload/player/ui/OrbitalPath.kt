package com.example.mdownload.player.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp

/**
 * Decorative sweeping planetary dome and curved orbital navigation line derived from Reference 2.
 *
 * Strictly visual — never registers pointer input or intercepts touch events.
 * Uses `drawWithCache` so arc/dome [Path] geometry and shader [Brush]es are allocated once per
 * viewport size, while per-frame [orbitProgress] transitions only translate & scale alpha in draw phase.
 */
@Composable
fun OrbitalPath(
    orbitProgress: Float,
    modifier: Modifier = Modifier
) {
    if (orbitProgress <= 0.005f) return
    OrbitalPath(
        orbitProgressProvider = { orbitProgress },
        modifier = modifier
    )
}

@Composable
fun OrbitalPath(
    orbitProgressProvider: () -> Float,
    modifier: Modifier = Modifier
) {
    Spacer(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val w = size.width
                val h = size.height
                if (w <= 0f || h <= 0f) {
                    return@drawWithCache onDrawBehind {}
                }

                val points = OrbitalGeometry.sampleArcPoints(w, h, steps = 40)
                if (points.size < 4) {
                    return@drawWithCache onDrawBehind {}
                }

                val arcPath = Path().apply {
                    val first = points.first()
                    moveTo(first.first, first.second)
                    for (i in 1 until points.size - 1) {
                        val curr = points[i]
                        val next = points[i + 1]
                        val midX = (curr.first + next.first) * 0.5f
                        val midY = (curr.second + next.second) * 0.5f
                        quadraticTo(curr.first, curr.second, midX, midY)
                    }
                    val last = points.last()
                    lineTo(last.first, last.second)
                }

                val domePath = Path().apply {
                    addPath(arcPath)
                    lineTo(-w * 0.15f, h * 1.15f)
                    lineTo(-w * 0.15f, -h * 0.15f)
                    close()
                }

                val apexX = OrbitalGeometry.focalCenterX(w)
                val centerY = OrbitalGeometry.focalCenterY(h)

                val domeBrush = Brush.radialGradient(
                    0.0f to OrbitalDesignTokens.Colors.OrbitDomeInner.copy(alpha = 0.0f),
                    0.56f to OrbitalDesignTokens.Colors.OrbitDomeInner.copy(alpha = 0.35f),
                    0.82f to OrbitalDesignTokens.Colors.OrbitDomeMid.copy(alpha = 0.72f),
                    0.96f to OrbitalDesignTokens.Colors.OrbitDomeOuter.copy(alpha = 0.92f),
                    1.0f to OrbitalDesignTokens.Colors.OrbitDomeOuter.copy(alpha = 0.55f),
                    center = Offset(apexX - w * 0.72f, centerY),
                    radius = w * 0.78f
                )
                val glowBrush = Brush.verticalGradient(
                    0.00f to Color.Transparent,
                    0.20f to OrbitalDesignTokens.Colors.OrbitArcGlow.copy(alpha = 0.14f),
                    0.50f to OrbitalDesignTokens.Colors.OrbitArcGlow.copy(alpha = 0.26f),
                    0.80f to OrbitalDesignTokens.Colors.OrbitArcGlow.copy(alpha = 0.14f),
                    1.00f to Color.Transparent
                )
                val rimBrush = Brush.verticalGradient(
                    0.00f to Color.Transparent,
                    0.14f to OrbitalDesignTokens.Colors.OrbitArcCore.copy(alpha = 0.28f),
                    0.50f to OrbitalDesignTokens.Colors.OrbitArcCore.copy(alpha = 0.56f),
                    0.86f to OrbitalDesignTokens.Colors.OrbitArcCore.copy(alpha = 0.28f),
                    1.00f to Color.Transparent
                )
                val glowStroke = Stroke(
                    width = 8.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
                val rimStroke = Stroke(
                    width = 1.4.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )

                onDrawBehind {
                    val p = orbitProgressProvider().coerceIn(0f, 1f)
                    if (p <= 0.005f) return@onDrawBehind
                    val slideOffsetX = -w * 0.28f * (1f - p)
                    translate(left = slideOffsetX) {
                        drawPath(path = domePath, brush = domeBrush, alpha = p)
                        drawPath(path = arcPath, brush = glowBrush, alpha = p, style = glowStroke)
                        drawPath(path = arcPath, brush = rimBrush, alpha = p, style = rimStroke)
                    }
                }
            }
    )
}
