# HiFiDeck — Metadata Data Integrity Audit

**Audit Scope:** Phase 5A — Container-level correctness, audio payload preservation, unknown/ReplayGain/lyrics/artwork tag retention, scoped storage safety, read-back verification, and batch editing across MP3, FLAC, M4A/MP4, and Ogg/Opus.  
**Audit Date:** 2026-11-24

> **Audit Safety Policy Observed:** Zero real user music files on the vivo X200s were modified during this audit. All integrity conclusions are grounded in line-by-line source inspection of `MetadataTagIO.kt`, `MetadataRepository.kt`, `LyricsTagIO.kt`, `PlaybackController.kt`, `MainViewModel.kt`, and existing synthetic-fixture JVM tests (`AdvancedAudioFeaturesTest.kt`, `LyricsPipelineTest.kt`).

---

## 1. Documentation Claims vs. Reality Matrix

| Format | Claimed in `AUDIO_PIPELINE.md` / `FEATURE_VALIDATION_REPORT.md` | Actual Implementation in `MetadataTagIO.kt` | Audio Payload SHA-256 Verified? | Read-Back Verified? |
|---|---|---|---|---|
| **MP3 (ID3v2.3 / 2.4)** | Native pure-Kotlin ID3v2 frame rewriter preserving audio payload, unknown frames, ReplayGain, lyrics, cover | Pure-Kotlin ID3v2 rewriter (**has P0 ID3v2.4 unsync flag corruption & ID3v2.2 silent tag wipe**) | **YES** (exact post-ID3v2 byte range) | **YES** (partial field check) |
| **FLAC** | Native pure-Kotlin FLAC metadata block rewriter (`VORBIS_COMMENT` + `PICTURE`) preserving audio frames | Pure-Kotlin FLAC block rewriter (preserves `STREAMINFO`, `SEEKTABLE`, `CUESHEET`, `APPLICATION`, unknown blocks) | **YES** (post-metadata-blocks byte range) | **YES** |
| **MP4 / M4A (AAC / ALAC)** | Claimed native box rewriter with `stco`/`co64` chunk-offset adjustment | **CONTRADICTED:** No Kotlin box writer exists! Shells out to `libffmpeg.so -c copy -map_metadata 0` **without `-movflags use_metadata_tags`** | **NO** (`computeAudioPayloadSha256` returns `null` → skipped!) | **NO** (`readMp4BasicMetadata(file)` is a dead stub ignoring `file`!) |
| **Ogg / Opus** | Claimed native Ogg page rewriter with CRC32 recalculation | **CONTRADICTED:** No Kotlin Ogg page/CRC writer exists! Shells out to `libffmpeg.so -c copy` and **silently drops artwork edits** | **NO** (`computeAudioPayloadSha256` returns `null` → skipped!) | **NO** (skipped / incomplete) |

---

## 2. Critical Data-Loss & Corruption Findings (P0)

### 2.1 [P0 / META-01] Non-Atomic `content://` File Truncation Window (Permanent File Destruction Risk)
- **Files:**
  - `MetadataRepository.kt` (`commitContentUri()`)
  - `LyricsTagIO.kt` (`embedLyricsIntoUri()`)
- **What Happens Today:**
  1. Both pipelines stage the rewritten audio file in `cacheDir` first (good), and `MetadataRepository` checks `cacheDir.usableSpace` (missing check on external/shared storage where the destination `content://` lives!).
  2. When committing back to MediaStore / SAF, both call:
     ```kotlin
     context.contentResolver.openOutputStream(uri, "wt")
     ```
  3. Opening with mode `"wt"` **immediately truncates the user's original audio file on external storage to 0 bytes** before a single byte of the staged file is written.
  4. If the device runs out of external storage mid-copy, the app is killed by OriginOS / OOM killer, the user force-closes the app, or an I/O exception occurs mid-stream: **the original music file is permanently destroyed (0 bytes or half-written corrupt file)** and there is no recovery copy or rollback transaction.
