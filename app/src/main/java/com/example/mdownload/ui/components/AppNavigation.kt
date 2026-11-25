package com.example.mdownload.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.mdownload.AppTab
import com.example.mdownload.player.playback.PlaybackUiState
import com.example.mdownload.player.ui.MiniPlayer
import com.example.mdownload.player.ui.TrackArtworkBox
import com.example.mdownload.ui.AppText as Text
import com.example.mdownload.ui.design.HiFiShapes
import com.example.mdownload.ui.design.HiFiSpacing
import com.example.mdownload.ui.design.HiFiTheme
import com.example.mdownload.ui.localText
import kotlinx.coroutines.flow.StateFlow

/**
 * Signature floating double-deck smoked-glass dock derived directly from the Dribbble reference:
 * - Upper deck (when a playback session exists): Floating MiniPlayer with left-to-right progress wash
 * - Lower deck: 5-slot floating smoked-glass capsule featuring [Home, Library, Glowing Center Orb, Downloads, Settings]
 */
@Composable
fun UnifiedBottomDock(
    selectedTab: AppTab,
    playbackState: PlaybackUiState,
    activeDownloadCount: Int,
    onSelectTab: (AppTab) -> Unit,
    onCenterOrbClick: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    loadArtwork: suspend (uri: String, albumId: Long, highRes: Boolean) -> Bitmap?,
    modifier: Modifier = Modifier,
    showMiniPlayer: Boolean = true,
    playbackPositionMsFlow: StateFlow<Long>? = null
) {
    val tokens = HiFiTheme.colors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(
                start = HiFiSpacing.dockHorizontalInset,
                end = HiFiSpacing.dockHorizontalInset,
                bottom = 10.dp
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AnimatedVisibility(
            visible = showMiniPlayer && playbackState.currentTrack != null,
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom)
        ) {
            MiniPlayer(
                state = playbackState,
                onOpenNowPlaying = onCenterOrbClick,
                onTogglePlayPause = onTogglePlayPause,
                onSkipNext = onSkipNext,
                loadArtwork = loadArtwork,
                positionMsFlow = playbackPositionMsFlow
            )
        }

        // 5-slot floating smoked-glass navigation pill
        SmokedGlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .height(HiFiSpacing.dockHeight),
            shape = HiFiShapes.dockCapsule,
            containerColor = tokens.glassSurface
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(HiFiSpacing.dockHeight)
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                DockNavItem(
                    label = localText(AppTab.HOME.label),
                    icon = if (selectedTab == AppTab.HOME) Icons.Rounded.Home else Icons.Outlined.Home,
                    selected = selectedTab == AppTab.HOME,
                    onClick = { onSelectTab(AppTab.HOME) }
                )

                DockNavItem(
                    label = localText(AppTab.LIBRARY.label),
                    icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
                    selected = selectedTab == AppTab.LIBRARY,
                    onClick = { onSelectTab(AppTab.LIBRARY) }
                )

                // Center Glowing Artwork / Now-Playing Orb (iconic focal point from the reference)
                CenterOrbNavButton(
                    playbackState = playbackState,
                    onClick = onCenterOrbClick,
                    loadArtwork = loadArtwork
                )

                DockNavItem(
                    label = localText(AppTab.DOWNLOADS.label),
                    icon = Icons.Rounded.GraphicEq,
                    selected = selectedTab == AppTab.DOWNLOADS || selectedTab == AppTab.DISCOVER,
                    badgeCount = activeDownloadCount,
                    onClick = { onSelectTab(AppTab.DOWNLOADS) }
                )

                DockNavItem(
                    label = localText(AppTab.SETTINGS.label),
                    icon = if (selectedTab == AppTab.SETTINGS) Icons.Rounded.Settings else Icons.Outlined.Settings,
                    selected = selectedTab == AppTab.SETTINGS,
                    onClick = { onSelectTab(AppTab.SETTINGS) }
                )
            }
        }
    }
}

@Composable
private fun DockNavItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    badgeCount: Int = 0
) {
    val tokens = HiFiTheme.colors
    val tint = if (selected) tokens.inkPrimary else tokens.inkTertiary

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .semantics {
                contentDescription = label
                this.selected = selected
            }
            .clickable(
                role = Role.Tab,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(contentAlignment = Alignment.TopEnd) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(23.dp)
                )
                if (badgeCount > 0) {
                    Box(
                        modifier = Modifier
                            .offset(x = 6.dp, y = (-4).dp)
                            .clip(CircleShape)
                            .background(tokens.accent)
                            .padding(horizontal = 4.5.dp, vertical = 1.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (badgeCount > 9) "9+" else "$badgeCount",
                            style = MaterialTheme.typography.labelSmall,
                            color = tokens.onAccent
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .size(width = if (selected) 14.dp else 0.dp, height = 2.5.dp)
                    .clip(CircleShape)
                    .background(if (selected) tokens.accent else Color.Transparent)
            )
        }
    }
}

@Composable
private fun CenterOrbNavButton(
    playbackState: PlaybackUiState,
    onClick: () -> Unit,
    loadArtwork: suspend (uri: String, albumId: Long, highRes: Boolean) -> Bitmap?
) {
    val tokens = HiFiTheme.colors
    val track = playbackState.currentTrack
    val ringBrush = remember(tokens.progressArcStart, tokens.accent, tokens.accentBright) {
        Brush.sweepGradient(
            colors = listOf(
                tokens.progressArcStart,
                tokens.accent,
                tokens.accentBright,
                tokens.progressArcStart
            )
        )
    }
    val orbLabel = localText("Now playing")

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .semantics { contentDescription = orbLabel }
            .border(
                width = if (playbackState.isPlaying) 1.8.dp else 1.1.dp,
                brush = ringBrush,
                shape = CircleShape
            )
            .padding(2.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (track != null) {
            TrackArtworkBox(
                uri = track.uri,
                albumId = track.albumId,
                highRes = false,
                isPlayingIndicator = playbackState.isPlaying,
                shape = CircleShape,
                showBorder = false,
                iconSize = 20.dp,
                modifier = Modifier.size(44.dp),
                loadArtwork = loadArtwork
            )
        } else {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                tokens.progressArcStart,
                                tokens.auraPeak,
                                tokens.canvasDeep
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
