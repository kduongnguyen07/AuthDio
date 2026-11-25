package com.example.mdownload.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.mdownload.core.LibraryTrack
import com.example.mdownload.core.TextUtils
import com.example.mdownload.player.ui.TrackArtworkBox
import com.example.mdownload.ui.AppText as Text
import com.example.mdownload.ui.design.HiFiSpacing
import com.example.mdownload.ui.design.HiFiTheme
import com.example.mdownload.ui.localText

/**
 * Borderless, cardless track row directly reproducing the Dribbble reference's right screen:
 * - 52dp rounded-square (14dp) artwork on the left
 * - Crisp white title + cool muted lavender-gray artist
 * - Tabular right-aligned duration ("2:56") + restrained overflow trigger
 * - Ultra-thin hairline divider separating tracks on the atmospheric canvas
 */
@Composable
fun HiFiTrackRow(
    track: LibraryTrack,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    loadArtwork: suspend (uri: String, albumId: Long, highRes: Boolean) -> Bitmap?,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
    subtitleOverride: String? = null,
    trailingMenu: (@Composable () -> Unit)? = null
) {
    val tokens = HiFiTheme.colors
    val rowBg = if (isCurrent) {
        tokens.accentSoft.copy(alpha = if (tokens.isDark) 0.22f else 0.14f)
    } else {
        Color.Transparent
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(rowBg)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = HiFiSpacing.lg,
                    end = if (trailingMenu != null) HiFiSpacing.xs else HiFiSpacing.lg,
                    top = 11.dp,
                    bottom = 11.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TrackArtworkBox(
                uri = track.uri,
                albumId = track.albumId,
                highRes = false,
                isPlayingIndicator = isPlaying,
                cornerRadius = 14.dp,
                iconSize = 22.dp,
                modifier = Modifier.size(52.dp),
                loadArtwork = loadArtwork
            )

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isCurrent) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = null,
                            tint = tokens.accent,
                            modifier = Modifier
                                .size(15.dp)
                                .padding(end = 4.dp)
                        )
                    }
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isCurrent) tokens.accentBright else tokens.inkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitleOverride ?: track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(HiFiSpacing.sm))

            Text(
                text = TextUtils.formatDurationMs(track.durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = if (isCurrent) tokens.inkPrimary else tokens.inkSecondary
            )

            if (trailingMenu != null) {
                Box(modifier = Modifier.padding(start = 2.dp)) {
                    trailingMenu()
                }
            }
        }

        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = HiFiSpacing.lg + 52.dp + 14.dp, end = HiFiSpacing.lg),
                thickness = 0.65.dp,
                color = tokens.divider
            )
        }
    }
}

@Composable
fun HiFiTrackOverflowButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = HiFiTheme.colors
    IconButton(
        onClick = onClick,
        modifier = modifier.size(36.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.MoreVert,
            contentDescription = localText("More options"),
            tint = tokens.inkSecondary.copy(alpha = 0.78f),
            modifier = Modifier.size(18.dp)
        )
    }
}
