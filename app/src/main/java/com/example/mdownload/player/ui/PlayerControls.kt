package com.example.mdownload.player.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.mdownload.player.playback.RepeatModeOption
import com.example.mdownload.ui.localText

/**
 * Restrained 5-icon playback transport bar reproducing Reference 1:
 * Shuffle · Previous · Large Solid White Circular Play/Pause · Next · Repeat.
 */
@Composable
fun PlayerControls(
    isPlaying: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: RepeatModeOption,
    onToggleShuffle: () -> Unit,
    onSkipPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onCycleRepeat: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playPauseLabel = localText(if (isPlaying) "Pause" else "Play")

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onToggleShuffle,
            modifier = Modifier.size(OrbitalDesignTokens.Sizing.ModeButtonSize)
        ) {
            Icon(
                imageVector = Icons.Rounded.Shuffle,
                contentDescription = localText("Shuffle"),
                tint = if (shuffleEnabled) {
                    OrbitalDesignTokens.Colors.IconAccent
                } else {
                    OrbitalDesignTokens.Colors.IconInactive
                },
                modifier = Modifier.size(21.dp)
            )
        }

        IconButton(
            onClick = onSkipPrevious,
            modifier = Modifier.size(OrbitalDesignTokens.Sizing.SkipButtonSize)
        ) {
            Icon(
                imageVector = Icons.Rounded.SkipPrevious,
                contentDescription = localText("Previous track"),
                tint = OrbitalDesignTokens.Colors.IconPrimary,
                modifier = Modifier.size(28.dp)
            )
        }

        // Signature large solid white circular Play/Pause orb from Reference 1
        Box(
            modifier = Modifier
                .size(OrbitalDesignTokens.Sizing.PlayButtonDiameter)
                .shadow(elevation = 10.dp, shape = CircleShape, clip = false)
                .clip(CircleShape)
                .background(OrbitalDesignTokens.Colors.PlayButtonSurface)
                .semantics {
                    role = Role.Button
                    contentDescription = playPauseLabel
                }
                .clickable(onClick = onTogglePlayPause),
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = isPlaying,
                transitionSpec = {
                    fadeIn(tween(OrbitalDesignTokens.Motion.PLAY_ICON_MS)) togetherWith
                        fadeOut(tween(OrbitalDesignTokens.Motion.PLAY_ICON_MS))
                },
                label = "orbitalPlayPauseIcon"
            ) { playing ->
                Icon(
                    imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = OrbitalDesignTokens.Colors.PlayButtonIcon,
                    modifier = Modifier.size(30.dp)
                )
            }
        }

        IconButton(
            onClick = onSkipNext,
            modifier = Modifier.size(OrbitalDesignTokens.Sizing.SkipButtonSize)
        ) {
            Icon(
                imageVector = Icons.Rounded.SkipNext,
                contentDescription = localText("Next track"),
                tint = OrbitalDesignTokens.Colors.IconPrimary,
                modifier = Modifier.size(28.dp)
            )
        }

        IconButton(
            onClick = onCycleRepeat,
            modifier = Modifier.size(OrbitalDesignTokens.Sizing.ModeButtonSize)
        ) {
            Icon(
                imageVector = if (repeatMode == RepeatModeOption.ONE) {
                    Icons.Rounded.RepeatOne
                } else {
                    Icons.Rounded.Repeat
                },
                contentDescription = localText(repeatMode.label),
                tint = if (repeatMode != RepeatModeOption.OFF) {
                    OrbitalDesignTokens.Colors.IconAccent
                } else {
                    OrbitalDesignTokens.Colors.IconInactive
                },
                modifier = Modifier.size(21.dp)
            )
        }
    }
}