- **Scoped Storage Permission Gap in `LyricsTagIO`:**
  - `MetadataRepository` properly checks for `RecoverableSecurityException` and triggers `MediaStore.createWriteRequest()` via `MainViewModel` (`PendingMetadataWriteRequest`).
  - **`LyricsTagIO.embedLyricsIntoUri()` does NOT implement `MediaStore.createWriteRequest()`** — embedding lyrics into tracks not created by HiFiDeck fails on Android 15–17 (API 35–37) scoped storage instead of prompting the user for write consent.

---

### 2.2 [P0 / META-02a] MP3 ID3v2.4 Per-Frame Unsynchronization Corruption (Duplicated in 2 Files)
- **Files:** `MetadataTagIO.kt` AND `LyricsTagIO.kt` (code is copy-pasted between both files).
- **Root Cause:**
  - When reading an ID3v2.4 frame with the per-frame unsynchronization bit set (`(frameFlags[1].toInt() and 0x02) != 0`), the parser calls `removeUnsync(body)` to strip `0xFF 0x00 → 0xFF` bytes.
  - However, when serializing unknown/preserved frames back out, **it writes the de-unsynchronized `body` while keeping the original `frameFlags` bit `0x02` SET!**
  - Any subsequent compliant ID3v2.4 reader (ExoPlayer, FFmpeg, foobar2000, MusicBrainz Picard, HiFiDeck itself on next edit) sees flag `0x02` and applies `removeUnsync()` a **second time**, stripping legitimate `0xFF 0x00` sequences inside embedded JPEG covers (`APIC`), UTF-16 BOMs, or `PRIV` frames — **permanently corrupting embedded artwork and binary frames**.
- **Duplication Note:** Because `LyricsTagIO.kt` duplicates ~500 lines of ID3v2 parsing/writing from `MetadataTagIO.kt`, fixing only one file leaves the other broken.

---

### 2.3 [P0 / META-02b] MP3 ID3v2.2 Silent Full Tag Wipe
- **File:** `MetadataTagIO.kt` (`readId3v2Frames()`, `findMp3AudioPayloadOffset()`)
- **Root Cause:**
  - `readId3v2Frames()` rejects ID3v2.2 headers (`major == 2`, which use 3-char frame IDs like `TT2`/`TP1`/`PIC`) by returning `null` or empty frame list.
  - Meanwhile, `findMp3AudioPayloadOffset()` parses the ID3v2.2 header size and locates the MPEG audio frames after it.
  - On rewrite, `MetadataTagIO` builds a brand-new ID3v2.3/2.4 tag containing **only** the newly edited fields and copies the audio payload after the old ID3v2.2 header — **silently wiping ALL pre-existing ID3v2.2 tags** (embedded album art, ReplayGain, lyrics, comments, custom frames) with no warning to the user.

---

### 2.4 [P0 / META-02c] MP4 / M4A ReplayGain Loss, Broken Read-Back Stub & Comment Overwrite
- **File:** `MetadataTagIO.kt` (and IDE analyzer warning at `MetadataTagIO.kt:654`)
- **Confirmed Defects:**
  1. **ReplayGain & Freeform Atoms Stripped:** `rewriteViaFfmpeg()` runs `libffmpeg.so -i src -c copy -map_metadata 0 ...` **without `-movflags use_metadata_tags`**. FFmpeg's MP4 muxer drops non-standard iTunes freeform `----` atoms (including `----:com.apple.iTunes:replaygain_track_gain`, `replaygain_album_gain`, `replaygain_track_peak`, and custom tags) unless `-movflags use_metadata_tags` is explicitly supplied.
  2. **Read-Back Verification Is a Dead Stub:** `readMp4BasicMetadata(file: File)` (`MetadataTagIO.kt:654`) has an unused `file` parameter (flagged by Android Studio static analysis!) and literally returns an empty `EditableTrackMetadata()`. Read-back verification after MP4 editing therefore cannot detect failed or corrupted writes.
  3. **Audio Payload SHA-256 Skipped:** `computeAudioPayloadSha256()` returns `null` for `MP4_M4A`, silently bypassing audio integrity verification.
  4. **Comment Cannot Be Cleared & Clobbers `MDOWNLOAD_YT_ID`:** `rewriteViaFfmpeg()` only adds `-metadata comment=...` when `meta.comment.isNotBlank()`, so clearing an existing comment leaves the old comment intact; conversely, setting a comment overwrites FFmpeg's `comment` atom where HiFiDeck's YouTube fingerprint (`MDOWNLOAD_YT_ID=...`) is stored, breaking duplicate download detection!
  5. **No Subprocess Timeout or Cancellation:** If `libffmpeg.so` stalls on a malformed M4A atom tree, the coroutine hangs without `process.destroyForcibly()` or timeout enforcement.

