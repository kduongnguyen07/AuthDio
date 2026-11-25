package com.example.mdownload.player.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.mdownload.core.LibraryTrack
import com.example.mdownload.core.TextUtils
import com.example.mdownload.player.library.ArtistGroup
import com.example.mdownload.player.library.ArtistLibraryHelper
import com.example.mdownload.ui.AppText as Text
import com.example.mdownload.ui.components.HiFiGlassChip
import com.example.mdownload.ui.components.HiFiGlassTextField
import com.example.mdownload.ui.components.HiFiPillActionButton
import com.example.mdownload.ui.components.SmokedGlassSurface
import com.example.mdownload.ui.design.HiFiShapes
import com.example.mdownload.ui.design.HiFiTheme
import com.example.mdownload.ui.localText
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * Circular artist portrait rendering a user-selected custom artist cover photo when set,
 * otherwise falling back cleanly to the artist's representative track artwork or a minimalist silhouette.
 */
@Composable
fun ArtistAvatarBox(
    customCoverPath: String,
    fallbackTrackUri: String,
    fallbackAlbumId: Long = 0L,
    highRes: Boolean = false,
    iconSize: Dp = 24.dp,
    modifier: Modifier = Modifier,
    loadArtwork: suspend (uri: String, albumId: Long, highRes: Boolean) -> Bitmap?
) {
    val tokens = HiFiTheme.colors
    val customFile = remember(customCoverPath) {
        customCoverPath.trim().takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isFile }
    }

    if (customFile != null) {
        Box(
            modifier = modifier
                .clip(CircleShape)
                .background(tokens.glassElevated)
                .border(
                    width = 1.dp,
                    color = tokens.accentBright.copy(alpha = if (tokens.isDark) 0.42f else 0.55f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = customFile,
                contentDescription = localText("Artist cover"),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    } else if (fallbackTrackUri.isNotBlank()) {
        TrackArtworkBox(
            uri = fallbackTrackUri,
            albumId = fallbackAlbumId,
            highRes = highRes,
            shape = CircleShape,
            iconSize = iconSize,
            modifier = modifier,
            loadArtwork = loadArtwork
        )
    } else {
        val fallbackBrush = Brush.linearGradient(
            colors = listOf(
                tokens.auraMid.copy(alpha = if (tokens.isDark) 0.72f else 0.45f),
                tokens.glassElevated
            )
        )
        Box(
            modifier = modifier
                .clip(CircleShape)
                .background(fallbackBrush)
                .border(
                    width = 0.8.dp,
                    color = tokens.glassRimTop.copy(alpha = if (tokens.isDark) 0.28f else 0.52f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Person,
                contentDescription = localText("Artist cover"),
                tint = tokens.accentBright,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

/**
 * Smoked-glass modal for creating a new artist or editing an existing artist's name and cover photo.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArtistEditorDialog(
    title: String,
    initialName: String,
    initialCoverPath: String,
    fallbackTrackUri: String = "",
    fallbackAlbumId: Long = 0L,
    existingArtistNames: List<String> = emptyList(),
    confirmLabel: String,
    onImportCover: suspend (Uri) -> String?,
    loadArtwork: suspend (uri: String, albumId: Long, highRes: Boolean) -> Bitmap?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, coverPath: String, clearCover: Boolean) -> Unit
) {
    val tokens = HiFiTheme.colors
    val scope = rememberCoroutineScope()

    var name by remember(initialName) { mutableStateOf(initialName) }
    var coverPath by remember(initialCoverPath) { mutableStateOf(initialCoverPath) }
    var clearCover by remember(initialCoverPath) { mutableStateOf(false) }
    var isImportingCover by remember { mutableStateOf(false) }
    var coverNote by remember { mutableStateOf<String?>(null) }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { pickedUri ->
        if (pickedUri != null) {
            scope.launch {
                isImportingCover = true
                coverNote = null
                val imported = onImportCover(pickedUri)
                if (!imported.isNullOrBlank()) {
                    coverPath = imported
                    clearCover = false
                    coverNote = "Artist cover selected"
                } else {
                    coverNote = "Could not load selected image"
                }
                isImportingCover = false
            }
        }
    }

    val willMerge = remember(name, initialName, existingArtistNames) {
        val clean = name.trim()
        if (clean.isBlank()) return@remember false
        val newKey = ArtistLibraryHelper.normalizeKey(clean)
        val oldKey = ArtistLibraryHelper.normalizeKey(initialName)
        if (initialName.isNotBlank() && newKey == oldKey) return@remember false
        existingArtistNames.any { ArtistLibraryHelper.normalizeKey(it) == newKey }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = HiFiShapes.card,
        containerColor = tokens.canvasDeep,
        title = {
            Text(title, style = MaterialTheme.typography.titleMedium, color = tokens.inkPrimary)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SmokedGlassSurface(
                    shape = HiFiShapes.input,
                    containerColor = tokens.glassElevated
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(78.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            ArtistAvatarBox(
                                customCoverPath = if (clearCover) "" else coverPath,
                                fallbackTrackUri = fallbackTrackUri,
                                fallbackAlbumId = fallbackAlbumId,
                                highRes = true,
                                iconSize = 30.dp,
                                modifier = Modifier.fillMaxSize(),
                                loadArtwork = loadArtwork
                            )
                            if (isImportingCover) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape)
                                        .background(tokens.canvasDeep.copy(alpha = 0.72f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        color = tokens.accent,
                                        strokeWidth = 2.dp
                                    )
                                }
                            }
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Artist cover",
                                style = MaterialTheme.typography.titleSmall,
                                color = tokens.inkPrimary
                            )
                            if (!coverNote.isNullOrBlank()) {
                                Text(
                                    text = coverNote!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = tokens.accentBright
                                )
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                HiFiGlassChip(
                                    label = "Choose photo",
                                    selected = false,
                                    leadingIcon = Icons.Rounded.Image,
                                    onClick = {
                                        if (!isImportingCover) {
                                            photoPicker.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                            )
                                        }
                                    }
                                )
                                if (coverPath.isNotBlank() && !clearCover) {
                                    TextButton(
                                        onClick = {
                                            coverPath = ""
                                            clearCover = true
                                            coverNote = null
                                        },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Icon(
                                            Icons.Rounded.RestartAlt,
                                            contentDescription = null,
                                            tint = tokens.inkSecondary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(Modifier.width(2.dp))
                                        Text("Reset", style = MaterialTheme.typography.labelSmall, color = tokens.inkSecondary)
                                    }
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Artist name") },
                    placeholder = { Text("Enter artist name") },
                    singleLine = true,
                    shape = HiFiShapes.input,
                    modifier = Modifier.fillMaxWidth()
                )

                if (willMerge) {
                    Text(
                        text = if (initialName.isBlank()) {
                            "An artist with this name already exists; saving will update its cover."
                        } else {
                            "Matches an existing artist; saving will merge their songs together."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.accentBright
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = tokens.inkSecondary)
            }
        },
        confirmButton = {
            HiFiPillActionButton(
                label = confirmLabel,
                enabled = name.isNotBlank() && !isImportingCover,
                primary = true,
                onClick = {
                    onConfirm(name.trim(), if (clearCover) "" else coverPath, clearCover)
                }
            )
        }
    )
}

private enum class SongPickerFilter(val label: String) {
    ALL("All"),
    ASSIGNED("In artist"),
    OTHER("Other songs")
}

/**
 * Searchable multi-select song picker allowing users to add mis-tagged songs into an artist
 * or uncheck songs to remove them from the artist.
 */
@Composable
fun AssignSongsToArtistDialog(
    artistName: String,
    allTracks: List<LibraryTrack>,
    initialAssignedUris: Set<String>,
    loadArtwork: suspend (uri: String, albumId: Long, highRes: Boolean) -> Bitmap?,
    onDismiss: () -> Unit,
    onConfirm: (selectedTrackUris: Set<String>) -> Unit
) {
    val tokens = HiFiTheme.colors
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(SongPickerFilter.ALL) }
    var selectedUris by remember(artistName, initialAssignedUris) { mutableStateOf(initialAssignedUris) }

    val visibleTracks = remember(allTracks, search, filter, selectedUris) {
        val query = TextUtils.asciiText(search).lowercase(Locale.ROOT).trim()
        allTracks
            .filter { track ->
                when (filter) {
                    SongPickerFilter.ALL -> true
                    SongPickerFilter.ASSIGNED -> track.uri in selectedUris
                    SongPickerFilter.OTHER -> track.uri !in selectedUris
                }
            }
            .filter { track ->
                if (query.isBlank()) true
                else {
                    TextUtils.asciiText(track.title).lowercase(Locale.ROOT).contains(query) ||
                        TextUtils.asciiText(track.artist).lowercase(Locale.ROOT).contains(query) ||
                        track.title.lowercase(Locale.ROOT).contains(search.trim().lowercase(Locale.ROOT))
                }
            }
            .sortedWith(
                compareByDescending<LibraryTrack> { it.uri in selectedUris }
                    .thenBy { ArtistLibraryHelper.normalizeKey(it.title) }
            )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = HiFiShapes.card,
        containerColor = tokens.canvasDeep,
        title = {
            Column {
                Text("Manage songs", style = MaterialTheme.typography.titleMedium, color = tokens.inkPrimary)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = artistName,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.accentBright,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HiFiGlassTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = "Search song or artist…",
                    onClear = { search = "" }
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SongPickerFilter.entries.forEach { option ->
                        HiFiGlassChip(
                            label = option.label,
                            selected = filter == option,
                            onClick = { filter = option }
                        )
                    }
                }

                if (visibleTracks.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No matching tracks",
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.inkSecondary
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 340.dp)
                    ) {
                        items(visibleTracks, key = { it.uri }) { track ->
                            val isChecked = track.uri in selectedUris
                            val toggle = {
                                selectedUris = if (isChecked) {
                                    selectedUris - track.uri
                                } else {
                                    selectedUris + track.uri
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = toggle)
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TrackArtworkBox(
                                    uri = track.uri,
                                    albumId = track.albumId,
                                    highRes = false,
                                    cornerRadius = 10.dp,
                                    iconSize = 18.dp,
                                    modifier = Modifier.size(40.dp),
                                    loadArtwork = loadArtwork
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = track.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (isChecked) tokens.inkPrimary else tokens.inkSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = track.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isChecked) tokens.accentBright else tokens.inkSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { toggle() },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = tokens.accent,
                                        uncheckedColor = tokens.inkSecondary,
                                        checkmarkColor = tokens.onAccent
                                    )
                                )
                            }
                            HorizontalDivider(thickness = 0.5.dp, color = tokens.divider)
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = tokens.inkSecondary)
            }
        },
        confirmButton = {
            HiFiPillActionButton(
                label = "Save (${selectedUris.size})",
                primary = true,
                onClick = { onConfirm(selectedUris) }
            )
        }
    )
}

/**
 * Quick single-track artist reassignment modal: pick any existing artist, create a new artist inline,
 * or reset a manually overridden song back to its original embedded file tag.
 */
@Composable
fun AssignTrackArtistDialog(
    track: LibraryTrack,
    artists: List<ArtistGroup>,
    isOverridden: Boolean,
    originalTagArtist: String?,
    loadArtwork: suspend (uri: String, albumId: Long, highRes: Boolean) -> Bitmap?,
    onSelectArtist: (artistName: String) -> Unit,
    onCreateAndAssign: (artistName: String) -> Unit,
    onResetToOriginal: () -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = HiFiTheme.colors
    var newArtistName by remember { mutableStateOf("") }
    val currentKey = remember(track.artist) { ArtistLibraryHelper.normalizeKey(track.artist) }

    val filteredArtists = remember(artists, newArtistName) {
        val query = TextUtils.asciiText(newArtistName).lowercase(Locale.ROOT).trim()
        if (query.isBlank()) artists
        else artists.filter {
            TextUtils.asciiText(it.name).lowercase(Locale.ROOT).contains(query) ||
                it.name.lowercase(Locale.ROOT).contains(newArtistName.trim().lowercase(Locale.ROOT))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = HiFiShapes.card,
        containerColor = tokens.canvasDeep,
        title = { Text("Change artist", color = tokens.inkPrimary) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
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
                        value = newArtistName,
                        onValueChange = { newArtistName = it },
                        placeholder = "New or filter artist…",
                        leadingIcon = null,
                        modifier = Modifier.weight(1f)
                    )
                    HiFiPillActionButton(
                        label = "+",
                        onClick = {
                            if (newArtistName.isNotBlank()) onCreateAndAssign(newArtistName.trim())
                        },
                        enabled = newArtistName.isNotBlank(),
                        primary = true
                    )
                }

                if (isOverridden && !originalTagArtist.isNullOrBlank()) {
                    SmokedGlassSurface(
                        shape = HiFiShapes.input,
                        containerColor = tokens.glassElevated,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onResetToOriginal)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.RestartAlt,
                                contentDescription = null,
                                tint = tokens.accentBright,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Reset original artist",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = tokens.inkPrimary
                                )
                                Text(
                                    text = originalTagArtist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = tokens.inkSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                if (filteredArtists.isNotEmpty()) {
                    HorizontalDivider(thickness = 0.7.dp, color = tokens.divider)
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                    ) {
                        items(filteredArtists, key = { it.id }) { artist ->
                            val isSelected = artist.id == currentKey
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectArtist(artist.name) }
                                    .padding(vertical = 9.dp, horizontal = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ArtistAvatarBox(
                                    customCoverPath = artist.customCoverPath,
                                    fallbackTrackUri = artist.coverTrackUri,
                                    fallbackAlbumId = artist.coverAlbumId,
                                    iconSize = 18.dp,
                                    modifier = Modifier.size(38.dp),
                                    loadArtwork = loadArtwork
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = artist.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (isSelected) tokens.accentBright else tokens.inkPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${artist.tracks.size} track(s)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = tokens.inkSecondary
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = tokens.accentBright,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            HorizontalDivider(thickness = 0.5.dp, color = tokens.divider)
                        }
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
