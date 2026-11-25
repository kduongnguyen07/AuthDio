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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.mdownload.core.LibraryTrack
import com.example.mdownload.player.library.MatchMode
import com.example.mdownload.player.library.PlaylistRule
import com.example.mdownload.player.library.PlaylistSortOrder
import com.example.mdownload.player.library.SmartPlaylistDefinition
import com.example.mdownload.player.library.SmartPlaylistEvalContext
import com.example.mdownload.player.library.SmartPlaylistEvaluator
import com.example.mdownload.player.library.SmartRuleType
import com.example.mdownload.ui.components.HiFiPillActionButton
import com.example.mdownload.ui.design.HiFiTheme
import com.example.mdownload.ui.localText
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SmartPlaylistEditorDialog(
    initial: SmartPlaylistDefinition?,
    allTracks: List<LibraryTrack>,
    evalContext: SmartPlaylistEvalContext,
    onDismiss: () -> Unit,
    onSave: (SmartPlaylistDefinition) -> Unit
) {
    val tokens = HiFiTheme.colors
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var matchMode by remember(initial) { mutableStateOf(initial?.matchMode ?: MatchMode.ALL) }
    var sortOrder by remember(initial) { mutableStateOf(initial?.sortOrder ?: PlaylistSortOrder.DATE_ADDED_DESC) }
    var limitText by remember(initial) { mutableStateOf(initial?.limit?.toString().orEmpty()) }
    var rules by remember(initial) {
        mutableStateOf(
            initial?.rules?.takeIf { it.isNotEmpty() }
                ?: listOf(PlaylistRule(SmartRuleType.ARTIST_CONTAINS, stringValue = ""))
        )
    }

    val previewDef = remember(name, matchMode, sortOrder, limitText, rules) {
        SmartPlaylistDefinition(
            id = initial?.id ?: UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "Smart Playlist" },
            rules = rules,
            matchMode = matchMode,
            sortOrder = sortOrder,
            limit = limitText.toIntOrNull()?.takeIf { it > 0 },
            isBuiltIn = false
        )
    }
    val previewCount by produceState(
        initialValue = 0,
        key1 = previewDef,
        key2 = allTracks,
        key3 = evalContext
    ) {
        value = withContext(Dispatchers.Default) {
            SmartPlaylistEvaluator.evaluate(previewDef, allTracks, evalContext).size
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = tokens.glassElevated,
        title = {
            Text(
                text = localText(if (initial == null) "New Smart Playlist" else "Edit Smart Playlist"),
                color = tokens.inkPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(localText("Playlist name")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Match mode (AND / OR)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MatchMode.entries.forEach { mode ->
                        FilterChip(
                            selected = matchMode == mode,
                            onClick = { matchMode = mode },
                            label = {
                                Text(if (mode == MatchMode.ALL) localText("All (AND)") else localText("Any (OR)"))
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = tokens.accentSoft,
                                selectedLabelColor = tokens.accentBright
                            )
                        )
                    }
                }

                // Rules list
                Text(
                    text = localText("Rules"),
                    style = MaterialTheme.typography.labelLarge,
                    color = tokens.inkSecondary
                )
                rules.forEachIndexed { idx, rule ->
                    SmartRuleRowEditor(
                        rule = rule,
                        canRemove = rules.size > 1,
                        onChange = { updated ->
                            rules = rules.toMutableList().also { it[idx] = updated }
                        },
                        onRemove = {
                            rules = rules.toMutableList().also { it.removeAt(idx) }
                        }
                    )
                }

                OutlinedButton(
                    onClick = {
                        rules = rules + PlaylistRule(SmartRuleType.PLAY_COUNT_AT_LEAST, intValue = 1)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(localText("Add rule"))
                }

                HorizontalDivider(thickness = 0.5.dp, color = tokens.divider)

                // Sort order
                Text(
                    text = localText("Sort by"),
                    style = MaterialTheme.typography.labelLarge,
                    color = tokens.inkSecondary
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PlaylistSortOrder.entries.forEach { order ->
                        FilterChip(
                            selected = sortOrder == order,
                            onClick = { sortOrder = order },
                            label = { Text(localText(order.label)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = tokens.accentSoft,
                                selectedLabelColor = tokens.accentBright
                            )
                        )
                    }
                }

                // Result limit
                OutlinedTextField(
                    value = limitText,
                    onValueChange = { limitText = it.filter(Char::isDigit).take(4) },
                    label = { Text(localText("Max tracks (blank = unlimited)")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "${localText("Matching tracks right now:")} $previewCount",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.accentBright
                )
            }
        },
        confirmButton = {
            HiFiPillActionButton(
                label = localText("Save"),
                onClick = {
                    if (name.trim().isNotEmpty()) {
                        onSave(
                            previewDef.copy(
                                name = name.trim(),
                                description = rules.joinToString(" · ") { it.describe() }
                            )
                        )
                    }
                },
                enabled = name.trim().isNotEmpty(),
                primary = true
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(localText("Cancel"))
            }
        }
    )
}

@Composable
private fun SmartRuleRowEditor(
    rule: PlaylistRule,
    canRemove: Boolean,
    onChange: (PlaylistRule) -> Unit,
    onRemove: () -> Unit
) {
    val tokens = HiFiTheme.colors
    var typeMenuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.glassSurface.copy(alpha = 0.65f))
            .border(0.6.dp, tokens.glassRimTop, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { typeMenuOpen = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(localText(rule.type.label), maxLines = 1)
                }
                DropdownMenu(
                    expanded = typeMenuOpen,
                    onDismissRequest = { typeMenuOpen = false },
                    containerColor = tokens.glassElevated
                ) {
                    SmartRuleType.entries.forEach { t ->
                        DropdownMenuItem(
                            text = { Text(localText(t.label)) },
                            onClick = {
                                typeMenuOpen = false
                                onChange(PlaylistRule(type = t, stringValue = "", intValue = defaultIntFor(t), boolValue = true))
                            }
                        )
                    }
                }
            }
            if (canRemove) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Rounded.Delete, contentDescription = localText("Remove"), tint = tokens.error, modifier = Modifier.size(18.dp))
                }
            }
        }

        when (rule.type) {
            SmartRuleType.ARTIST_EQUALS,
            SmartRuleType.ARTIST_CONTAINS,
            SmartRuleType.ALBUM_EQUALS,
            SmartRuleType.GENRE_EQUALS,
            SmartRuleType.CODEC_EQUALS -> {
                OutlinedTextField(
                    value = rule.stringValue,
                    onValueChange = { onChange(rule.copy(stringValue = it)) },
                    placeholder = { Text(rule.type.valueHint) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SmartRuleType.DATE_ADDED_WITHIN_DAYS,
            SmartRuleType.PLAY_COUNT_AT_LEAST,
            SmartRuleType.PLAY_COUNT_AT_MOST,
            SmartRuleType.LAST_PLAYED_WITHIN_DAYS,
            SmartRuleType.BITRATE_AT_LEAST_KBPS,
            SmartRuleType.DURATION_AT_LEAST_SEC,
            SmartRuleType.DURATION_AT_MOST_SEC -> {
                OutlinedTextField(
                    value = rule.intValue.toString(),
                    onValueChange = { s ->
                        val v = s.filter(Char::isDigit).take(5).toIntOrNull() ?: 0
                        onChange(rule.copy(intValue = v))
                    },
                    placeholder = { Text(rule.type.valueHint) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SmartRuleType.HAS_BEEN_PLAYED,
            SmartRuleType.IS_FAVORITE,
            SmartRuleType.IS_LOSSLESS_CODEC,
            SmartRuleType.DOWNLOADED_BY_APP -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = rule.describe(),
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.inkPrimary
                    )
                    Switch(
                        checked = rule.boolValue,
                        onCheckedChange = { onChange(rule.copy(boolValue = it)) }
                    )
                }
            }
        }
    }
}

private fun defaultIntFor(type: SmartRuleType): Int = when (type) {
    SmartRuleType.DATE_ADDED_WITHIN_DAYS -> 30
    SmartRuleType.PLAY_COUNT_AT_LEAST -> 3
    SmartRuleType.PLAY_COUNT_AT_MOST -> 0
    SmartRuleType.LAST_PLAYED_WITHIN_DAYS -> 14
    SmartRuleType.BITRATE_AT_LEAST_KBPS -> 256
    SmartRuleType.DURATION_AT_LEAST_SEC -> 180
    SmartRuleType.DURATION_AT_MOST_SEC -> 300
    else -> 0
}
