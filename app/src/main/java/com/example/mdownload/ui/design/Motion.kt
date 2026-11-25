package com.example.mdownload.ui.design

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color

object HiFiMotion {
    const val AURA_TINT_MS = 650
    const val SCREEN_TRANSITION_MS = 260
    const val MINI_PLAYER_MS = 240
    const val PLAY_PAUSE_MS = 180

    val auraColorSpec = tween<Color>(durationMillis = AURA_TINT_MS, easing = FastOutSlowInEasing)
    val quickFadeSpec = tween<Float>(durationMillis = PLAY_PAUSE_MS, easing = FastOutSlowInEasing)
    val dockSpringFloat = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
}
