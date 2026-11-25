package com.example.mdownload

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import androidx.lifecycle.ViewModelProvider
import com.example.mdownload.core.*
import com.example.mdownload.engine.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SettingsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun isolated():Context {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        return object:ContextWrapper(base) {
            private val folder=File(base.cacheDir,"test-settings-${UUID.randomUUID()}").apply{mkdirs()}
            override fun getFilesDir():File=folder
            override fun getCacheDir():File=File(folder,"cache").apply{mkdirs()}
        }
    }
    @Test fun persistenceAndCleanupProtectPendingAudio() {
        val context=isolated()
        try {
            val store=SettingsStore(context)
            val settings=AppSettings(wifiOnly=true,allowBackground=false,themeMode="dark",language="vi",autoUpdate=true,lastUpdateCheck="2026-11-23T00:00:00Z",lastUpdateResult="Checked")
            store.save(PersistedState(settings=settings))
            assertEquals(settings,store.load().settings)
            val tools=StorageTools(context)
            val job=DownloadJob(url="https://youtu.be/CvBfHwUxHIk",title="Retained fixture")
            val source=File(tools.workRoot,"${job.id}/fixture.mp3").apply{parentFile!!.mkdirs();writeBytes(ByteArray(2048))}
            tools.retain(job,source)
            File(context.cacheDir,"unneeded.tmp").writeBytes(ByteArray(100))
            assertEquals(1,tools.snapshot().pending.size)
            tools.clearTemporary()
            assertTrue(source.isFile)
            assertFalse(File(context.cacheDir,"unneeded.tmp").exists())
            assertTrue(runCatching {tools.retry(job.id,MediaStoreExporter(context))}.isFailure)
            assertTrue(source.isFile)
            assertTrue(runCatching {tools.discard("../escape")}.isFailure)
            tools.discard(job.id)
            assertTrue(tools.snapshot().pending.isEmpty())
        } finally {context.filesDir.deleteRecursively()}
    }
    @Test fun storageCanRecoverAndVerifyRealAudio() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveDownload")=="true")
        val context=isolated()
        val exporter=MediaStoreExporter(context)
        var exported:String?=null
        try {
            val existing=exporter.scanLibraryTracks().firstOrNull()
            org.junit.Assume.assumeNotNull(existing)
            val tools=StorageTools(context)
            val job=DownloadJob(url="https://youtu.be/settingsQA1",title="MDownload recovery test",options=JobOptions(skipDuplicates=false))
            val source=File(tools.workRoot,"${job.id}/fixture.mp3").apply {parentFile!!.mkdirs()}
            context.contentResolver.openInputStream(android.net.Uri.parse(existing!!.uri))!!.use {input->source.outputStream().use{input.copyTo(it)}}
            tools.retain(job,source)
            val result=tools.retry(job.id,exporter);exported=result.uri
            assertTrue(result.sizeBytes>1024)
            assertFalse(source.exists())
            assertTrue(exporter.scanLibraryTracks().any{it.uri==result.uri})
        } finally {exported?.let{exporter.deleteTrack(it)};context.filesDir.deleteRecursively()}
    }
    @Test fun settingsSectionsThemeAndConfirmation() {
        val vm=ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val original=vm.settings.value
        try {
            compose.runOnIdle { vm.selectTab(AppTab.SETTINGS) }
            compose.onNodeWithTag("settings-list").assertExists()
            compose.onNodeWithTag("settings-section-appearance").performScrollTo().performClick()
            compose.runOnIdle {vm.updatePreferences{it.copy(themeMode="dark")}}
            compose.onNodeWithText("Theme").performScrollTo()
            compose.waitForIdle();capture("settings-dark.png")
            compose.runOnIdle {vm.updatePreferences{it.copy(themeMode="light")}}
            compose.waitForIdle();capture("settings-light.png")
            compose.onNodeWithTag("settings-section-support").performScrollTo().performClick()
            compose.onNodeWithText("Reset preferences").performScrollTo().performClick()
            compose.onNodeWithText("Cancel").performClick()
            compose.runOnIdle {assertEquals("light",vm.settings.value.themeMode)}
            compose.runOnIdle {vm.setLanguage("vi")}
            compose.waitUntil(10000) {compose.onAllNodesWithText("Cài đặt").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("settings-section-appearance").performScrollTo()
            compose.waitForIdle();capture("settings-vietnamese.png")
        } finally {
            compose.runOnIdle {vm.updatePreferences{original};vm.setLanguage(original.language)}
        }
    }
    private fun capture(name:String) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val output=File(context.filesDir,"settings-validation/$name").apply{parentFile!!.mkdirs()}
        compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,output.outputStream())
    }
}
