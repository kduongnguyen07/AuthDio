package com.example.mdownload
import org.junit.Test
import org.junit.Assert.*
import com.example.mdownload.core.DownloadPolicy
import com.example.mdownload.ui.translateText

class SettingsPolicyTest {
    @Test fun wifiOnlyBlocksMobileAndResumesOnWifi() {
        assertEquals("Waiting for Wi-Fi", DownloadPolicy.waitReason(true,true,true,true,false))
        assertEquals("", DownloadPolicy.waitReason(true,true,true,true,true))
    }
    @Test fun backgroundSettingDoesNotBlockForeground() {
        assertEquals("Waiting for the app to return to foreground", DownloadPolicy.waitReason(false,false,false,true,true))
        assertEquals("", DownloadPolicy.waitReason(false,false,true,true,true))
    }
    @Test fun offlineAlwaysWaits() {
        assertEquals("Waiting for a network connection", DownloadPolicy.waitReason(false,true,true,false,false))
        assertEquals("", DownloadPolicy.waitReason(false,true,false,true,false))
    }
    @Test fun localizationPreservesTitlesAndTranslatesQueueCounts() {
        assertEquals("Cài đặt", translateText("Settings",true))
        assertEquals("Hàng đợi (3)", translateText("Queue (3)",true))
        assertEquals("Rihanna - Umbrella",translateText("Rihanna - Umbrella",true))
        assertEquals("Settings",translateText("Settings",false))
    }
}
