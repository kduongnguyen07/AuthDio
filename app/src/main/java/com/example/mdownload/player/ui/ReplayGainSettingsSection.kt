package com.example.mdownload.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.mdownload.core.AppSettings
import com.example.mdownload.core.audio.MissingReplayGainBehavior
import com.example.mdownload.core.audio.ReplayGainCalculator
import com.example.mdownload.core.audio.ReplayGainMode
import com.example.mdownload.player.playback.GainCoordinatorState
import com.example.mdownload.ui.design.HiFiTheme
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReplayGainSettingsContent(
    settings: AppSettings,
    gainState: GainCoordinatorState,
    bi: (String, String) -> String,
    onSelectMode: (ReplayGainMode) -> Unit,
    onChangePreampDb: (Float) -> Unit,
    onSelectMissingBehavior: (MissingReplayGainBehavior) -> Unit,
    onChangeFallbackPreampDb: (Float) -> Unit,
    onTogglePreventClipping: (Boolean) -> Unit
) {
    val tokens = HiFiTheme.colors
    val mode = ReplayGainMode.fromId(settings.replayGainMode)
    val missingBehavior = MissingReplayGainBehavior.fromId(settings.replayGainMissingBehavior)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = bi(
                "Normalizes perceived loudness during playback without altering your audio files. Reads ID3v2 (TXXX/RVA2), FLAC/Vorbis, Opus R128, MP4 atoms, and optional EBU R128 scans.",
                "Cân bằng độ lớn âm thanh khi phát mà không chỉnh sửa tệp nhạc gốc. Đọc tag ID3v2 (TXXX/RVA2), FLAC/Vorbis, Opus R128, MP4 và kết quả quét EBU R128."
            ),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.inkSecondary
        )

        // Mode chips
        Text(
            text = bi("Normalization mode", "Chế độ cân bằng"),
            style = MaterialTheme.typography.labelLarge,
            color = tokens.inkPrimary
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ReplayGainMode.entries.forEach { entry ->
                val label = when (entry) {
                    ReplayGainMode.OFF -> bi("Off", "Tắt")
                    ReplayGainMode.TRACK -> bi("Track Gain", "Theo bài hát")
                    ReplayGainMode.ALBUM -> bi("Album Gain", "Theo album")
                }
                FilterChip(
                    selected = mode == entry,
                    onClick = { onSelectMode(entry) },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = tokens.accentSoft,
                        selectedLabelColor = tokens.accentBright
                    )
                )
            }
        }

        if (mode == ReplayGainMode.ALBUM) {
            Text(
                text = bi(
                    "Preserves relative volume within an album; automatically falls back to Track Gain when album tags are unavailable.",
                    "Giữ độ chênh âm lượng tự nhiên trong album; tự động chuyển về Track Gain nếu bài không có tag album."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.inkTertiary
            )
        }

        if (mode != ReplayGainMode.OFF) {
            // Preamp slider (tracks with tags)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = bi("Preamp (with ReplayGain)", "Preamp (có ReplayGain)"),
                        style = MaterialTheme.typography.labelLarge,
                        color = tokens.inkPrimary
                    )
                    Text(
                        text = ReplayGainCalculator.formatDb(settings.replayGainPreampDb),
                        style = MaterialTheme.typography.labelLarge,
                        color = tokens.accentBright
                    )
                }
                Slider(
                    value = settings.replayGainPreampDb.coerceIn(-12f, 12f),
                    onValueChange = { raw ->
                        val snapped = (raw * 2f).roundToInt() / 2f
                        onChangePreampDb(snapped)
                    },
                    valueRange = -12f..12f,
                    steps = 47,
                    colors = SliderDefaults.colors(
                        thumbColor = tokens.accentBright,
                        activeTrackColor = tokens.accent
                    )
                )
            }

            // Missing metadata behavior
            Text(
                text = bi("Tracks without ReplayGain tags", "Bài không có tag ReplayGain"),
                style = MaterialTheme.typography.labelLarge,
                color = tokens.inkPrimary
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MissingReplayGainBehavior.entries.forEach { b ->
                    val label = when (b) {
                        MissingReplayGainBehavior.LEAVE_UNCHANGED -> bi("Leave unchanged (0 dB)", "Giữ nguyên (0 dB)")
                        MissingReplayGainBehavior.APPLY_FALLBACK_PREAMP -> bi("Fallback preamp", "Dùng preamp dự phòng")
                    }
                    FilterChip(
                        selected = missingBehavior == b,
                        onClick = { onSelectMissingBehavior(b) },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = tokens.accentSoft,
                            selectedLabelColor = tokens.accentBright
                        )
                    )
                }
            }

            if (missingBehavior == MissingReplayGainBehavior.APPLY_FALLBACK_PREAMP) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = bi("Fallback preamp", "Preamp dự phòng"),
                            style = MaterialTheme.typography.labelLarge,
                            color = tokens.inkPrimary
                        )
                        Text(
                            text = ReplayGainCalculator.formatDb(settings.replayGainFallbackPreampDb),
                            style = MaterialTheme.typography.labelLarge,
                            color = tokens.accentBright
                        )
                    }
                    Slider(
                        value = settings.replayGainFallbackPreampDb.coerceIn(-12f, 6f),
                        onValueChange = { raw ->
                            val snapped = (raw * 2f).roundToInt() / 2f
                            onChangeFallbackPreampDb(snapped)
                        },
                        valueRange = -12f..6f,
                        steps = 35,
                        colors = SliderDefaults.colors(
                            thumbColor = tokens.accentBright,
                            activeTrackColor = tokens.accent
                        )
                    )
                }
            }

            // Sample-peak limiting toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = bi("Peak-aware gain limiting", "Giới hạn khuếch đại theo peak"),
                        style = MaterialTheme.typography.titleSmall,
                        color = tokens.inkPrimary
                    )
                    Text(
                        text = bi(
                            "Caps gain using sample-peak metadata when available (headroom protection; not inter-sample true-peak limiting).",
                            "Giới hạn mức tăng theo metadata sample-peak khi có sẵn để tránh méo tiếng."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.inkSecondary
                    )
                }
                Switch(
                    checked = settings.replayGainPreventClipping,
                    onCheckedChange = onTogglePreventClipping
                )
            }

            // Live status line
            Text(
                text = bi("Active stage: ", "Đang áp dụng: ") +
                    "${gainState.applicationPath.label} · ${ReplayGainCalculator.formatDb(gainState.replayGainResult.appliedGainDb)} (${gainState.replayGainResult.reason})",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.accentBright
            )
        }
    }
}
