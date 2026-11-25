package com.example.mdownload.player.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.mdownload.core.LibraryTrack
import com.example.mdownload.core.metadata.BatchMetadataPatch
import com.example.mdownload.core.metadata.BatchMetadataResult
import com.example.mdownload.core.metadata.EditableTrackMetadata
import com.example.mdownload.core.metadata.FileSnapshot
import com.example.mdownload.core.metadata.MetadataValidator
import com.example.mdownload.core.metadata.MetadataWriteResult
import com.example.mdownload.ui.components.HiFiPillActionButton
import com.example.mdownload.ui.design.HiFiTheme
import com.example.mdownload.ui.localText
import kotlinx.coroutines.launch

data class TrackMetadataEditorUiState(
    val track: LibraryTrack,
    val original: EditableTrackMetadata = EditableTrackMetadata(),
    val snapshot: FileSnapshot? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null
)

data class BatchMetadataEditorUiState(
    val tracks: List<LibraryTrack>,
    val isSaving: Boolean = false,
    val completedCount: Int = 0,
    val totalCount: Int = tracks.size,
    val currentTrackTitle: String = "",
    val result: BatchMetadataResult? = null,
    val error: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackMetadataEditorSheet(
    state: TrackMetadataEditorUiState,
    onPrepareArtwork: suspend (Uri, Boolean) -> Pair<ByteArray, String>?,
    onSave: (EditableTrackMetadata) -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = HiFiTheme.colors
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var draft by remember(state.track.uri, state.isLoading) { mutableStateOf(state.original) }
    var squareCrop by remember { mutableStateOf(true) }
    var showDiscardDialog by remember { mutableStateOf(false) }

    val hasUnsavedChanges = !state.isLoading && draft.hasTextOrArtworkChangesFrom(state.original)
    val validation = remember(draft) { MetadataValidator.validateSingle(draft) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val prepared = onPrepareArtwork(uri, squareCrop)
                if (prepared != null) {
                    draft = draft.copy(
                        artworkBytes = prepared.first,
                        artworkMimeType = prepared.second,
                        artworkChanged = true,
                        artworkRemoved = false
                    )
                }
            }
        }
    }

    fun requestClose() {
        if (state.isSaving) return
        if (hasUnsavedChanges) showDiscardDialog = true else onDismiss()
    }

    val sheetAccent = if (tokens.isDark) tokens.accentBright else tokens.accent
    val cardBorder = if (tokens.isDark) tokens.glassRimTop else tokens.glassRimBottom

    ModalBottomSheet(
        onDismissRequest = ::requestClose,
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = localText("Edit Metadata"),
                        style = MaterialTheme.typography.titleLarge,
                        color = tokens.inkPrimary
                    )
                    Text(
                        text = state.track.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.inkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = ::requestClose, enabled = !state.isSaving) {
                    Text(localText("Cancel"), color = tokens.inkSecondary)
                }
                HiFiPillActionButton(
                    label = if (state.isSaving) localText("Saving…") else localText("Save"),
                    onClick = { onSave(draft) },
                    enabled = !state.isLoading && !state.isSaving && validation.isValid && hasUnsavedChanges,
                    primary = true
                )
            }

            if (state.isLoading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = sheetAccent)
                    Spacer(Modifier.width(12.dp))
                    Text(localText("Reading embedded tags…"), color = tokens.inkSecondary)
                }
            } else {
                if (state.isSaving) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = sheetAccent
                    )
                }
                if (!state.error.isNullOrBlank()) {
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.error
                    )
                }

                // Artwork preview & picker (decoded & downsampled off the Main Thread)
                val previewBitmap by produceState<ImageBitmap?>(
                    initialValue = null,
                    key1 = draft.artworkBytes,
                    key2 = draft.artworkRemoved
                ) {
                    val bytes = draft.artworkBytes
                    if (draft.artworkRemoved || bytes == null || bytes.isEmpty()) {
                        value = null
                    } else {
                        value = withContext(Dispatchers.Default) {
                            runCatching {
                                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                                var sampleSize = 1
                                val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
                                while (maxDim / sampleSize > 256) {
                                    sampleSize *= 2
                                }
                                val decodeOpts = BitmapFactory.Options().apply {
                                    inSampleSize = sampleSize
                                }
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
                                    ?.asImageBitmap()
                            }.getOrNull()
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(tokens.glassSurface.copy(alpha = if (tokens.isDark) 0.65f else 0.96f))
                        .border(0.75.dp, cardBorder, RoundedCornerShape(16.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(tokens.accentSoft.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        val bmp = previewBitmap
                        if (bmp != null) {
                            Image(
                                bitmap = bmp,
                                contentDescription = localText("Album artwork"),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Album,
                                contentDescription = null,
                                tint = tokens.inkTertiary,
                                modifier = Modifier.size(34.dp)
                            )
                        }
                    }
                    val supportsArtworkEdit = draft.format != com.example.mdownload.player.lyrics.AudioContainerFormat.OGG_OPUS
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = localText("Album artwork"),
                            style = MaterialTheme.typography.titleSmall,
                            color = tokens.inkPrimary
                        )
                        if (!supportsArtworkEdit) {
                            Text(
                                text = localText("Artwork editing is not supported for Ogg/Opus files. Text tags can still be edited safely."),
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.inkSecondary
                            )
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                HiFiPillActionButton(
                                    label = localText("Choose"),
                                    icon = Icons.Rounded.Image,
                                    onClick = {
                                        imagePicker.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                    enabled = !state.isSaving,
                                    primary = false
                                )
                                if (draft.hasArtwork) {
                                    TextButton(
                                        onClick = {
                                            draft = draft.copy(
                                                artworkBytes = null,
                                                artworkChanged = false,
                                                artworkRemoved = true
                                            )
                                        },
                                        enabled = !state.isSaving
                                    ) {
                                        Icon(Icons.Rounded.Delete, contentDescription = null, tint = tokens.error, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(localText("Remove"), color = tokens.error)
                                    }
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = localText("Crop 1:1 square"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = tokens.inkSecondary
                                )
                                Switch(checked = squareCrop, onCheckedChange = { squareCrop = it })
                            }
                        }
                    }
                }

                // Group 1: Core identity
                SectionHeading(localText("Track Information"))
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = { draft = draft.copy(title = it) },
                    label = { Text(localText("Title")) },
                    isError = validation.fieldErrors.containsKey("title"),
                    supportingText = validation.fieldErrors["title"]?.let { err -> { Text(err) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = draft.artist,
                    onValueChange = { draft = draft.copy(artist = it) },
                    label = { Text(localText("Artist")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = draft.albumArtist,
                    onValueChange = { draft = draft.copy(albumArtist = it) },
                    label = { Text(localText("Album artist")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = draft.album,
                    onValueChange = { draft = draft.copy(album = it) },
                    label = { Text(localText("Album")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Group 2: Classification & numbering
                SectionHeading(localText("Release & Numbering"))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = draft.genre,
                        onValueChange = { draft = draft.copy(genre = it) },
                        label = { Text(localText("Genre")) },
                        singleLine = true,
                        modifier = Modifier.weight(1.3f)
                    )
                    OutlinedTextField(
                        value = draft.year,
                        onValueChange = { draft = draft.copy(year = it.take(10)) },
                        label = { Text(localText("Year / Date")) },
                        isError = validation.fieldErrors.containsKey("year"),
                        supportingText = validation.fieldErrors["year"]?.let { err -> { Text(err) } },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = draft.trackNumber,
                        onValueChange = { draft = draft.copy(trackNumber = it.filter(Char::isDigit).take(4)) },
                        label = { Text(localText("Track #")) },
                        isError = validation.fieldErrors.containsKey("trackNumber"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = draft.trackTotal,
                        onValueChange = { draft = draft.copy(trackTotal = it.filter(Char::isDigit).take(4)) },
                        label = { Text(localText("Of total")) },
                        isError = validation.fieldErrors.containsKey("trackTotal"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                validation.fieldErrors["trackNumber"]?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = draft.discNumber,
                        onValueChange = { draft = draft.copy(discNumber = it.filter(Char::isDigit).take(3)) },
                        label = { Text(localText("Disc #")) },
                        isError = validation.fieldErrors.containsKey("discNumber"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = draft.discTotal,
                        onValueChange = { draft = draft.copy(discTotal = it.filter(Char::isDigit).take(3)) },
                        label = { Text(localText("Discs total")) },
                        isError = validation.fieldErrors.containsKey("discTotal"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                validation.fieldErrors["discNumber"]?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.error)
                }

                // Group 3: Credits & notes
                SectionHeading(localText("Credits & Notes"))
                OutlinedTextField(
                    value = draft.composer,
                    onValueChange = { draft = draft.copy(composer = it) },
                    label = { Text(localText("Composer")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = draft.comment,
                    onValueChange = { draft = draft.copy(comment = it) },
                    label = { Text(localText("Comment")) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = localText("Edits are backed up and verified before commit. Existing lyrics, ReplayGain tags, and audio data are preserved on supported containers; legacy ID3v2.2 and Ogg/Opus artwork edits are rejected safely."),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.inkSecondary
                )
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            containerColor = tokens.surfaceElevated,
            titleContentColor = tokens.inkPrimary,
            textContentColor = tokens.inkSecondary,
            title = { Text(localText("Discard unsaved changes?")) },
            text = { Text(localText("Your edits to this track's metadata have not been saved.")) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    onDismiss()
                }) {
                    Text(localText("Discard"), color = tokens.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(localText("Keep editing"))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchMetadataEditorSheet(
    state: BatchMetadataEditorUiState,
    onPrepareArtwork: suspend (Uri, Boolean) -> Pair<ByteArray, String>?,
    onApplyBatch: (BatchMetadataPatch) -> Unit,
    onCancelBatch: () -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = HiFiTheme.colors
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var applyArtist by remember { mutableStateOf(false) }
    var artist by remember { mutableStateOf("") }
    var applyAlbumArtist by remember { mutableStateOf(false) }
    var albumArtist by remember { mutableStateOf("") }
    var applyAlbum by remember { mutableStateOf(false) }
    var album by remember { mutableStateOf("") }
    var applyGenre by remember { mutableStateOf(false) }
    var genre by remember { mutableStateOf("") }
    var applyYear by remember { mutableStateOf(false) }
    var year by remember { mutableStateOf("") }
    var applyComposer by remember { mutableStateOf(false) }
    var composer by remember { mutableStateOf("") }
    var applyComment by remember { mutableStateOf(false) }
    var comment by remember { mutableStateOf("") }
    var artworkBytes by remember { mutableStateOf<ByteArray?>(null) }
    var artworkMime by remember { mutableStateOf("image/jpeg") }
    var replaceArtwork by remember { mutableStateOf(false) }
    var removeArtwork by remember { mutableStateOf(false) }

    val patch = remember(
        applyArtist, artist, applyAlbumArtist, albumArtist, applyAlbum, album,
        applyGenre, genre, applyYear, year, applyComposer, composer, applyComment, comment,
        artworkBytes, artworkMime, replaceArtwork, removeArtwork
    ) {
        BatchMetadataPatch(
            artist = if (applyArtist) artist else null,
            albumArtist = if (applyAlbumArtist) albumArtist else null,
            album = if (applyAlbum) album else null,
            genre = if (applyGenre) genre else null,
            year = if (applyYear) year else null,
            composer = if (applyComposer) composer else null,
            comment = if (applyComment) comment else null,
            artworkBytes = if (replaceArtwork) artworkBytes else null,
            artworkMimeType = artworkMime,
            replaceArtwork = replaceArtwork && artworkBytes != null,
            removeArtwork = removeArtwork
        )
    }
    val validation = remember(patch) { MetadataValidator.validateBatch(patch) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val prepared = onPrepareArtwork(uri, true)
                if (prepared != null) {
                    artworkBytes = prepared.first
                    artworkMime = prepared.second
                    replaceArtwork = true
                    removeArtwork = false
                }
            }
        }
    }

    val batchAccent = if (tokens.isDark) tokens.accentBright else tokens.accent

    ModalBottomSheet(
        onDismissRequest = { if (!state.isSaving) onDismiss() },
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = localText("Batch Edit Metadata"),
                        style = MaterialTheme.typography.titleLarge,
                        color = tokens.inkPrimary
                    )
                    Text(
                        text = "${state.tracks.size} ${localText("track(s) selected")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.inkSecondary
                    )
                }
                if (state.isSaving) {
                    TextButton(onClick = onCancelBatch) {
                        Text(localText("Stop"), color = tokens.error)
                    }
                } else {
                    TextButton(onClick = onDismiss) {
                        Text(localText("Close"), color = tokens.inkSecondary)
                    }
                }
            }

            if (state.isSaving) {
                val progress = if (state.totalCount > 0) state.completedCount.toFloat() / state.totalCount else 0f
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = batchAccent)
                Text(
                    text = "${state.completedCount}/${state.totalCount} · ${state.currentTrackTitle}",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            state.result?.let { res ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(tokens.glassSurface.copy(alpha = if (tokens.isDark) 0.7f else 0.96f))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "${res.succeededCount} ${localText("succeeded")} · ${res.failedCount} ${localText("failed")}",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (res.failedCount == 0) tokens.success else batchAccent
                    )
                    res.trackResults.take(12).forEach { (track, writeRes) ->
                        val ok = writeRes is MetadataWriteResult.Success
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = if (ok) tokens.success else tokens.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = track.title + if (!ok) " — ${describeFailure(writeRes)}" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.inkPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            if (!state.isSaving && state.result == null) {
                Text(
                    text = localText("Only checked fields are updated. Unchecked fields, song titles, track numbers, lyrics, and ReplayGain tags remain untouched."),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.inkSecondary
                )

                BatchFieldRow(applyArtist, { applyArtist = it }, localText("Artist"), artist, { artist = it; applyArtist = true })
                BatchFieldRow(applyAlbumArtist, { applyAlbumArtist = it }, localText("Album artist"), albumArtist, { albumArtist = it; applyAlbumArtist = true })
                BatchFieldRow(applyAlbum, { applyAlbum = it }, localText("Album"), album, { album = it; applyAlbum = true })
                BatchFieldRow(applyGenre, { applyGenre = it }, localText("Genre"), genre, { genre = it; applyGenre = true })
                BatchFieldRow(applyYear, { applyYear = it }, localText("Year / Date"), year, { year = it.take(10); applyYear = true })
                validation.fieldErrors["year"]?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.error) }
                BatchFieldRow(applyComposer, { applyComposer = it }, localText("Composer"), composer, { composer = it; applyComposer = true })
                BatchFieldRow(applyComment, { applyComment = it }, localText("Comment"), comment, { comment = it; applyComment = true })

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    HiFiPillActionButton(
                        label = if (replaceArtwork) localText("Artwork selected ✓") else localText("Set shared artwork"),
                        icon = Icons.Rounded.Image,
                        onClick = {
                            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        primary = false
                    )
                    TextButton(onClick = {
                        removeArtwork = !removeArtwork
                        if (removeArtwork) replaceArtwork = false
                    }) {
                        Text(
                            text = if (removeArtwork) localText("Will remove artwork") else localText("Remove artwork"),
                            color = if (removeArtwork) tokens.error else tokens.inkSecondary
                        )
                    }
                }

                HorizontalDivider(thickness = 0.5.dp, color = tokens.divider)
                Text(
                    text = localText("Affected tracks preview:") + " " + state.tracks.take(4).joinToString(", ") { it.title } +
                        if (state.tracks.size > 4) " +${state.tracks.size - 4} more" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.inkSecondary
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    HiFiPillActionButton(
                        label = "${localText("Apply to")} ${state.tracks.size}",
                        onClick = { onApplyBatch(patch) },
                        enabled = validation.isValid && patch.hasAnyChange,
                        primary = true
                    )
                }
            }
        }
    }
}

@Composable
private fun BatchFieldRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SectionHeading(text: String) {
    val tokens = HiFiTheme.colors
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        color = if (tokens.isDark) tokens.accentBright else tokens.accent,
        modifier = Modifier.padding(top = 4.dp)
    )
}

private fun describeFailure(res: MetadataWriteResult): String = when (res) {
    is MetadataWriteResult.Failed -> res.reason
    is MetadataWriteResult.Unsupported -> res.reason
    is MetadataWriteResult.Conflict -> res.reason
    is MetadataWriteResult.RequiresWritePermission -> res.message
    is MetadataWriteResult.Success -> res.detail
}
