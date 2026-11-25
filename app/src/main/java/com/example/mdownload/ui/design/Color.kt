package com.example.mdownload.ui.design

import android.graphics.Bitmap
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils

/**
 * Centralized Obsidian Orchid (Dark) and Pearl Orchid (Light) color tokens
 * derived from the Dribbble visual reference.
 */
@Immutable
data class HiFiColorTokens(
    val isDark: Boolean,
    val canvas: Color,
    val canvasDeep: Color,
    val auraPeak: Color,
    val auraMid: Color,
    val auraFade: Color,
    val glassSurface: Color,
    val glassElevated: Color,
    val surfaceElevated: Color,
    val glassPill: Color,
    val glassRimTop: Color,
    val glassRimBottom: Color,
    val accent: Color,
    val accentBright: Color,
    val accentSoft: Color,
    val progressArcStart: Color,
    val progressArcEnd: Color,
    val progressTrack: Color,
    val progressWash: Color,
    val inkPrimary: Color,
    val inkSecondary: Color,
    val inkTertiary: Color,
    val onAccent: Color,
    val divider: Color,
    val success: Color,
    val successContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val error: Color,
    val errorContainer: Color
)

val ObsidianOrchidColors = HiFiColorTokens(
    isDark = true,
    canvas = Color(0xFF050508),
    canvasDeep = Color(0xFF0D0A14),
    auraPeak = Color(0xFF9657A8),
    auraMid = Color(0xFF5B2D70),
    auraFade = Color(0xFF1C0D26),
    glassSurface = Color(0xE014101E),
    glassElevated = Color(0xEB1F192E),
    surfaceElevated = Color(0xFF1B1726),
    glassPill = Color(0xD9191426),
    glassRimTop = Color(0x36FFFFFF),
    glassRimBottom = Color(0x10FFFFFF),
    accent = Color(0xFFC78BFF),
    accentBright = Color(0xFFE3C4FF),
    accentSoft = Color(0x2EC78BFF),
    progressArcStart = Color(0xFF7EB4FF),
    progressArcEnd = Color(0xFFD5BAFF),
    progressTrack = Color(0x1FFFFFFF),
    progressWash = Color(0x5264498F),
    inkPrimary = Color(0xFFF8F7FB),
    inkSecondary = Color(0xFF9E95B0),
    inkTertiary = Color(0xFF6B627D),
    onAccent = Color(0xFF14091E),
    divider = Color(0x16FFFFFF),
    success = Color(0xFF62D8A0),
    successContainer = Color(0x2462D8A0),
    warning = Color(0xFFF3C36B),
    warningContainer = Color(0x26F3C36B),
    error = Color(0xFFFF7B94),
    errorContainer = Color(0x26FF7B94)
)

val PearlOrchidColors = HiFiColorTokens(
    isDark = false,
    canvas = Color(0xFFF7F5FA),
    canvasDeep = Color(0xFFEEE9F5),
    auraPeak = Color(0xFFDEC3EB),
    auraMid = Color(0xFFEEDFF6),
    auraFade = Color(0xFFF7F5FA),
    glassSurface = Color(0xE6FFFFFF),
    glassElevated = Color(0xF5F4EFFB),
    surfaceElevated = Color(0xFFFAF8FD),
    glassPill = Color(0xEBFFFFFF),
    glassRimTop = Color(0x99FFFFFF),
    glassRimBottom = Color(0x227D44B8),
    accent = Color(0xFF7B44B6),
    accentBright = Color(0xFF975FD4),
    accentSoft = Color(0x1F7B44B6),
    progressArcStart = Color(0xFF5A8DEE),
    progressArcEnd = Color(0xFF8B56CC),
    progressTrack = Color(0x1E231833),
    progressWash = Color(0x2E8C62C4),
    inkPrimary = Color(0xFF181223),
    inkSecondary = Color(0xFF655B75),
    inkTertiary = Color(0xFF8F85A0),
    onAccent = Color(0xFFFFFFFF),
    divider = Color(0x17201433),
    success = Color(0xFF217A50),
    successContainer = Color(0x1F217A50),
    warning = Color(0xFF8F6214),
    warningContainer = Color(0x218F6214),
    error = Color(0xFFC23650),
    errorContainer = Color(0x1FC23650)
)

/**
 * Extracts a restrained atmospheric glow tint from album artwork without extra libraries.
 * Blends 45% toward the signature orchid hue and clamps lightness so readability never degrades.
 */
fun extractArtworkAuraColor(bitmap: Bitmap?, fallback: Color, isDark: Boolean): Color {
    if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
        return fallback
    }
    return runCatching {
        val stepX = (bitmap.width / 12).coerceAtLeast(1)
        val stepY = (bitmap.height / 12).coerceAtLeast(1)
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var weightSum = 0L
        val hsl = FloatArray(3)

        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val px = bitmap.getPixel(x, y)
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                ColorUtils.RGBToHSL(r, g, b, hsl)
                val sat = hsl[1]
                val lum = hsl[2]
                if (lum in 0.14f..0.85f) {
                    val weight = (1 + (sat * 6f).toInt()).toLong()
                    rSum += r * weight
                    gSum += g * weight
                    bSum += b * weight
                    weightSum += weight
                }
                x += stepX
            }
            y += stepY
        }
        if (weightSum <= 0L) return fallback
        val avgR = (rSum / weightSum).toInt().coerceIn(0, 255)
        val avgG = (gSum / weightSum).toInt().coerceIn(0, 255)
        val avgB = (bSum / weightSum).toInt().coerceIn(0, 255)
        val sampledArgb = (0xFF shl 24) or (avgR shl 16) or (avgG shl 8) or avgB
        val blended = ColorUtils.blendARGB(sampledArgb, fallback.toArgb(), 0.45f)
        ColorUtils.colorToHSL(blended, hsl)
        hsl[1] = hsl[1].coerceIn(0.28f, 0.56f)
        hsl[2] = if (isDark) hsl[2].coerceIn(0.36f, 0.50f) else hsl[2].coerceIn(0.76f, 0.86f)
        Color(ColorUtils.HSLToColor(hsl))
    }.getOrDefault(fallback)
}
