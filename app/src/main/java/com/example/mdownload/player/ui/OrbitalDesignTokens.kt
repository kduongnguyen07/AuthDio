package com.example.mdownload.player.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Dedicated visual & motion tokens derived directly from the two Orbital Player references:
 * - Reference 1: Near-black void with restrained deep-navy upper/lower washes, dark concentric bezel,
 *   ice-blue circular progress ring, solid white Play/Pause orb, compact Next Songs preview.
 * - Reference 2: Dark canvas with sweeping left-bowed luminous slate-navy planetary dome,
 *   delicate cool-blue curved orbital path, focal bezel right-of-center, and left-anchored song label.
 */
object OrbitalDesignTokens {

    object Colors {
        // Canvas & subtle deep-navy ambient lighting (Reference 1)
        val BackgroundVoid = Color(0xFF04060C)
        val BackgroundDeep = Color(0xFF070A14)
        val AmbientNavyTop = Color(0xFF283350)
        val AmbientNavyTopMid = Color(0xFF151C2F)
        val AmbientNavyBottom = Color(0xFF1E2740)
        val AmbientNavyBottomMid = Color(0xFF111626)

        // Sweeping planetary dome & curved orbit arc (Reference 2)
        val OrbitDomeOuter = Color(0xFF2B3552)
        val OrbitDomeMid = Color(0xFF161D30)
        val OrbitDomeInner = Color(0xFF05070E)
        val OrbitArcCore = Color(0xFF8DA3CC)
        val OrbitArcGlow = Color(0xFF5674B8)

        // Physical circular bezel & depth rings (Reference 1 & 2)
        val BezelOuter = Color(0xFF131722)
        val BezelMid = Color(0xFF1B2132)
        val BezelInnerChannel = Color(0xFF0E121C)
        val BezelRimBright = Color(0xFFA7B8DB)
        val BezelRimSoft = Color(0xFF3E4A66)
        val BezelRimDark = Color(0xFF1A2030)
        val ArtworkInnerRim = Color(0xFF5B6B8C)

        // Circular playback progress (Reference 1)
        val ProgressGroove = Color(0xFF283147)
        val ProgressArcStart = Color(0xFF6B95FF)
        val ProgressArcEnd = Color(0xFFBBD1FF)
        val ProgressArcGlow = Color(0xFF5A84F5)

        // Press-hold feedback halo on central artwork (Reference 1)
        val HoldHaloFill = Color(0x28DCE6FF)
        val HoldHaloStroke = Color(0xD8EEF3FF)

        // Text & icons
        val TextPrimary = Color(0xFFF3F6FC)
        val TextSecondary = Color(0xFF9AA4BC)
        val TextMuted = Color(0xFF69748D)
        val IconPrimary = Color(0xFFF2F5FC)
        val IconInactive = Color(0xB8DDE3F2)
        val IconAccent = Color(0xFF8CB0FF)
        val FavoriteActive = Color(0xFFFF6B8B)

        // Signature solid white circular play/pause button (Reference 1)
        val PlayButtonSurface = Color(0xFFFFFFFF)
        val PlayButtonIcon = Color(0xFF060911)

        // Restrained dismiss / utility controls
        val CloseCircleSurface = Color(0xCC1B2235)
        val CloseCircleBorder = Color(0x668DA0C8)
        val NextSongCardSurface = Color(0x33161D30)
        val HairlineDivider = Color(0x1FA6B6DC)
    }

    object Typography {
        val HeaderTitle = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Light,
            fontSize = 30.sp,
            lineHeight = 36.sp,
            letterSpacing = (-0.5).sp
        )

        val HeaderArtist = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.1.sp
        )

        val OrbitalSelectedTitle = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Light,
            fontSize = 23.sp,
            lineHeight = 28.sp,
            letterSpacing = (-0.3).sp
        )

        val OrbitalSelectedArtist = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Normal,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.15.sp
        )

        val OrbitalCaption = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            letterSpacing = 1.1.sp
        )

        val ProgressTime = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.4.sp,
            fontFeatureSettings = "tnum"
        )

        val NextSongsHeading = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Normal,
            fontSize = 17.sp,
            lineHeight = 22.sp,
            letterSpacing = (-0.1).sp
        )

        val NextSongTitle = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Normal,
            fontSize = 14.5.sp,
            lineHeight = 19.sp
        )

        val NextSongMeta = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontFeatureSettings = "tnum"
        )
    }

    object Sizing {
        val NormalDiscMax = 312.dp
        val NormalDiscMin = 216.dp
        val BezelOuterStroke = 1.15.dp
        val ProgressGrooveWidth = 1.6.dp
        val ProgressActiveWidth = 2.6.dp
        val PlayButtonDiameter = 64.dp
        val SkipButtonSize = 48.dp
        val ModeButtonSize = 48.dp
        val NextSongArtwork = 44.dp
        val NextSongCorner = 12.dp
        val CloseButtonDiameter = 40.dp
    }

    object Spacing {
        val ScreenHorizontal = 24.dp
        val TopChromeHorizontal = 10.dp
        val SectionGap = 16.dp
        val LabelSafeGap = 14.dp
    }

    object Motion {
        const val LABEL_TRANSITION_MS = 190
        const val PLAY_ICON_MS = 150
        const val HOLD_FADE_MS = 160

        val OrbitMorphSpring = spring<Float>(
            dampingRatio = 0.84f,
            stiffness = 340f
        )

        val OrbitSnapSpring = spring<Float>(
            dampingRatio = 0.80f,
            stiffness = 420f
        )

        val SelectionFadeSpec = tween<Float>(durationMillis = LABEL_TRANSITION_MS)
        val StandardSpring = spring<Float>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        )
    }
}
