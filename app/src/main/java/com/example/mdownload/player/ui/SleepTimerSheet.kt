package com.example.mdownload.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.mdownload.player.playback.SleepTimerMode
import com.example.mdownload.player.playback.SleepTimerState
import com.example.mdownload.ui.components.HiFiPillActionButton
import com.example.mdownload.ui.design.HiFiTheme
import com.example.mdownload.ui.localText
import java.util.Locale

private val TIMER_PRESETS_MIN = listOf(5, 15, 30, 45, 60)
private val FADE_OUT_OPTIONS_SEC = listOf(0, 5, 10, 15, 30)

fun formatSleepCountdown(remainingMs: Long): String {
    val totalSec = ((remainingMs + 999L) / 1000L).coerceAtLeast(0L)
    val hrs = totalSec / 3600L
    val mins = (totalSec % 3600L) / 60L
    val secs = totalSec % 60L
    return if (hrs > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hrs, mins, secs)
    } else {
        String.format(Locale.US, "%02d:%02d", mins, secs)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SleepTimerSheet(
    state: SleepTimerState,
    defaultFadeSeconds: Int,
    onStartDuration: (minutes: Int, fadeSeconds: Int) -> Unit,
    onStartEndOfTrack: (fadeSeconds: Int) -> Unit,
    onStartEndOfQueue: (fadeSeconds: Int) -> Unit,
    onUpdateFadeSeconds: (Int) -> Unit,
    onExtendMinutes: (Int) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = HiFiTheme.colors
    val sheetAccent = if (tokens.isDark) tokens.accentBright else tokens.accent
    val cardBorder = if (tokens.isDark) tokens.glassRimTop else tokens.glassRimBottom
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedFade by remember(state.fadeOutSeconds, defaultFadeSeconds) {
        mutableStateOf(if (state.isActive) state.fadeOutSeconds else defaultFadeSeconds)
    }
    var customMinutesText by remember { mutableStateOf("") }

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
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Bedtime,
                    contentDescription = null,
                    tint = sheetAccent,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = localText("Sleep Timer"),
                    style = MaterialTheme.typography.titleLarge,
                    color = tokens.inkPrimary,
                    modifier = Modifier.weight(1f)
                )
                if (state.isActive) {
                    TextButton(onClick = onCancel) {
                        Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(16.dp), tint = tokens.error)
                        Spacer(Modifier.width(4.dp))
                        Text(localText("Cancel"), color = tokens.error)
                    }
                }
            }

            // Active status card
            if (state.isActive) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(tokens.accentSoft.copy(alpha = if (tokens.isDark) 0.45f else 0.72f))
                        .border(0.8.dp, sheetAccent.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = when (state.mode) {
                                    SleepTimerMode.DURATION -> localText("Pausing playback in")
                                    SleepTimerMode.END_OF_TRACK -> localText("Stop at end of current song")
                                    SleepTimerMode.END_OF_QUEUE -> localText("Stop at end of queue")
                                    SleepTimerMode.OFF -> ""
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = tokens.inkSecondary
                            )
                            val bigLabel = when (state.mode) {
                                SleepTimerMode.DURATION -> formatSleepCountdown(state.remainingMs)
                                SleepTimerMode.END_OF_TRACK -> if (state.remainingMs > 0L) {
                                    formatSleepCountdown(state.remainingMs)
                                } else {
                                    localText("Active")
                                }
                                SleepTimerMode.END_OF_QUEUE -> localText("Active")
                                SleepTimerMode.OFF -> ""
                            }
                            Text(
                                text = bigLabel,
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = tokens.inkPrimary
                            )
                        }
                        if (state.isFadingOut) {
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(sheetAccent.copy(alpha = 0.18f))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = localText("Fading out…"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = sheetAccent
                                )
                            }
                        }
                    }

                    if (state.mode == SleepTimerMode.DURATION) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HiFiPillActionButton(
                                label = "+5 min",
                                onClick = { onExtendMinutes(5) },
                                primary = false
                            )
                            HiFiPillActionButton(
                                label = "+10 min",
                                onClick = { onExtendMinutes(10) },
                                primary = false
                            )
                            HiFiPillActionButton(
                                label = "+15 min",
                                onClick = { onExtendMinutes(15) },
                                primary = false
                            )
                        }
                    }
                }
            }

            // Duration presets
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = localText("Duration"),
                    style = MaterialTheme.typography.labelLarge,
                    color = tokens.inkSecondary
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TIMER_PRESETS_MIN.forEach { min ->
                        HiFiPillActionButton(
                            label = "$min min",
                            onClick = {
                                onStartDuration(min, selectedFade)
                                onDismiss()
                            },
                            primary = false
                        )
                    }
                }
            }

            // Custom minutes
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = customMinutesText,
                    onValueChange = { input -> customMinutesText = input.filter { it.isDigit() }.take(3) },
                    label = { Text(localText("Custom minutes (1–360)")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                val parsed = customMinutesText.toIntOrNull()
                HiFiPillActionButton(
                    label = localText("Start"),
                    onClick = {
                        if (parsed != null && parsed in 1..360) {
                            onStartDuration(parsed, selectedFade)
                            onDismiss()
                        }
                    },
                    enabled = parsed != null && parsed in 1..360,
                    primary = true
                )
            }

            // Track / Queue boundaries
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(tokens.glassSurface.copy(alpha = if (tokens.isDark) 0.72f else 0.96f))
                        .border(
                            0.8.dp,
                            if (state.mode == SleepTimerMode.END_OF_TRACK) sheetAccent else cardBorder,
                            RoundedCornerShape(14.dp)
                        )
                        .clickable {
                            onStartEndOfTrack(selectedFade)
                            onDismiss()
                        }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = sheetAccent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = localText("End of song"),
                        style = MaterialTheme.typography.labelLarge,
                        color = tokens.inkPrimary
                    )
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(tokens.glassSurface.copy(alpha = if (tokens.isDark) 0.72f else 0.96f))
                        .border(
                            0.8.dp,
                            if (state.mode == SleepTimerMode.END_OF_QUEUE) sheetAccent else cardBorder,
                            RoundedCornerShape(14.dp)
                        )
                        .clickable {
                            onStartEndOfQueue(selectedFade)
                            onDismiss()
                        }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Rounded.QueueMusic, contentDescription = null, tint = sheetAccent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = localText("End of queue"),
                        style = MaterialTheme.typography.labelLarge,
                        color = tokens.inkPrimary
                    )
                }
            }

            HorizontalDivider(thickness = 0.6.dp, color = tokens.divider)

            // Fade-out duration selector
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = localText("Fade-out before pause"),
                    style = MaterialTheme.typography.labelLarge,
                    color = tokens.inkSecondary
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FADE_OUT_OPTIONS_SEC.forEach { sec ->
                        val selected = selectedFade == sec
                        FilterChip(
                            selected = selected,
                            onClick = {
                                selectedFade = sec
                                onUpdateFadeSeconds(sec)
                            },
                            label = { Text(if (sec == 0) localText("Off") else "${sec}s") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = tokens.accentSoft,
                                selectedLabelColor = sheetAccent,
                                labelColor = tokens.inkPrimary
                            )
                        )
                    }
                }
                Text(
                    text = localText("Gradually attenuates playback gain without changing system volume; preserves queue and ReplayGain settings."),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.inkSecondary
                )
            }
        }
    }
}
