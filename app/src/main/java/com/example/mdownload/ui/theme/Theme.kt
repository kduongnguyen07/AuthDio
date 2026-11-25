package com.example.mdownload.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.example.mdownload.ui.design.HiFiDeckTheme

@Composable
fun MDownloadTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    HiFiDeckTheme(darkTheme = darkTheme, content = content)
}
