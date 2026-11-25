package com.example.mdownload

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.mdownload.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Opt in: -e liveDownload true. Saves a real track to Music/MDownload. */
@RunWith(AndroidJUnit4::class)
class LiveDownloadTest {
    @Test fun downloadExportAndPlayback(): Unit = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveDownload") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MDownloadApp
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(withTimeout(30000) { app.engine.awaitReady() })
        assertTrue(app.engine.state.value.toString(), (app.engine.state.value as EngineState.Ready).ytDlpVersion >= "2026.10.26")
        check(app.queueManager.jobs.value.isEmpty()) { "Refusing to start unrelated queued tracks" }
        val job = DownloadJob(url="https://www.youtube.com/watch?v=CvBfHwUxHIk", title="Android live verification", options=JobOptions(skipDuplicates=false))
        app.queueManager.enqueueTracks(listOf(job), autoStart=true)
        val result = withTimeout(180000) {
            while (app.queueManager.history.value.none { it.id == job.id }) delay(250)
            app.queueManager.history.value.first { it.id == job.id }
        }
        assertEquals(result.detail, JobStatus.DONE, result.status)
        assertTrue(result.savedUri.isNotBlank())
        val track = app.exporter.scanLibraryTracks().first { it.uri == result.savedUri }
        assertTrue(track.sizeBytes > 1024)
        assertTrue(track.durationMs > 1000)
        instrumentation.runOnMainSync { app.playbackController.playTrack(track) }
        try {
            withTimeout(15000) { while (!app.playbackController.state.value.isPlaying) delay(100) }
            withTimeout(10000) { while (app.playbackController.state.value.positionMs < 1000) delay(100) }
        } finally { instrumentation.runOnMainSync { app.playbackController.stop() } }
        android.util.Log.i("MDownloadLiveTest", "PASS engine=${app.engine.state.value} uri=${result.savedUri} bytes=${track.sizeBytes} duration=${track.durationMs}")
    }
}
