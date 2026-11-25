package com.example.mdownload.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.mdownload.core.LibraryTrack
import com.example.mdownload.core.TextUtils
import com.example.mdownload.core.audio.AudioQualityInfo
import com.example.mdownload.core.audio.BitrateMode
import com.example.mdownload.core.audio.PlaybackOutputSnapshot
import com.example.mdownload.core.audio.ReplayGainCalculator
import com.example.mdownload.ui.components.HiFiPillActionButton
import com.example.mdownload.ui.design.HiFiTheme
import com.example.mdownload.ui.localText
import java.util.Locale

data class AudioInspectorUiState(
    val track: LibraryTrack? = null,
    val info: AudioQualityInfo? = null,
    val outputSnapshot: PlaybackOutputSnapshot? = null,
    val isLoading: Boolean = false,
    val isScanningLoudness: Boolean = false,
    val canScanLoudness: Boolean = false,
    val error: String? = null
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AudioInspectorSheet(
    state: AudioInspectorUiState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onScanLoudness: () -> Unit,
    onCancelScan: () -> Unit,
    onEditMetadata: (LibraryTrack) -> Unit
) {
    val track = state.track ?: return
    val tokens = HiFiTheme.colors
    val sheetAccent = if (tokens.isDark) tokens.accentBright else tokens.accent
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = tokens.surfaceElevated,
        contentColor = tokens.inkPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = localText("Audio Details"),
                        style = MaterialTheme.typography.titleLarge,
                        color = tokens.inkPrimary
                    )
                    Text(
                        text = "${track.title} · ${track.artist}",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.inkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onRefresh, enabled = !state.isLoading) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = localText("Refresh"),
                        tint = tokens.inkSecondary
                    )
                }
            }

            if (state.isLoading && state.info == null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = sheetAccent
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = localText("Inspecting source file…"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.inkSecondary
                    )
                }
            } else {
                val info = state.info
                val unknown = localText("Unknown")

                // Section 1: Format (Source File)
                InspectorSectionCard(title = localText("Format · Source File")) {
                    InspectorKeyValueRow(localText("Container"), info?.container ?: unknown)
                    InspectorKeyValueRow(localText("Audio codec"), info?.codec ?: unknown)
                    if (!info?.codecProfile.isNullOrBlank()) {
                        InspectorKeyValueRow(localText("Codec profile"), info!!.codecProfile!!)
                    }
                    InspectorKeyValueRow(localText("Encoding class"), localText(info?.losslessLabel ?: "Unknown"))
                    if (!info?.losslessCaveat.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(tokens.accentSoft.copy(alpha = 0.45f))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Info,
                                contentDescription = null,
                                tint = sheetAccent,
                                modifier = Modifier
                                    .size(15.dp)
                                    .padding(top = 1.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = info!!.losslessCaveat!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.inkSecondary
                            )
                        }
                    }
                }

                // Section 2: Audio Properties (Source File)
                InspectorSectionCard(title = localText("Audio Properties")) {
                    InspectorKeyValueRow(localText("Sample rate"), info?.formattedSampleRate ?: unknown)
                    InspectorKeyValueRow(localText("Channels"), info?.formattedChannels ?: unknown)
                    val bitDepthLabel = when {
                        info?.isLossless == false -> localText("N/A (lossy codec)")
                        info?.bitDepth != null -> "${info.bitDepth}-bit"
                        else -> unknown
                    }
                    InspectorKeyValueRow(localText("Bit depth"), bitDepthLabel)
                    if (!info?.sampleFormat.isNullOrBlank()) {
                        InspectorKeyValueRow(localText("Sample format"), info!!.sampleFormat!!)
                    }
                    val bitrateText = info?.bitrateKbps?.let { kbps ->
                        val modeSuffix = when (info.bitrateMode) {
                            BitrateMode.CBR -> " · CBR"
                            BitrateMode.VBR -> " · VBR"
                            BitrateMode.UNKNOWN -> ""
                        }
                        "$kbps kbps$modeSuffix"
                    } ?: unknown
                    InspectorKeyValueRow(localText("Bitrate"), bitrateText)
                    InspectorKeyValueRow(
                        localText("Duration"),
                        info?.durationMs?.takeIf { it > 0L }?.let { formatDurationMs(it) } ?: unknown
                    )
                    InspectorKeyValueRow(
                        localText("File size"),
                        info?.fileSizeBytes?.takeIf { it > 0L }?.let { TextUtils.formatFileSize(it) } ?: unknown
                    )
                    InspectorKeyValueRow(
                        localText("Location"),
                        info?.fileLocation ?: track.displayName
                    )
                }

                // Section 3: Playback Output (Live — strictly separate from Source File)
                val out = state.outputSnapshot
                if (out != null) {
                    InspectorSectionCard(title = localText("Playback Output")) {
                        InspectorKeyValueRow(
                            localText("Output route"),
                            out.outputDeviceName ?: unknown
                        )
                        if (!out.outputDeviceType.isNullOrBlank()) {
                            InspectorKeyValueRow(localText("Device type"), out.outputDeviceType)
                        }
                        val playerFmt = buildList {
                            out.reportedCodec?.takeIf { it.isNotBlank() }?.let { add(it) }
                            out.reportedSampleRateHz?.takeIf { it > 0 }?.let { sr ->
                                add(if (sr % 1000 == 0) "${sr / 1000} kHz" else String.format(Locale.US, "%.1f kHz", sr / 1000.0))
                            }
                            out.reportedChannelCount?.takeIf { it > 0 }?.let { add("${it}ch") }
                        }.joinToString(" · ").ifBlank { unknown }
                        InspectorKeyValueRow(localText("Player format"), playerFmt)
                        InspectorKeyValueRow(localText("Output path"), out.audioOffloadStatus)
                        InspectorKeyValueRow(localText("Gain stage"), out.gainApplicationPath.label)
                        InspectorKeyValueRow(
                            localText("Active ReplayGain"),
                            "${ReplayGainCalculator.formatDb(out.activeReplayGainDb)} (${out.replayGainReason})"
                        )
                    }
                }

                // Section 4: Metadata & Loudness
                InspectorSectionCard(title = localText("Metadata & Loudness")) {
                    val artworkText = when (info?.hasEmbeddedArtwork) {
                        true -> if (!info.artworkDimensions.isNullOrBlank()) "Embedded (${info.artworkDimensions})" else "Embedded"
                        false -> "None"
                        null -> unknown
                    }
                    InspectorKeyValueRow(localText("Artwork"), artworkText)
                    val lyricsText = when (info?.hasLyrics) {
                        true -> info.lyricsSourceLabel ?: "Available"
                        false -> "Not found"
                        null -> unknown
                    }
                    InspectorKeyValueRow(localText("Lyrics"), lyricsText)

                    val rg = info?.replayGain
                    if (rg != null && rg.hasAnyGain) {
                        InspectorKeyValueRow(localText("Loudness source"), rg.source)
                        rg.measuredIntegratedLufs?.let { lufs ->
                            InspectorKeyValueRow(
                                localText("Measured loudness"),
                                String.format(Locale.US, "%.1f LUFS", lufs)
                            )
                        }
                        rg.trackGainDb?.let { g ->
                            InspectorKeyValueRow(
                                localText(if (rg.isScannedEstimate) "Estimated track gain" else "Track gain"),
                                ReplayGainCalculator.formatDb(g)
                            )
                        }
                        rg.albumGainDb?.let { g ->
                            InspectorKeyValueRow(localText("Album gain"), ReplayGainCalculator.formatDb(g))
                        }
                        rg.trackPeak?.let { p ->
                            InspectorKeyValueRow(
                                localText("Track peak"),
                                String.format(Locale.US, "%.4f", p)
                            )
                        }
                    } else {
                        InspectorKeyValueRow(localText("ReplayGain"), localText("No tags found"))
                    }
                    InspectorKeyValueRow(localText("Inspector source"), info?.inspectionSource ?: unknown)
                }

                if (!state.error.isNullOrBlank()) {
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.error
                    )
                }

                // Contextual actions
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HiFiPillActionButton(
                        label = localText("Edit metadata"),
                        icon = Icons.Rounded.Edit,
                        onClick = { onEditMetadata(track) },
                        primary = false
                    )
                    if (state.canScanLoudness) {
                        if (state.isScanningLoudness) {
                            TextButton(onClick = onCancelScan) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = sheetAccent
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(localText("Cancel loudness scan"), color = tokens.error)
                            }
                        } else {
                            HiFiPillActionButton(
                                label = localText("Scan loudness (R128)"),
                                icon = Icons.Rounded.GraphicEq,
                                onClick = onScanLoudness,
                                primary = false
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InspectorSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    val tokens = HiFiTheme.colors
    val sectionAccent = if (tokens.isDark) tokens.accentBright else tokens.accent
    val cardBorder = if (tokens.isDark) tokens.glassRimTop else tokens.glassRimBottom
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(tokens.glassSurface.copy(alpha = if (tokens.isDark) 0.65f else 0.96f))
            .border(0.75.dp, cardBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = title.uppercase(Locale.US),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = sectionAccent
        )
        HorizontalDivider(thickness = 0.5.dp, color = tokens.divider)
        content()
    }
}

@Composable
private fun InspectorKeyValueRow(label: String, value: String) {
    val tokens = HiFiTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = tokens.inkSecondary,
            modifier = Modifier.padding(end = 12.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = tokens.inkPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun formatDurationMs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return String.format(Locale.US, "%d:%02d", min, sec)
}