---

### 2.5 [P0 / META-02d] Ogg / Opus Silent Artwork Discard & Skipped Verification
- **File:** `MetadataTagIO.kt` (`rewriteViaFfmpeg()`)
- **Confirmed Defects:**
  1. **Silent Artwork Drop:** When `artworkChanged == true` or `artworkRemoved == true`, `rewriteViaFfmpeg()` applies artwork mapping **only** when `format == MP4_M4A`. For `OGG_OPUS`, artwork changes are **silently ignored**, yet the function returns `MetadataWriteResult.Success`, misleading the user into believing artwork was updated or removed.
  2. **No Audio Payload SHA-256 Verification:** `computeAudioPayloadSha256()` returns `null` for `OGG_OPUS`.
  3. **No Native Ogg Page/CRC Engine:** Contrary to `AUDIO_PIPELINE.md`, all Ogg/Opus rewrites depend on `libffmpeg.so` remuxing (which alters Ogg serial numbers/page framing and does not verify untouched Opus packet payloads).

---

## 3. FLAC Integrity Assessment (Strongest Format)

- **File:** `MetadataTagIO.kt` (`rewriteFlacFile()`), `LyricsTagIO.kt`
- **Verified Behavior (`metadataTagIO_flacRoundTripPreservesCommentsLyricsReplayGainAndAudioPayload`, `flacVorbisCommentRoundTripPreservesMetadataBlocksAndAudioStreamBitForBit`):**
  - Correctly parses FLAC `fLaC` marker and 4-byte metadata block headers (`last-block` bit + 7-bit type + 24-bit big-endian length).
  - Preserves `STREAMINFO`, `SEEKTABLE`, `CUESHEET`, `APPLICATION`, and non-target blocks intact.
  - Updates `VORBIS_COMMENT` fields case-insensitively while retaining `REPLAYGAIN_*`, `LYRICS`, `UNSYNCEDLYRICS`, `MDOWNLOAD_YT_ID`, and unknown Vorbis keys.
  - Updates or removes `PICTURE` (`type = 6`) blocks cleanly and recalculates the `last-block` flag on the final metadata block.
  - Computes SHA-256 over the exact post-metadata audio frame stream before and after rewriting, failing safely if a single audio bit differs.
- **Remaining FLAC Edge Risks:**
  - Subject to the same **P0 `META-01` `"wt"` truncation window** when committing back to `content://` URIs.
  - Does not guard against malformed >16 MiB artwork exceeding the 24-bit FLAC metadata block length limit (`16,777,215` bytes) before truncating the 24-bit length field silently (`size and 0xFFFFFF`) — **a large PNG/BMP cover >16 MiB would corrupt the FLAC block header**.

---

## 4. Batch Editing, Cancellation & Post-Commit Synchronization Audit

