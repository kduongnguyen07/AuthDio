package com.example.mdownload.core

/**
 * Readable recovery guidance alongside the original diagnostic message,
 * ported from HiFi Deck task_feedback.py.
 */
object TaskFeedback {

    private val BLOCK_429_REGEX = Regex(
        """\b(?:http(?:\s+error)?\s*[:=]?\s*429|too many requests)\b""",
        RegexOption.IGNORE_CASE
    )

    fun isYoutubeBlocked(message: String): Boolean {
        val text = message.lowercase()
        if (BLOCK_429_REGEX.containsMatchIn(text)) return true
        return "not a bot" in text && listOf("confirm", "verify", "verification").any { it in text }
    }

    fun recoveryHint(record: HistoryRecord): String {
        val detail = record.detail.lowercase()
        if (record.status == JobStatus.CANCELLED) {
            return "Download cancelled. Queue this track again when you are ready."
        }
        if (record.status == JobStatus.SKIPPED) {
            return "Verified audio was already downloaded for this profile. Disable duplicate skipping in Settings to re-download."
        }
        if (listOf("429", "too many requests", "not a bot").any { it in detail }) {
            return "YouTube is temporarily limiting requests from this network. Pause repeated retries, keep Gentle mode enabled, and try again later."
        }
        if ("ffmpeg" in detail || "ffprobe" in detail) {
            return "FFmpeg conversion failed. Try Original audio format or restart the app to re-initialize FFmpeg."
        }
        if (listOf("permission", "access is denied", "no space", "enospc").any { it in detail }) {
            return "Check free device storage space and Media permissions, then retry."
        }
        if (listOf("private", "unavailable", "removed", "not available", "sign in").any { it in detail }) {
            return "This track may be private, age-restricted, or unavailable. Check the link in Discover before retrying."
        }
        if (listOf("getaddrinfo", "name resolution", "failed to resolve", "unable to resolve host").any { it in detail }) {
            return "Could not resolve YouTube host. Check your Wi-Fi or mobile data connection, then retry."
        }
        if (listOf("403", "timed out", "connection", "network").any { it in detail }) {
            return "Temporary YouTube or network error. Try updating yt-dlp in Settings or retrying shortly."
        }
        if ("no audio file" in detail || "no valid audio" in detail) {
            return "No playable audio was produced. If a previous file was deleted, turn off Skip duplicates in Settings and retry."
        }
        return "Queue this track again to retry, or inspect Diagnostics in Settings for the full log."
    }
}
