package com.example.mdownload.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.mdownload.core.LibraryTrack
import com.example.mdownload.player.library.UserPlaylist
import com.example.mdownload.ui.AppText as Text
import com.example.mdownload.ui.components.HiFiGlassTextField
import com.example.mdownload.ui.components.HiFiPillActionButton
import com.example.mdownload.ui.design.HiFiShapes
import com.example.mdownload.ui.design.HiFiTheme

@Composable
fun AddToPlaylistDialog(
    track: LibraryTrack,
    playlists: List<UserPlaylist>,
    onSelectPlaylist: (playlistId: String) -> Unit,
    onCreateAndAdd: (name: String) -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = HiFiTheme.colors
    var newName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = HiFiShapes.card,
        containerColor = tokens.canvasDeep,
        title = { Text("Add to playlist", color = tokens.inkPrimary) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HiFiGlassTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = "New playlist name",
                        leadingIcon = null,
                        modifier = Modifier.weight(1f)
                    )
                    HiFiPillActionButton(
                        label = "+",
                        onClick = {
                            if (newName.isNotBlank()) onCreateAndAdd(newName.trim())
                        },
                        enabled = newName.isNotBlank(),
                        primary = true
                    )
                }

                if (playlists.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider(thickness = 0.7.dp, color = tokens.divider)
                    playlists.forEach { playlist ->
                        val alreadyIn = track.uri in playlist.trackUris
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectPlaylist(playlist.id) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.PlaylistPlay,
                                contentDescription = null,
                                tint = tokens.accent,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = playlist.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = tokens.inkPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (alreadyIn) {
                                        "${playlist.trackUris.size} track(s) · Added"
                                    } else {
                                        "${playlist.trackUris.size} track(s)"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = tokens.inkSecondary
                                )
                            }
                        }
                        HorizontalDivider(thickness = 0.6.dp, color = tokens.divider)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = tokens.accent)
            }
        }
    )
}