| Severity | ID | Component | Confirmed Defect |
|---|---|---|---|
| **P1** | **META-03** | `MainViewModel.kt` (`applyBatchMetadataEdit`) | **Cancellation Skips Refresh for Already-Committed Tracks:** Batch editing processes tracks sequentially inside `batchMetadataJob`. If the user cancels mid-batch (e.g., after 4 of 10 tracks were written to disk), `CancellationException` exits before calling `postCommitMetadataRefresh()` — leaving Library UI, `AudioAnalysisRepository`, Smart Playlists, and MediaStore cache out of sync with the 4 already-modified files. |
| **P1** | **META-04** | `PlaybackController.kt` (`refreshTrackMetadataFromLibrary`) | **Stale MediaSession / Notification / Lock-Screen / Bluetooth Metadata:** After editing the currently playing track, `refreshTrackMetadataFromLibrary()` updates `PlaybackUiState` in the Compose UI, **but does NOT call `player.replaceMediaItem(...)` or update Media3's `MediaItem.mediaMetadata`**. System media notification, lock screen, and Bluetooth AVRCP display stale title/artist/album/cover until the user switches tracks. |
| **P2** | **META-05** | `TrackMetadataEditorSheet.kt` | **Misleading UI Safety Copy:** The Metadata Editor sheet reassures users that ReplayGain, lyrics, and unrecognized tags are always preserved safely across formats, which is currently **false for MP4/M4A (ReplayGain dropped), ID3v2.2 (all tags wiped), ID3v2.4 unsynced frames (corrupted), and Ogg/Opus artwork (silently ignored)**. |
| **P2** | **META-06** | `MetadataTagIO.kt` & `LyricsTagIO.kt` | **~500+ Lines of Duplicated Binary Tag Code:** Both classes independently implement ID3v2 and FLAC binary parsers/writers, duplicating subtle binary bugs (such as the ID3v2.4 unsync flag bug) and creating divergent behavior between metadata edits and lyrics embedding. |

---

## 5. Required Integrity Hardening Checklist

1. **Pre-Write Backup & Rollback for `content://` (`META-01`):** Before opening `"wt"` on a `content://` URI, copy the original file to a durable recovery file in `filesDir/pending_metadata_rollback/` (checking external storage headroom via `StorageManager.getAllocatableBytes()`), stream staged output, verify byte count + read-back, and delete backup only on confirmed success (plus startup crash-recovery check).
2. **Fix ID3v2.4 Unsync & ID3v2.2 (`META-02a/b`):** Clear bit `0x02` (`frameFlags[1] = (frameFlags[1].toInt() and 0xFD).toByte()`) whenever `removeUnsync()` is applied; explicitly reject ID3v2.2 with a clear user message (or implement ID3v2.2→ID3v2.3 frame ID mapping table) rather than silently wiping tags.
3. **Fix MP4/M4A & Ogg/Opus (`META-02c/d`):** Pass `-movflags use_metadata_tags` + explicit `-metadata comment=` clearing (while preserving `MDOWNLOAD_YT_ID`), implement real MP4 atom/ffprobe read-back verification in `readMp4BasicMetadata()`, add FFmpeg subprocess timeout + cancellation (`destroyForcibly`), and either implement native Ogg `METADATA_BLOCK_PICTURE` writing or explicitly disable artwork edits for Ogg/Opus in the UI instead of returning silent `Success`.
4. **Enforce 16 MiB FLAC Block Guard:** Reject or downscale artwork exceeding `16,000,000` bytes before writing FLAC `PICTURE` blocks.
5. **Unify `MetadataTagIO` and `LyricsTagIO`:** Extract a single tested `AudioContainerTagEngine` and wire `LyricsTagIO` to `MediaStore.createWriteRequest()`.
6. **Sync Media3 `MediaItem` Post-Edit (`META-04`):** Update `MediaController` / `ExoPlayer` metadata on the active track and execute `postCommitMetadataRefresh()` inside `finally { withContext(NonCancellable) { ... } }` during batch cancellation.
