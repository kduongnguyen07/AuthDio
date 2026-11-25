package com.example.mdownload.player.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.mdownload.core.LibraryTrack
import com.example.mdownload.ui.AppText as Text
import com.example.mdownload.ui.localText

/**
 * Selected song label anchored strictly to the LEFT of the central focal album cover
 * (matching Reference 2's focal label region), vertically aligned with the selected disc.
 *
 * Strictly width-bounded to [maxLabelWidth] so long titles gracefully truncate without ever
 * colliding with the circular artwork or the left edge of the screen.
 */
@Composable
fun SelectedSongLabel(
    track: LibraryTrack,
    selectedIndex: Int,
    originalIndex: Int,
    queueSize: Int,
    shuffleEnabled: Boolean,
    maxLabelWidth: Dp,
    modifier: Modifier = Modifier
) {
    val queueCaption = if (shuffleEnabled) {
        "${localText("Shuffle").uppercase()} · ${selectedIndex + 1}/$queueSize"
    } else {
        "${localText("Playing queue").uppercase()} · ${selectedIndex + 1}/$queueSize"
    }

    val statusHint = if (selectedIndex == originalIndex) {
        localText("Now playing")
    } else {
        localText("Release to play")
    }

    Column(
        modifier = modifier.widthIn(min = 72.dp, max = maxLabelWidth.coerceAtLeast(88.dp)),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = queueCaption,
            style = OrbitalDesignTokens.Typography.OrbitalCaption,
            color = OrbitalDesignTokens.Colors.IconAccent.copy(alpha = 0.82f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(6.dp))

        AnimatedContent(
            targetState = track,
            transitionSpec = {
                val enter = fadeIn(tween(OrbitalDesignTokens.Motion.LABEL_TRANSITION_MS)) +
                    slideInVertically(tween(OrbitalDesignTokens.Motion.LABEL_TRANSITION_MS)) { it / 4 }
                val exit = fadeOut(tween(OrbitalDesignTokens.Motion.LABEL_TRANSITION_MS / 2)) +
                    slideOutVertically(tween(OrbitalDesignTokens.Motion.LABEL_TRANSITION_MS / 2)) { -it / 4 }
                enter togetherWith exit
            },
            contentKey = { "${it.uri}#$selectedIndex" },
            label = "orbitalSelectedSongLabel"
        ) { active ->
            Column(horizontalAlignment = Alignment.Start) {
                Text(
                    text = active.title,
                    style = OrbitalDesignTokens.Typography.OrbitalSelectedTitle,
                    color = OrbitalDesignTokens.Colors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (active.artist.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = active.artist,
                        style = OrbitalDesignTokens.Typography.OrbitalSelectedArtist,
                        color = OrbitalDesignTokens.Colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = statusHint,
            style = OrbitalDesignTokens.Typography.OrbitalCaption,
            color = OrbitalDesignTokens.Colors.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
