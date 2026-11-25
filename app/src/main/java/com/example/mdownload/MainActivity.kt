package com.example.mdownload

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.example.mdownload.ui.MDownloadRoot
import com.example.mdownload.ui.theme.MDownloadTheme

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SHARED_TEXT = "com.example.mdownload.EXTRA_SHARED_TEXT"
        const val EXTRA_OPEN_PLAYLIST_URL = "com.example.mdownload.EXTRA_OPEN_PLAYLIST_URL"
        const val EXTRA_OPEN_NOW_PLAYING = "com.example.mdownload.EXTRA_OPEN_NOW_PLAYING"
    }

    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.onAudioPermissionChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        requestRecommendedPermissions()
        handleIncomingIntent(intent)

        setContent {
            val settings by viewModel.settings.collectAsState()
            val dark = when (settings.themeMode) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }

            LaunchedEffect(dark) {
                if (dark) {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                        navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
                    )
                } else {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                        navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    )
                }
            }

            MDownloadTheme(darkTheme = dark) {
                MDownloadRoot(
                    viewModel = viewModel,
                    onRequestAudioPermission = {
                        permissionLauncher.launch(arrayOf(Manifest.permission.READ_MEDIA_AUDIO))
                    }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val app = application as MDownloadApp
        app.queueManager.setAppVisible(true)
        app.libraryRepository.checkAudioPermission()
        app.connectPlaybackAfterHydration()
    }

    override fun onStop() {
        (application as MDownloadApp).queueManager.setAppVisible(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_OPEN_NOW_PLAYING, false)) {
            viewModel.openNowPlaying()
            intent.removeExtra(EXTRA_OPEN_NOW_PLAYING)
        }
        val playlistUrl = intent.getStringExtra(EXTRA_OPEN_PLAYLIST_URL).orEmpty()
        if (playlistUrl.isNotBlank()) {
            viewModel.openSharedPlaylist(playlistUrl)
            intent.removeExtra(EXTRA_OPEN_PLAYLIST_URL)
            return
        }
        val handoffText = intent.getStringExtra(EXTRA_SHARED_TEXT).orEmpty()
        if (handoffText.isNotBlank()) {
            viewModel.onSharedTextReceived(handoffText)
            intent.removeExtra(EXTRA_SHARED_TEXT)
            return
        }
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            if (sharedText.isNotBlank()) {
                viewModel.onSharedTextReceived(sharedText)
            }
        }
    }

    private fun requestRecommendedPermissions() {
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}
