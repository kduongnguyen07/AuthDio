package com.example.mdownload.core

object DownloadPolicy {
    fun waitReason(wifiOnly:Boolean, allowBackground:Boolean, appVisible:Boolean, online:Boolean, wifi:Boolean):String = when {
        !allowBackground && !appVisible -> "Waiting for the app to return to foreground"
        !online -> "Waiting for a network connection"
        wifiOnly && !wifi -> "Waiting for Wi-Fi"
        else -> ""
    }
}
