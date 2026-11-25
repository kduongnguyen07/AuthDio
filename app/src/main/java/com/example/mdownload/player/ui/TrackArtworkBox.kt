package com.example.mdownload.player.ui

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.mdownload.ui.design.HiFiMotion
import com.example.mdownload.ui.design.HiFiTheme
import com.example.mdownload.ui.localText

/**
 * Async, memory-cached album artwork container styled for Obsidian Orchid:
 * supports both rounded-square list thumbnails and circular orbital discs.
 */
@Composable
fun TrackArtworkBox(
    uri: String,
    albumId: Long = 0L,
    highRes: Boolean = false,
    isPlayingIndicator: Boolean = false,
    cornerRadius: Dp = 14.dp,
    shape: Shape? = null,
    iconSize: Dp = 22.dp,
    showBorder: Boolean = true,
    onBitmapLoaded: ((Bitmap?) -> Unit)? = null,
    modifier: Modifier = Modifier,
    loadArtwork: suspend (uri: String, albumId: Long, highRes: Boolean) -> Bitmap?
) {
    val tokens = HiFiTheme.colors
    var bitmap by remember(uri, albumId, highRes) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(uri, albumId, highRes) {
        val loaded = if (uri.isNotBlank()) loadArtwork(uri, albumId, highRes) else null
        bitmap = loaded
        onBitmapLoaded?.invoke(loaded)
    }

    val resolvedShape = shape ?: RoundedCornerShape(cornerRadius)
    val fallbackBrush = Brush.linearGradient(
        colors = listOf(
            tokens.auraMid.copy(alpha = if (tokens.isDark) 0.72f else 0.45f),
            tokens.glassElevated
        )
    )

    Box(
        modifier = modifier
            .clip(resolvedShape)
            .background(fallbackBrush)
            .then(
                if (showBorder) {
                    Modifier.border(
                        width = 0.8.dp,
                        color = tokens.glassRimTop.copy(alpha = if (tokens.isDark) 0.25f else 0.50f),
                        shape = resolvedShape
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Crossfade(
            targetState = bitmap,
            animationSpec = tween(durationMillis = HiFiMotion.PLAY_PAUSE_MS),
            label = "artworkFade"
        ) { loaded ->
            if (loaded != null && !loaded.isRecycled) {
                Image(
                    bitmap = loaded.asImageBitmap(),
                    contentDescription = localText("Track artwork"),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlayingIndicator) Icons.Rounded.GraphicEq else Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = tokens.accentBright,
                        modifier = Modifier.size(iconSize)
                    )
                }
            }
        }
    }
}
