package com.example.mdownload.ui.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

val LocalHiFiColors = staticCompositionLocalOf { ObsidianOrchidColors }

object HiFiTheme {
    val colors: HiFiColorTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalHiFiColors.current
}

private fun HiFiColorTokens.toMaterialColorScheme() = if (isDark) {
    darkColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSoft,
        onPrimaryContainer = inkPrimary,
        secondary = progressArcStart,
        onSecondary = onAccent,
        secondaryContainer = glassElevated,
        onSecondaryContainer = inkPrimary,
        tertiary = success,
        onTertiary = onAccent,
        background = canvas,
        onBackground = inkPrimary,
        surface = canvasDeep,
        onSurface = inkPrimary,
        surfaceVariant = glassElevated,
        onSurfaceVariant = inkSecondary,
        surfaceContainerLowest = canvas,
        surfaceContainerLow = canvasDeep,
        surfaceContainer = glassSurface,
        surfaceContainerHigh = glassElevated,
        surfaceContainerHighest = glassElevated,
        outline = glassRimTop,
        outlineVariant = divider,
        error = error,
        onError = onAccent,
        errorContainer = errorContainer,
        onErrorContainer = error
    )
} else {
    lightColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSoft,
        onPrimaryContainer = inkPrimary,
        secondary = progressArcStart,
        onSecondary = onAccent,
        secondaryContainer = glassElevated,
        onSecondaryContainer = inkPrimary,
        tertiary = success,
        onTertiary = onAccent,
        background = canvas,
        onBackground = inkPrimary,
        surface = glassSurface,
        onSurface = inkPrimary,
        surfaceVariant = canvasDeep,
        onSurfaceVariant = inkSecondary,
        surfaceContainerLowest = canvas,
        surfaceContainerLow = canvasDeep,
        surfaceContainer = glassSurface,
        surfaceContainerHigh = glassElevated,
        surfaceContainerHighest = glassElevated,
        outline = glassRimBottom,
        outlineVariant = divider,
        error = error,
        onError = onAccent,
        errorContainer = errorContainer,
        onErrorContainer = error
    )
}

@Composable
fun HiFiDeckTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val tokens = if (darkTheme) ObsidianOrchidColors else PearlOrchidColors
    CompositionLocalProvider(LocalHiFiColors provides tokens) {
        MaterialTheme(
            colorScheme = tokens.toMaterialColorScheme(),
            typography = HiFiTypography,
            shapes = HiFiShapes.materialShapes,
            content = content
        )
    }
}
