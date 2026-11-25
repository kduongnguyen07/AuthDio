package com.example.mdownload.player.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.mdownload.core.LibraryTrack
import com.example.mdownload.ui.AppText as Text
import com.example.mdownload.ui.localText
import kotlin.math.roundToInt

/**
 * Full-screen interactive Orbital Song Navigator reproducing Reference 2:
 * - Sweeping left-bowed dark-slate planetary dome & curved illumination line ([OrbitalPath]).
 * - Windowed constellation of circular album covers ([OrbitalItem]) following the curved path.
 * - Selected song title & artist strictly to the LEFT of the focal cover ([SelectedSongLabel]).
 * - Top-right translucent circular `×` dismiss button matching Reference 2.
 * - Accessible vertical drag, tap-to-select, and Previous / Next / Play Selected controls
 *   when opened via the options menu or TalkBack.
 */
@Composable
fun OrbitalNavigator(
    tracks: List<LibraryTrack>,
    shuffleEnabled: Boolean,
    controller: OrbitalGestureController,
    onCommitNavIndex: (Int) -> Unit,
    onDismiss: () -> Unit,
    loadArtwork: suspend (uri: String, albumId: Long, highRes: Boolean) -> Bitmap?,
    modifier: Modifier = Modifier
) {
    val orbitProgress = controller.orbitProgress.value
    if (orbitProgress <= 0.005f || tracks.isEmpty()) return

    val session = controller.session
    val visualFocus = controller.animatedFocusIndex.value
    val selectedIndex = session.selectedIndex.coerceIn(0, tracks.lastIndex)
    val selectedTrack = tracks[selectedIndex]
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (session.isAccessibleMode) {
                    Modifier.pointerInput(controller, tracks.size) {
                        val stepPx = (size.height * 0.165f).coerceAtLeast(120f)
                        detectVerticalDragGestures(
                            onDragEnd = { controller.settleToSelected() },
                            onDragCancel = { controller.settleToSelected() },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                controller.onVerticalDrag(dragAmount, stepPx)
                            }
                        )
                    }
                } else {
                    Modifier
                }
            )
    ) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val baseBezelPx = OrbitalGeometry.focalBezelDiameterPx(wPx, hPx)
        val focalY = OrbitalGeometry.focalCenterY(hPx)
        val focalBezelLeftPx = OrbitalGeometry.focalBezelLeftEdgePx(wPx, hPx)

        // Pure dark backdrop + sweeping planetary dome + curved orbital path (non-interactive)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = orbitProgress }
                .background(OrbitalDesignTokens.Colors.BackgroundVoid)
        )
        OrbitalPath(
            orbitProgressProvider = { controller.orbitProgress.value },
            modifier = Modifier.fillMaxSize()
        )

        // Render ONLY the visible constellation window (±3 neighbors around visual focus)
        val visibleRange = OrbitalGeometry.visibleIndices(tracks.size, visualFocus)
        for (idx in visibleRange) {
            val item = tracks[idx]
            val targetTransform = OrbitalGeometry.computeTransform(
                itemIndex = idx,
                visualFocusIndex = visualFocus,
                viewportWidthPx = wPx,
                viewportHeightPx = hPx
            )

            // Smooth entry morph: center cover glides from screen center into the right-of-center focal slot;
            // neighboring covers expand gently outward from the focal center along the curved path.
            val normalCenterX = wPx * 0.5f
            val normalCenterY = hPx * 0.44f
            val morphedTransform = if (idx == session.originalIndex && orbitProgress < 0.999f) {
                targetTransform.copy(
                    offsetX = lerp(normalCenterX, targetTransform.offsetX, orbitProgress),
                    offsetY = lerp(normalCenterY, targetTransform.offsetY, orbitProgress),
                    scale = lerp(1.18f, targetTransform.scale, orbitProgress),
                    alpha = 1f
                )
            } else {
                targetTransform.copy(
                    offsetX = lerp(normalCenterX, targetTransform.offsetX, orbitProgress),
                    offsetY = lerp(normalCenterY, targetTransform.offsetY, orbitProgress),
                    scale = targetTransform.scale * (0.45f + 0.55f * orbitProgress),
                    alpha = targetTransform.alpha * orbitProgress
                )
            }

            key("${item.uri}#$idx") {
                OrbitalItem(
                    track = item,
                    transform = morphedTransform,
                    baseBezelDiameterPx = baseBezelPx,
                    isFocalSelection = idx == selectedIndex,
                    interactiveTapEnabled = session.isAccessibleMode,
                    onTapSelect = { controller.tapOrbitIndex(idx, onCommitNavIndex) },
                    loadArtwork = loadArtwork
                )
            }
        }

        // CRITICAL: Selected Song Label strictly to the LEFT of the central focal cover,
        // mathematically vertically centered with the focal disc for both 1-line and 2-line titles.
        val leftMarginPx = with(density) { 20.dp.toPx() }
        val safeGapPx = with(density) { OrbitalDesignTokens.Spacing.LabelSafeGap.toPx() }
        val availableLabelWidthPx = (focalBezelLeftPx - leftMarginPx - safeGapPx).coerceAtLeast(
            with(density) { 96.dp.toPx() }
        )
        val maxLabelWidthDp = with(density) { availableLabelWidthPx.toDp() }

        Box(
            modifier = Modifier
                .zIndex(20f)
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) {
                        val x = leftMarginPx.roundToInt()
                        val y = (focalY - placeable.height * 0.5f).roundToInt()
                        placeable.placeRelative(x, y)
                    }
                }
                .graphicsLayer {
                    alpha = ((orbitProgress - 0.18f) / 0.82f).coerceIn(0f, 1f)
                    translationX = -22f * (1f - orbitProgress)
                }
        ) {
            SelectedSongLabel(
                track = selectedTrack,
                selectedIndex = selectedIndex,
                originalIndex = session.originalIndex.coerceIn(0, tracks.lastIndex),
                queueSize = tracks.size,
                shuffleEnabled = shuffleEnabled,
                maxLabelWidth = maxLabelWidthDp
            )
        }

        // Top-right translucent circular close button matching Reference 2
        // Outer 48dp accessible hit box wraps the exact 40dp visual circle (UI-07).
        val closeLabel = localText("Close")
        Box(
            modifier = Modifier
                .zIndex(25f)
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 8.dp, end = 16.dp)
                .graphicsLayer { alpha = orbitProgress }
                .size(48.dp)
                .clip(CircleShape)
                .semantics {
                    role = Role.Button
                    contentDescription = closeLabel
                }
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(OrbitalDesignTokens.Sizing.CloseButtonDiameter)
                    .clip(CircleShape)
                    .background(OrbitalDesignTokens.Colors.CloseCircleSurface)
                    .border(0.9.dp, OrbitalDesignTokens.Colors.CloseCircleBorder, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = null,
                    tint = OrbitalDesignTokens.Colors.IconPrimary,
                    modifier = Modifier.size(19.dp)
                )
            }
        }

        // Accessible step & confirm bar shown when opened via non-gesture accessibility / menu mode
        if (session.isAccessibleMode) {
            Row(
                modifier = Modifier
                    .zIndex(25f)
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 20.dp, start = 20.dp, end = 20.dp)
                    .graphicsLayer { alpha = orbitProgress }
                    .clip(RoundedCornerShape(28.dp))
                    .background(OrbitalDesignTokens.Colors.CloseCircleSurface)
                    .border(0.9.dp, OrbitalDesignTokens.Colors.CloseCircleBorder, RoundedCornerShape(28.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OrbitalStepButton(
                    icon = Icons.Rounded.KeyboardArrowUp,
                    label = localText("Previous track"),
                    enabled = selectedIndex > 0,
                    onClick = { controller.stepSelection(-1) }
                )

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(OrbitalDesignTokens.Colors.PlayButtonSurface)
                        .semantics { role = Role.Button }
                        .clickable { controller.commitAndExit(onCommitNavIndex) }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = OrbitalDesignTokens.Colors.PlayButtonIcon,
                        modifier = Modifier.size(17.dp)
                    )
                    Text(
                        text = if (selectedIndex == session.originalIndex) "Keep playing" else "Play selected",
                        style = OrbitalDesignTokens.Typography.NextSongMeta,
                        color = OrbitalDesignTokens.Colors.PlayButtonIcon
                    )
                }

                OrbitalStepButton(
                    icon = Icons.Rounded.KeyboardArrowDown,
                    label = localText("Next track"),
                    enabled = selectedIndex < tracks.lastIndex,
                    onClick = { controller.stepSelection(+1) }
                )
            }
        }
    }
}

@Composable
private fun OrbitalStepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .semantics {
                role = Role.Button
                contentDescription = label
            }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) {
                OrbitalDesignTokens.Colors.IconPrimary
            } else {
                OrbitalDesignTokens.Colors.TextMuted.copy(alpha = 0.45f)
            },
            modifier = Modifier.size(22.dp)
        )
    }
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)
