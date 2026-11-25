package com.example.mdownload

import com.example.mdownload.core.LogSanitizer
import com.example.mdownload.core.YouTubeUrlParser
import com.example.mdownload.core.audio.BoundedSubprocessRunner
import com.example.mdownload.core.audio.LoudnessScanner
import com.example.mdownload.engine.OutputPathGuard
import com.example.mdownload.engine.YtDlpEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

/**
 * JVM regression suite for P1 Phase A (`STAB-04`: Security & Subprocess Reliability):
 * - A1 (SEC-01): OutputPathGuard canonical containment
 * - A2 (SEC-02): YtDlpEngine Python re.sub replacement escaping
 * - A3 (AUD-08) & A4 (AUD-09): BoundedSubprocessRunner deadlock/timeout/cancellation + LoudnessScanner
 * - A5 (SEC-04): YouTubeUrlParser URL & SSRF hardening + pre-flight DNS validation
 * - A6 (SEC-03): Backup & data-extraction XML exclusions
 * - A7 (SEC-05): LogSanitizer scrubbing & line length bounding
 */
class SecurityAndSubprocessP1Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // =========================================================================
    // A1 (SEC-01): OutputPathGuard — Canonical Containment
    // =========================================================================

    @Test
    fun `SEC-01 accepts valid absolute, relative, and nested files inside workDir`() {
        val workDir = tempFolder.newFolder("job_valid")
        val directFile = File(workDir, "track.mp3").apply { writeBytes(ByteArray(2048) { 1 }) }
        val nestedDir = File(workDir, "sub").apply { mkdirs() }
        val nestedFile = File(nestedDir, "nested.flac").apply { writeBytes(ByteArray(2048) { 2 }) }

        assertEquals(
            OutputPathGuard.ValidationResult.Valid(directFile.canonicalFile),
            OutputPathGuard.validateCandidate(directFile.absolutePath, workDir)
        )
        assertEquals(
            OutputPathGuard.ValidationResult.Valid(directFile.canonicalFile),
            OutputPathGuard.validateCandidate("track.mp3", workDir)
        )
        assertEquals(
            OutputPathGuard.ValidationResult.Valid(nestedFile.canonicalFile),
            OutputPathGuard.validateCandidate("sub/nested.flac", workDir)
        )
    }

    @Test
    fun `SEC-01 rejects parent traversal, sibling prefix collision, directories, and missing files`() {
        val root = tempFolder.newFolder("jobs_root")
        val workDir = File(root, "job123").apply { mkdirs() }
        val siblingCollisionDir = File(root, "job123_backup").apply { mkdirs() }
        val outsideFile = File(root, "secret.mp3").apply { writeBytes(ByteArray(2048) { 7 }) }
        val siblingFile = File(siblingCollisionDir, "private.mp3").apply { writeBytes(ByteArray(2048) { 8 }) }
        val nestedDir = File(workDir, "subdir").apply { mkdirs() }

        val cases = listOf(
            "../secret.mp3",
            "subdir/../../secret.mp3",
            outsideFile.absolutePath,
            siblingFile.absolutePath,
            nestedDir.absolutePath,
            "does_not_exist.mp3",
            "track\u0000.mp3",
            "   "
        )
        for (candidate in cases) {
            val res = OutputPathGuard.validateCandidate(candidate, workDir)
            assertTrue("Expected Rejected for '$candidate', got $res", res is OutputPathGuard.ValidationResult.Rejected)
        }
    }

    @Test
    fun `SEC-01 rejects symlink inside workDir escaping outside`() {
        val root = tempFolder.newFolder("sym_root")
        val workDir = File(root, "job_sym").apply { mkdirs() }
        val outsideFile = File(root, "outside.mp3").apply { writeBytes(ByteArray(2048) { 9 }) }
        val symlinkPath = File(workDir, "escape.mp3").toPath()

        val created = try {
            Files.createSymbolicLink(symlinkPath, outsideFile.toPath())
            true
        } catch (_: Exception) {
            // Windows without Developer Mode / SeCreateSymbolicLinkPrivilege may throw FileSystemException.
            false
        }

        if (created) {
            val res = OutputPathGuard.validateCandidate(symlinkPath.toFile().absolutePath, workDir)
            assertTrue("Symlink escaping workDir must be rejected, got $res", res is OutputPathGuard.ValidationResult.Rejected)
            val reason = (res as OutputPathGuard.ValidationResult.Rejected).reason
            assertTrue(reason.contains("symlink", ignoreCase = true) || reason.contains("escapes", ignoreCase = true))
        }
    }

    @Test
    fun `SEC-01 resolveProducedFile rejects untrusted HF_FILE and falls back safely to workDir audio`() {
        val root = tempFolder.newFolder("resolve_root")
        val workDir = File(root, "job_a").apply { mkdirs() }
        val outsideFile = File(root, "job_a_evil.mp3").apply { writeBytes(ByteArray(4096) { 3 }) }
        val legitimateAudio = File(workDir, "real_output.mp3").apply { writeBytes(ByteArray(4096) { 4 }) }

        val rejections = mutableListOf<String>()
        val resolved = OutputPathGuard.resolveProducedFile(
            markerPath = outsideFile.absolutePath,
            stdout = "HF_FILE:${outsideFile.absolutePath}\n",
            workDir = workDir,
            onRejected = { rejections.add(it) }
        )

        assertEquals(legitimateAudio.canonicalFile, resolved)
        assertTrue("Expected untrusted marker rejection to be logged", rejections.isNotEmpty())
        assertNull(OutputPathGuard.parseMarkerLine("NOT_A_MARKER:/tmp/a.mp3"))
        assertEquals("song.mp3", OutputPathGuard.parseMarkerLine("HF_FILE: song.mp3 "))
    }

    // =========================================================================
    // A2 (SEC-02): YtDlpEngine Python re.sub Replacement Escaping
    // =========================================================================

    @Test
    fun `SEC-02 escapes Python re_sub metacharacters and anchors pattern against double replacement`() {
        val testTitles = listOf(
            """Track \1 Backref""",
            """Group \g<0> and \g<1>""",
            """Trailing backslash \""",
            """Double \\\\ slashes""",
            """Quoted "Song" & 'Remix' (Live)""",
            """Price $100 & $1""",
            """Mùa Thu Hà Nội — Bản Giao Hưởng""",
            """Night Drive 🎵🔥""",
            "A".repeat(250)
        )

        assertEquals("(?s)\\A.*\\Z", YtDlpEngine.FULL_STRING_PYTHON_REGEX)

        for (title in testTitles) {
            val cmd = YtDlpEngine.buildTitleReplaceCommand(title)
            assertEquals(4, cmd.size)
            assertEquals("--replace-in-metadata", cmd[0])
            assertEquals("title", cmd[1])
            assertEquals(YtDlpEngine.FULL_STRING_PYTHON_REGEX, cmd[2])

            val escaped = cmd[3]
            // Simulate Python re.sub replacement template parser:
            // every `\` must be paired with another `\` so no backreference/escape is ever interpreted.
            val decoded = simulatePythonReSubReplacement(escaped)
            assertEquals("Round-trip title mismatch for input: $title", title.trim(), decoded)
        }
    }

    @Test
    fun `SEC-02 strips newlines and control characters from replacement title`() {
        val escaped = YtDlpEngine.escapePythonRegexReplacement("Line1\nLine2\r\t\u0007\\1")
        assertFalse(escaped.contains('\n'))
        assertFalse(escaped.contains('\r'))
        assertFalse(escaped.contains('\u0007'))
        assertEquals("""Line1 Line2 \\1""", escaped)
    }

    private fun simulatePythonReSubReplacement(template: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < template.length) {
            val ch = template[i]
            if (ch == '\\') {
                if (i + 1 >= template.length) {
                    fail("Unescaped trailing backslash would trigger Python re.error: $template")
                }
                val next = template[i + 1]
                if (next != '\\') {
                    fail("Unescaped Python replacement sequence '\\$next' in: $template")
                }
                out.append('\\')
                i += 2
            } else {
                out.append(ch)
                i++
            }
        }
        return out.toString()
    }

    // =========================================================================
    // A3 (AUD-08) & A4 (AUD-09): BoundedSubprocessRunner & LoudnessScanner
    // =========================================================================

    @Test
    fun `AUD-08 handles normal exit, empty output, and non-zero exit cleanly`() = runBlocking {
        val ok = BoundedSubprocessRunner.run(
            command = fixtureCommand("normal"),
            timeoutMs = 5_000L,
            mergeErrorStream = false
        )
        assertTrue("Expected Success, got $ok", ok is BoundedSubprocessRunner.SubprocessResult.Success)
        ok as BoundedSubprocessRunner.SubprocessResult.Success
        assertEquals(0, ok.exitCode)
        assertTrue(ok.stdout.contains("\"codec_name\":\"flac\""))
        assertFalse(ok.stdoutTruncated)

        val empty = BoundedSubprocessRunner.run(
            command = fixtureCommand("empty"),
            timeoutMs = 5_000L,
            mergeErrorStream = false
        )
        assertTrue(empty is BoundedSubprocessRunner.SubprocessResult.Success)
        assertEquals("", (empty as BoundedSubprocessRunner.SubprocessResult.Success).stdout)

        val nonZero = BoundedSubprocessRunner.run(
            command = fixtureCommand("nonzero"),
            timeoutMs = 5_000L,
            mergeErrorStream = false
        )
        assertTrue(nonZero is BoundedSubprocessRunner.SubprocessResult.NonZeroExit)
        nonZero as BoundedSubprocessRunner.SubprocessResult.NonZeroExit
        assertEquals(42, nonZero.exitCode)
        assertTrue(nonZero.stderr.contains("Simulated failure"))
    }

    @Test
    fun `AUD-08 concurrent stdout and stderr draining prevents pipe deadlock on large 256KB streams`() = runBlocking {
        // Classic deadlock trigger: child writes 300 KB to stderr BEFORE writing JSON to stdout.
        // Without concurrent draining, a sequential stdout reader hangs forever because the OS stderr pipe fills.
        val stderrFlood = BoundedSubprocessRunner.run(
            command = fixtureCommand("large_stderr"),
            timeoutMs = 10_000L,
            mergeErrorStream = false,
            maxStdoutChars = 64 * 1024,
            maxStderrChars = 16 * 1024
        )
        assertTrue("Expected Success without deadlock, got $stderrFlood", stderrFlood is BoundedSubprocessRunner.SubprocessResult.Success)
        stderrFlood as BoundedSubprocessRunner.SubprocessResult.Success
        assertTrue(stderrFlood.stdout.contains("JSON_OK"))
        assertTrue("Expected stderr to be capped & flagged truncated", stderrFlood.stderrTruncated)
        assertTrue(stderrFlood.stderr.length <= 16 * 1024)

        // Large stdout (>256 KB) must also drain completely without OOM and flag truncation.
        val stdoutFlood = BoundedSubprocessRunner.run(
            command = fixtureCommand("large_stdout"),
            timeoutMs = 10_000L,
            mergeErrorStream = false,
            maxStdoutChars = 32 * 1024
        )
        assertTrue(stdoutFlood is BoundedSubprocessRunner.SubprocessResult.Success)
        stdoutFlood as BoundedSubprocessRunner.SubprocessResult.Success
        assertTrue(stdoutFlood.stdoutTruncated)
        assertEquals(32 * 1024, stdoutFlood.stdout.length)
    }

    @Test
    fun `AUD-08 and AUD-09 terminate hanging subprocess cleanly on timeout`() = runBlocking {
        val start = System.currentTimeMillis()
        val result = BoundedSubprocessRunner.run(
            command = fixtureCommand("hang"),
            timeoutMs = 600L,
            mergeErrorStream = true
        )
        val elapsed = System.currentTimeMillis() - start

        assertTrue("Expected TimedOut, got $result", result is BoundedSubprocessRunner.SubprocessResult.TimedOut)
        assertTrue("Timeout should return promptly (<5s), took ${elapsed}ms", elapsed < 5_000L)
    }

    @Test
    fun `AUD-08 and AUD-09 terminate subprocess promptly on coroutine cancellation`() = runBlocking {
        val start = System.currentTimeMillis()
        var caughtCancellation = false
        val job = launch(Dispatchers.IO) {
            try {
                BoundedSubprocessRunner.run(
                    command = fixtureCommand("hang"),
                    timeoutMs = 30_000L,
                    mergeErrorStream = true
                )
            } catch (_: CancellationException) {
                caughtCancellation = true
            }
        }

        delay(250L)
        job.cancel()
        job.join()
        val elapsed = System.currentTimeMillis() - start

        assertTrue("Expected CancellationException inside runner", caughtCancellation)
        assertTrue("Cancelled subprocess should terminate promptly (<5s), took ${elapsed}ms", elapsed < 5_000L)
    }

    @Test
    fun `AUD-09 line filter extracts EBU R128 summary from noisy FFmpeg output and parses ReplayGain`() = runBlocking {
        val result = BoundedSubprocessRunner.run(
            command = fixtureCommand("ebur128_noisy"),
            timeoutMs = 10_000L,
            mergeErrorStream = true,
            maxStdoutChars = 64 * 1024,
            stdoutLineFilter = LoudnessScanner::isRelevantEbuR128Line
        )
        assertTrue(result is BoundedSubprocessRunner.SubprocessResult.Success)
        result as BoundedSubprocessRunner.SubprocessResult.Success
        assertFalse("Filtered output should stay well below cap", result.stdoutTruncated)

        val rg = LoudnessScanner.parseEbuR128Output(result.stdout)
        assertNotNull(rg)
        assertEquals(-14.2f, rg!!.measuredIntegratedLufs!!, 0.01f)
        assertEquals(-3.8f, rg.trackGainDb!!, 0.01f)
        assertNotNull(rg.trackPeak)
        assertTrue(abs(rg.trackPeak!! - 0.944f) < 0.02f)
    }

    @Test
    fun `AUD-09 repeated concurrent subprocess calls succeed without resource leaks`() = runBlocking {
        val results = (1..6).map {
            async(Dispatchers.IO) {
                BoundedSubprocessRunner.run(
                    command = fixtureCommand("normal"),
                    timeoutMs = 10_000L
                )
            }
        }.awaitAll()

        assertTrue(results.all { it is BoundedSubprocessRunner.SubprocessResult.Success })
    }

    @Test
    fun `AUD-09 LoudnessScanner duration-aware timeout bounds and invalid output handling`() {
        assertEquals(LoudnessScanner.DEFAULT_TIMEOUT_MS, LoudnessScanner.computeTimeoutMs(null))
        assertEquals(LoudnessScanner.DEFAULT_TIMEOUT_MS, LoudnessScanner.computeTimeoutMs(0L))
        assertEquals(LoudnessScanner.DEFAULT_TIMEOUT_MS, LoudnessScanner.computeTimeoutMs(-5000L))
        assertEquals(LoudnessScanner.MIN_TIMEOUT_MS, LoudnessScanner.computeTimeoutMs(20_000L))
        assertEquals(65_000L, LoudnessScanner.computeTimeoutMs(180_000L)) // 3 min -> 65s
        assertEquals(170_000L, LoudnessScanner.computeTimeoutMs(600_000L)) // 10 min -> 170s
        assertEquals(LoudnessScanner.MAX_TIMEOUT_MS, LoudnessScanner.computeTimeoutMs(7_200_000L)) // 2h -> capped 180s

        // Incomplete / corrupt / out-of-range outputs must NEVER return a fake ReplayGainInfo
        assertNull(LoudnessScanner.parseEbuR128Output(""))
        assertNull(LoudnessScanner.parseEbuR128Output("frame=100 fps=50"))
        assertNull(LoudnessScanner.parseEbuR128Output("I: -99.0 LUFS"))
        assertNull(LoudnessScanner.parseEbuR128Output("I: +5.0 LUFS"))
    }

    // =========================================================================
    // A5 (SEC-04): YouTubeUrlParser Hardened Validation & Pre-Flight DNS
    // =========================================================================

    @Test
    fun `SEC-04 accepts valid public YouTube and third-party media URLs`() {
        val allowed = listOf(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ",
            "https://www.youtube.com/shorts/abc123XYZ_-",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RDAMVMdQw4w9WgXcQ",
            "https://www.youtube.com/playlist?list=PL1234567890",
            "https://soundcloud.com/artist-name/track-name",
            "https://artist.bandcamp.com/track/song-title",
            "https://93.184.216.34/media/song.mp3",
            "https://[2606:2800:220:1:248:1893:25c8:1946]/media/song.mp3"
        )
        for (url in allowed) {
            assertTrue("Expected URL to be valid: $url", YouTubeUrlParser.isValidUrl(url))
        }

        // IDN / Punycode normalization
        val idnNormalized = YouTubeUrlParser.normalizeUrl("https://münchen.example.com/audio/track.mp3")
        assertEquals("https://xn--mnchen-3ya.example.com/audio/track.mp3", idnNormalized)
    }

    @Test
    fun `SEC-04 rejects dangerous schemes, credentials, malformed encodings, and private or obfuscated hosts`() {
        val rejected = listOf(
            // Non-HTTP(S) schemes
            "file:///etc/passwd",
            "ftp://example.com/track.mp3",
            "javascript:alert(1)",
            "data:text/plain;base64,SGVsbG8=",
            // Embedded credentials & backslash confusion
            "https://user:pass@www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://@example.com/track.mp3",
            "https://www.youtube.com\\@127.0.0.1/watch?v=dQw4w9WgXcQ",
            // Malformed percent encoding & invalid ports
            "https://example.com/audio%ZZ.mp3",
            "https://example.com/audio%2",
            "https://example.com:99999/audio.mp3",
            "https://example.com:0/audio.mp3",
            // Local / internal / trailing-dot / single-label hostnames
            "http://localhost/audio.mp3",
            "http://localhost./audio.mp3",
            "http://api.localhost/audio.mp3",
            "http://router.local/audio.mp3",
            "http://metadata.google.internal/computeMetadata/v1/",
            "http://printer.home.arpa/audio.mp3",
            "http://intranet/audio.mp3",
            // IPv4 loopback, unspecified, RFC1918, link-local, CGNAT, multicast, broadcast
            "http://127.0.0.1/audio.mp3",
            "http://127.255.255.254/audio.mp3",
            "http://0.0.0.0/audio.mp3",
            "http://10.0.0.1/audio.mp3",
            "http://172.16.0.1/audio.mp3",
            "http://172.31.255.254/audio.mp3",
            "http://192.168.1.1/audio.mp3",
            "http://169.254.169.254/latest/meta-data/",
            "http://100.64.0.1/audio.mp3",
            "http://100.127.255.254/audio.mp3",
            "http://224.0.0.1/audio.mp3",
            "http://255.255.255.255/audio.mp3",
            // Alternative / obfuscated IPv4 notations
            "http://2130706433/audio.mp3",       // decimal integer 127.0.0.1
            "http://0x7f000001/audio.mp3",       // hex 127.0.0.1
            "http://0x7f.0.0.1/audio.mp3",       // dotted hex
            "http://0177.0.0.1/audio.mp3",       // octal 127.0.0.1
            "http://127.1/audio.mp3",            // shortened 2-part
            "http://127.0.1/audio.mp3",          // shortened 3-part
            // IPv6 loopback, unspecified, IPv4-mapped private, ULA, link-local, zone ID, multicast, 6to4/NAT64 private
            "http://[::1]/audio.mp3",
            "http://[::]/audio.mp3",
            "http://[::ffff:127.0.0.1]/audio.mp3",
            "http://[::ffff:7f00:1]/audio.mp3",
            "http://[::ffff:192.168.1.1]/audio.mp3",
            "http://[fc00::1]/audio.mp3",
            "http://[fd00::1]/audio.mp3",
            "http://[fe80::1]/audio.mp3",
            "http://[fe80::1%25eth0]/audio.mp3",
            "http://[ff02::1]/audio.mp3",
            "http://[2002:7f00:0001::1]/audio.mp3",
            "http://[64:ff9b::127.0.0.1]/audio.mp3"
        )

        for (url in rejected) {
            assertFalse("Expected URL to be REJECTED: $url", YouTubeUrlParser.isValidUrl(url))
        }
    }

    @Test
    fun `SEC-04 pre-flight DNS validation blocks private or mixed answers and allows public answers`() = runBlocking {
        val publicAddr = InetAddress.getByAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34))
        val loopbackAddr = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val privateAddr = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 5))
        val cgnatAddr = InetAddress.getByAddress(byteArrayOf(100, 64, 0, 5))
        val ulaV6 = InetAddress.getByAddress(
            byteArrayOf(0xFD.toByte(), 0x12, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
        )

        // Public host succeeds
        val ok = YouTubeUrlParser.validateResolvedHost("https://media.example.com/song.mp3") {
            arrayOf(publicAddr)
        }
        assertEquals("https://media.example.com/song.mp3", ok)

        // Any disallowed DNS answer (even mixed with a public IP) must throw
        val badSets = listOf(
            arrayOf(loopbackAddr),
            arrayOf(publicAddr, privateAddr),
            arrayOf(cgnatAddr),
            arrayOf(ulaV6)
        )
        for (answers in badSets) {
            try {
                YouTubeUrlParser.validateResolvedHost("https://rebind-test.example.com/song.mp3") { answers }
                fail("Expected private DNS resolution to be rejected: ${answers.map { it.hostAddress }}")
            } catch (_: IllegalArgumentException) {
                // Expected
            }
        }
    }

    // =========================================================================
    // A6 (SEC-03): Android Backup & Data-Extraction Exclusion Rules
    // =========================================================================

    @Test
    fun `SEC-03 backup_rules and data_extraction_rules exclude sensitive and P0 recovery directories`() {
        val projectDir = findAppModuleDir()
        val dataExtractionFile = File(projectDir, "src/main/res/xml/data_extraction_rules.xml")
        val backupRulesFile = File(projectDir, "src/main/res/xml/backup_rules.xml")
        val manifestFile = File(projectDir, "src/main/AndroidManifest.xml")

        assertTrue(dataExtractionFile.isFile)
        assertTrue(backupRulesFile.isFile)
        assertTrue(manifestFile.isFile)

        val manifestText = manifestFile.readText()
        assertTrue(manifestText.contains("""android:dataExtractionRules="@xml/data_extraction_rules""""))
        assertTrue(manifestText.contains("""android:fullBackupContent="@xml/backup_rules""""))

        val requiredExcludes = setOf(
            "hifideck_data/pending_writes",
            "hifideck_data/settings.log",
            "hifideck_data/settings.json",
            "hifideck_data/listening_history.json",
            "hifideck_data/audio_analysis_cache.json",
            "hifideck_data/playback_state.json",
            "hifideck_data/sleep_timer.json"
        )

        fun verifySection(docFile: File, sectionTag: String) {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(docFile)
            val section = doc.getElementsByTagName(sectionTag).item(0)
            assertNotNull("Missing <$sectionTag> in ${docFile.name}", section)

            val includes = mutableSetOf<String>()
            val excludes = mutableSetOf<String>()
            val children = section.childNodes
            for (i in 0 until children.length) {
                val node = children.item(i)
                val path = node.attributes?.getNamedItem("path")?.nodeValue ?: continue
                when (node.nodeName) {
                    "include" -> includes.add(path)
                    "exclude" -> excludes.add(path)
                }
            }
            assertEquals("Only hifideck_data should be allow-listed in <$sectionTag>", setOf("hifideck_data"), includes)
            for (req in requiredExcludes) {
                assertTrue("Missing exclude '$req' in ${docFile.name} <$sectionTag>", req in excludes)
            }
        }

        verifySection(dataExtractionFile, "cloud-backup")
        verifySection(dataExtractionFile, "device-transfer")
        verifySection(backupRulesFile, "full-backup-content")
    }

    // =========================================================================
    // A7 (SEC-05): LogSanitizer Redaction & Line Bounding
    // =========================================================================

    @Test
    fun `SEC-05 LogSanitizer redacts secrets and bounds lines while keeping diagnostic value`() {
        // 1. Sensitive query params redacted; benign YouTube params preserved
        val urlLine = "SOURCE: https://www.youtube.com/watch?v=dQw4w9WgXcQ&token=secret123&sig=abc999&list=PL42"
        val cleanUrl = LogSanitizer.sanitize(urlLine)
        assertTrue(cleanUrl.contains("v=dQw4w9WgXcQ"))
        assertTrue(cleanUrl.contains("list=PL42"))
        assertTrue(cleanUrl.contains("token=<REDACTED>"))
        assertTrue(cleanUrl.contains("sig=<REDACTED>"))
        assertFalse(cleanUrl.contains("secret123"))
        assertFalse(cleanUrl.contains("abc999"))

        // 2. Embedded URL credentials redacted
        val credLine = "ERROR: https://alice:supersecret@media.example.com/track.mp3 failed"
        val cleanCred = LogSanitizer.sanitize(credLine)
        assertFalse(cleanCred.contains("supersecret"))
        assertTrue(cleanCred.contains("https://<REDACTED>@media.example.com/track.mp3"))

        // 3. Authorization & Cookie headers redacted
        val hdrLine = "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig"
        val cleanHdr = LogSanitizer.sanitize(hdrLine)
        assertFalse(cleanHdr.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertTrue(cleanHdr.contains("<REDACTED>"))

        // 4. Control chars stripped and long lines bounded
        val hugeLine = "ERROR:\n\r\u0007 " + "X".repeat(5000)
        val cleanHuge = LogSanitizer.sanitize(hugeLine)
        assertFalse(cleanHuge.contains('\n'))
        assertFalse(cleanHuge.contains('\r'))
        assertTrue(cleanHuge.endsWith("…"))
        assertEquals(LogSanitizer.MAX_LOG_LINE_LENGTH, cleanHuge.length)
    }

    // =========================================================================
    // Helpers & Cross-Platform Disposable JVM Subprocess Fixture
    // =========================================================================

    private fun fixtureCommand(mode: String): List<String> {
        val javaHome = System.getProperty("java.home")
        val isWindows = (System.getProperty("os.name") ?: "").lowercase().contains("win")
        val javaBin = File(javaHome, if (isWindows) "bin/java.exe" else "bin/java").absolutePath
        // Keep classpath minimal (test classes + kotlin-stdlib) to avoid Windows CreateProcess 32KB command limit
        val fullCp = System.getProperty("java.class.path").orEmpty().split(File.pathSeparator)
        val fixtureOrigin = File(SubprocessFixtureMain::class.java.protectionDomain.codeSource.location.toURI()).absolutePath
        val minimalCp = (listOf(fixtureOrigin) + fullCp.filter { entry ->
            entry.contains("kotlin-stdlib", ignoreCase = true) ||
                entry.contains("testDebugUnitTest", ignoreCase = true)
        }).distinct().joinToString(File.pathSeparator)
        return listOf(javaBin, "-Xmx64m", "-cp", minimalCp, SubprocessFixtureMain::class.java.name, mode)
    }

    private fun findAppModuleDir(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return when {
            File(cwd, "src/main/AndroidManifest.xml").isFile -> cwd
            File(cwd, "app/src/main/AndroidManifest.xml").isFile -> File(cwd, "app")
            else -> cwd
        }
    }

    object SubprocessFixtureMain {
        @JvmStatic
        fun main(args: Array<String>) {
            when (args.firstOrNull()) {
                "normal" -> {
                    println("""{"streams":[{"codec_type":"audio","codec_name":"flac"}]}""")
                }
                "empty" -> {
                    // Output nothing, exit 0
                }
                "nonzero" -> {
                    System.err.println("Simulated failure in child process")
                    kotlin.system.exitProcess(42)
                }
                "large_stderr" -> {
                    val chunk = "E".repeat(1024)
                    repeat(300) { System.err.println(chunk) }
                    System.err.flush()
                    println("JSON_OK")
                    System.out.flush()
                }
                "large_stdout" -> {
                    val chunk = "O".repeat(1024)
                    repeat(300) { println(chunk) }
                    System.out.flush()
                }
                "hang" -> {
                    Thread.sleep(60_000L)
                }
                "ebur128_noisy" -> {
                    val noise = "[Parsed_ebur128_0] t: 1.000 M: -15.0 S: -14.8 I: -15.0 LUFS " + "x".repeat(120)
                    repeat(2000) { System.err.println(noise) }
                    System.err.println("Summary:")
                    System.err.println("  Integrated loudness:")
                    System.err.println("    I:         -14.2 LUFS")
                    System.err.println("  Sample peak:")
                    System.err.println("    Peak:       -0.5 dBFS")
                    System.err.flush()
                }
            }
        }
    }
}
