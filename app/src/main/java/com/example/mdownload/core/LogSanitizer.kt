package com.example.mdownload.core

/**
 * Sanitizes and bounds diagnostic log lines before persisting to `settings.log` or surfacing
 * subprocess error summaries, while preserving actionable error details.
 *
 * Redacts:
 * - Sensitive URL query parameters (`token`, `sig`, `signature`, `key`, `auth`, `cookie`, etc.).
 * - Embedded URL userinfo credentials (`https://user:pass@host`).
 * - `Authorization` / `Cookie` / `Set-Cookie` header values.
 * - Control characters / NUL bytes and unbounded payloads.
 */
object LogSanitizer {

    const val MAX_LOG_LINE_LENGTH = 1024

    private val URL_CREDENTIALS_REGEX = Regex(
        """(https?://)([^/\s@]+)@""",
        RegexOption.IGNORE_CASE
    )

    private val SENSITIVE_QUERY_PARAM_REGEX = Regex(
        """([?&](?:access_token|refresh_token|id_token|token|auth|authorization|key|api_key|apikey|sig|signature|s|lsig|cookie|cookies|session|session_id|sessionid|password|passwd|pwd|secret|jwt|bearer|x-amz-signature|x-amz-credential|x-amz-security-token|x-goog-signature)=)([^&#\s"']*)""",
        RegexOption.IGNORE_CASE
    )

    private val SENSITIVE_HEADER_REGEX = Regex(
        """((?:authorization|cookie|set-cookie|x-api-key)\s*:\s*)([^\r\n]+)""",
        RegexOption.IGNORE_CASE
    )

    private val CONTROL_CHARS_REGEX = Regex("""[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]""")

    fun sanitize(raw: String, maxLength: Int = MAX_LOG_LINE_LENGTH): String {
        if (raw.isEmpty()) return ""
        val clean = CONTROL_CHARS_REGEX.replace(raw, "")
            .replace('\r', ' ')
            .replace('\n', ' ')
        val noCreds = URL_CREDENTIALS_REGEX.replace(clean, "$1<REDACTED>@")
        val noQuerySecrets = SENSITIVE_QUERY_PARAM_REGEX.replace(noCreds, "$1<REDACTED>")
        val noHeaders = SENSITIVE_HEADER_REGEX.replace(noQuerySecrets, "$1<REDACTED>")
        return if (noHeaders.length > maxLength) {
            noHeaders.take(maxLength - 1) + "…"
        } else {
            noHeaders
        }
    }
}
