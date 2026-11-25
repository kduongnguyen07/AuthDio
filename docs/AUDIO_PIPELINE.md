# AuthDio Android — Audio Pipeline & Processing Architecture

> **Branding Note:** AuthDio was formerly known as HiFiDeck / MDownload.

This document describes the production audio playback, DSP gain coordination, quality inspection, EBU R128 loudness analysis, and non-destructive metadata pipeline implemented in AuthDio (`com.example.mdownload`).

---

## 1. End-to-End Playback & DSP Signal Chain

AuthDio uses **AndroidX Media3 (`1.9.2`)** hosted inside a foreground `MediaSessionService` (`HiFiPlaybackService`). Audio decoding, gain scaling, and sleep timer fade-out are unified in a single deterministic signal path:

```text
Local MediaStore / SAF Audio File (MP3, FLAC, Opus, OGG, M4A/AAC, ALAC, WAV)
   │
   ▼
Media3 Extractor + MediaCodec / Software Decoder
   │  (Decoded PCM_16BIT or PCM_FLOAT stream)
   ▼
DefaultAudioSink (DefaultRenderersFactory.buildAudioSink)
   │
   ▼
ReplayGainAudioProcessor (BaseAudioProcessor)
   │  effectiveLinearGain = replayGainLinear * sleepFadeFactor
   │  • 30 ms per-frame linear gain ramp (click/pop-free)
   │  • Zero-math fast-path at unity gain (1.0x ± 0.0005)
   │  • Full-scale clamping: [-32768, 32767] (PCM-16) / [-1.0f, 1.0f] (PCM-float)
   │  • Hard linear safety ceiling: 8.0x (+18 dB)
   ▼
Android AudioTrack -> Android AudioFlinger / System Mixer -> Output Device
```

### Key Architectural Guarantees
1. **Single Volume / Gain Authority (`PlaybackGainCoordinator`)**:
   - ReplayGain and Sleep Timer never mutate `player.volume` independently or race with one another.
   - `PlaybackGainCoordinator` composes a single scalar:
     `effectiveLinearGain = ReplayGainCalculator.composeEffectiveGain(replayGainLinear, sleepFadeFactor)`.
   - When `ReplayGainAudioProcessor.isProcessingPcm` is `true`, all ReplayGain (including positive preamp `> 1.0x`) and Sleep Timer fade attenuation run inside the PCM DSP stage while keeping `player.volume = 1.0f`.
   - **Thread safety**: All `player.volume` mutations are dispatched onto `player.applicationLooper` (via `Handler(looper)` when called from background IO coroutines) so off-thread metadata/ReplayGain refreshes never violate ExoPlayer's thread contract.
2. **Audio Offload / Passthrough Fallback & Ordered Single-Authority Handoff**:
   - If a stream bypasses the PCM processor (e.g., compressed offload, passthrough, or before PCM configuration), `PlaybackGainCoordinator` holds `ReplayGainAudioProcessor.targetGain = 1.0f` and applies safe attenuation fallback (`effectiveLinearGain.coerceIn(0f, 1f)`) on `player.volume`. When `onConfigure()` transitions into `DSP_PCM_PROCESSOR`, `player.volume` resets to `1.0f` on `player.applicationLooper` before DSP gain engages — preventing double attenuation.
   - The active stage (`DSP_PCM_PROCESSOR`, `PLAYER_VOLUME_FALLBACK`, or `BYPASS`) is surfaced transparently in Settings (`ReplayGainSettingsContent`) and the Audio Quality Inspector (`AudioInspectorSheet`).
   - At unity gain (`1.0f`), `ReplayGainAudioProcessor` executes a bit-exact `ByteBuffer.put()` fast path (zero floating-point scaling/rounding) while staying installed so mid-track `+dB` preamp and Sleep Timer fades engage without restarting `DefaultAudioSink`.
3. **No Fabricated Bit-Perfect / DAC Claims**:
   - Android's `AudioTrack` routes audio through `AudioFlinger` / system mixer on standard playback paths unless an explicit USB-DAC direct path is opened. AuthDio reports only observable facts from Media3's decoded `Format` (`sampleRate`, `channelCount`, `pcmEncoding`) and never claims bit-perfect hardware DAC output or system mixer bypass.

---

## 2. ReplayGain & Loudness Normalization

### 2.1 Tag Reading (`ReplayGainMetadataReader`)
Reads embedded loudness tags directly from file headers/containers without decoding audio:
- **MP3 (ID3v2.3 / ID3v2.4)**: `TXXX` (`REPLAYGAIN_TRACK_GAIN`, `REPLAYGAIN_ALBUM_GAIN`, `REPLAYGAIN_TRACK_PEAK`, `REPLAYGAIN_ALBUM_PEAK`) and `RVA2` frames.
- **FLAC / Ogg Vorbis**: Vorbis Comment `REPLAYGAIN_*` fields.
- **Ogg Opus**: RFC 7845 `R128_TRACK_GAIN` and `R128_ALBUM_GAIN` (Q7.8 fixed-point, divided by `256.0f`, offset to ReplayGain 2.0 `-18 LUFS` reference vs Opus `-23 LUFS` reference) plus standard `REPLAYGAIN_*` tags if present.
- **MP4 / M4A (AAC / ALAC)**: iTunes freeform atoms (`----:com.apple.iTunes:replaygain_*`).

### 2.2 Gain Calculation (`ReplayGainCalculator`)
- **Modes**: `OFF`, `TRACK`, `ALBUM` (automatically falls back to Track Gain when Album Gain is absent).
- **Preamp**:
  - Tracks with ReplayGain tags: configurable `preampWithTagsDb` (`-12.0 dB` to `+12.0 dB`).
  - Tracks without ReplayGain tags: configurable `MissingReplayGainBehavior` (`LEAVE_UNCHANGED` = `0 dB` or `APPLY_FALLBACK_PREAMP` using `preampWithoutTagsDb`).
- **Peak-Aware Gain Limiting**:
  - When `preventClipping` is enabled and sample-peak metadata (`> 0`) is available, linear gain is capped at `1.0f / peak` (`maxGainDb = -20 * log10(peak)`).
  - Documented honestly in UI as **sample-peak headroom protection** (not inter-sample true-peak oversampled limiting).

### 2.3 On-Demand EBU R128 Scanner (`LoudnessScanner`)
- Invokes the bundled `libffmpeg.so` (`applicationInfo.nativeLibraryDir`) via `BoundedSubprocessRunner` off the main thread (`Dispatchers.IO`) with `LD_LIBRARY_PATH` pointed at `youtubedl-android`'s unpacked FFmpeg shared libraries, concurrent `stdout`/`stderr` pipe draining, duration-aware timeout (`30 s..180 s`, `45 s` default), and `isRelevantEbuR128Line` filtering.
- Command: `libffmpeg.so -nostats -i <file> -filter_complex ebur128=peak=sample -f null -`.
- Parses integrated loudness (`I: <x> LUFS`) and sample peak (`Peak: <x> dBFS`).
- Computes ReplayGain 2.0 estimate against `-18.0 LUFS` (`trackGainDb = -18.0f - measuredIntegratedLufs`), clearly tagged as `"Measured (EBU R128 scan)"` and cached in `AudioAnalysisRepository` (`audio_analysis_cache.json`, keyed by `uri + sizeBytes + dateModifiedSec`, bounded to 1,000 LRU entries; failed/timed-out scans are never cached).
- Supports responsive cooperative coroutine cancellation with escalating `destroy()` → `destroyForcibly()`.

---

## 3. Audio Quality Inspector (`AudioInspectorEngine`)

Separates **Source File Properties** from **Active Playback Output** so users always see accurate technical provenance:

1. **Primary Inspection (`libffprobe.so`)**:
   - Runs bundled `libffprobe.so -v quiet -print_format json -show_format -show_streams <file>` on `Dispatchers.IO`.
   - Extracts container format, audio codec name/profile, lossless vs lossy classification, bitrate + bitrate mode (CBR/VBR/ABR when determinable), sample rate, channel count & layout, duration, file size, embedded artwork presence/resolution/MIME, and raw metadata tags.
2. **Fallback Inspection**:
   - Falls back cleanly to Android `MediaExtractor` + `MediaMetadataRetriever` if `libffprobe.so` is unavailable or fails on an unusual container.
3. **Bit-Depth Honesty**:
   - **Lossless codecs** (FLAC, ALAC, WAV/PCM, APE, WavPack, etc.): reports exact `bits_per_raw_sample` / `bits_per_sample` (e.g., `16-bit`, `24-bit`).
   - **Lossy codecs** (MP3, AAC, Opus, Vorbis, WMA): intentionally returns `bitDepth = null` (`"N/A (lossy)"`) because lossy codecs store frequency-domain transform coefficients rather than fixed PCM bit depth.
4. **Active Playback Output**:
   - Displayed only when inspecting the currently active track in `HiFiPlaybackService`, showing live Media3 decoded sample rate, channel count, PCM encoding, and active ReplayGain stage (`DSP_PCM_PROCESSOR` / `PLAYER_VOLUME_FALLBACK` / `BYPASS`) + applied dB.

---

## 4. Non-Destructive Metadata Editor (`AudioContainerPrimitives`, `MetadataTagIO` & `SafeAudioWritePipeline`)

### 4.1 Native Tag Rewriting & Lossless Remuxing (Zero Audio Re-Encoding)
`AudioContainerPrimitives` and `MetadataTagIO` edit container metadata without invoking lossy audio re-encoding:
- **MP3 (ID3v2.3 / ID3v2.4)**: Pure-Kotlin frame rewriter (`TIT2`, `TPE1`, `TALB`, `TPE2`, `TCON`, `TYER`/`TDRC`, `TRCK`, `TPOS`, `COMM`, `USLT`, `APIC`), clearing ID3v2.4 per-frame unsynchronization bit `0x02` after de-unsynchronizing preserved frames, preserving **all unknown/existing frames** (including `TXXX` ReplayGain tags, `RVA2`, `PRIV`, `MDOWNLOAD_YT_ID`), and copying the MPEG audio bitstream byte-for-byte. Legacy **ID3v2.2** files explicitly return `Unsupported` rather than silently wiping 3-char frames.
- **FLAC**: Pure-Kotlin `VORBIS_COMMENT` and validated `PICTURE` block rewriter (enforcing `<= 4 MiB`, JPEG/PNG/WebP magic bytes, real pixel dimensions `1..4096`, and FLAC 24-bit block ceiling) preserving `STREAMINFO`, `SEEKTABLE`, unknown blocks, and all audio frames verbatim.
- **MP4 / M4A**: Reads both iTunes `moov/udta/meta/ilst` (including `----` freeform ReplayGain atoms) and QuickTime `moov/meta/keys + ilst` (`mdta`); writes via bounded FFmpeg lossless remux (`-c copy -map_metadata 0 -movflags +use_metadata_tags+faststart`) preserving ReplayGain, lyrics, and `MDOWNLOAD_YT_ID`, verifying `mdat` SHA-256 on text/lyrics edits.
- **Ogg Vorbis / Ogg Opus**: Updates stream-level Vorbis/Opus comments via bounded FFmpeg lossless remux (`-map_metadata:s:a:0 0:s:a:0` / `-metadata:s:a:0`) preserving all audio packets (verified via audio-packet SHA-256). Artwork changes on Ogg/Opus explicitly return `Unsupported` (disabled in editor UI) rather than silently dropping artwork.
- **WAV / Unsupported Containers**: Explicitly rejected with `MetadataWriteOutcome.Unsupported` rather than risking corruption or silent lossy transcoding.

### 4.2 Crash-Safe Commit, Recovery Journal & Scoped Storage (`SafeAudioWritePipeline`)
1. **Validation (`MetadataValidator`)**: Rejects blank titles (single-track mode), out-of-range years (`1000..2100`), negative/invalid track or disc numbers, and active download conflicts.
2. **Durable Rollback & Journal (`filesDir/hifideck_data/pending_writes/<opId>/`)**:
   - Copies the original audio stream to a `fsync`'d `rollback.<ext>` file, persists a pure-Kotlin atomic `manifest.json` (`PREPARING → READY_TO_COMMIT → COMMITTING → VERIFIED`), checks destination storage headroom (`requiredBytes + 4 MiB`), and runs format verification + audio-stream SHA-256 checks on `staged_out.<ext>` before modifying the target URI.
   - Re-reads the destination URI after writing (`file://` atomic sibling rename; `content://` bounded 32 KiB stream + `fd.sync()`) to verify exact byte length, SHA-256, and tag read-back before cleaning up `<opId>/`.
   - Automatically restores `rollback.<ext>` on any error or mismatch (or retains `rollback.<ext>` + `RECOVERY_FAILED` manifest if restoration cannot complete). On next cold startup, `MDownloadApp` invokes `metadataRepository.recoverInterruptedWrites()` asynchronously on `Dispatchers.IO`.
3. **Android 15+ (API 35+) Scoped Storage Compliance**:
   - Detects `RecoverableSecurityException` / write-permission denial for non-owned `MediaStore` tracks and surfaces `pendingWritePermissionUris` to `MDownloadRoot`.
   - `MDownloadRoot` launches `MediaStore.createWriteRequest(contentResolver, uris)` via `ActivityResultContracts.StartIntentSenderForResult`.
   - Upon user grant, `MainViewModel.onWritePermissionResult(true)` automatically retries the exact pending single or batch commit.
4. **Post-Commit Synchronization**:
   - Triggers `MediaScannerConnection.scanFile`, invalidates `ArtworkCache` & `AudioAnalysisRepository` cache entries for edited URIs, refreshes `MusicLibraryRepository`, `LyricsRepository`, live Media3 `Player.replaceMediaItem()` metadata in `PlaybackController`, and live `PlaybackGainCoordinator` state (batch cancellation runs refresh in `NonCancellable` for already-committed tracks).

---

## 5. Sleep Timer Coordination (`SleepTimerController`)

- Owned by `MDownloadApp` and bound directly to `HiFiPlaybackService`'s `ExoPlayer` instance — survives Compose UI destruction, screen lock, and Activity recreation.
- **Timing accuracy**: Uses monotonic `SystemClock.elapsedRealtime()` for `DURATION` deadlines so wall-clock/timezone changes cannot skew expiry.
- **Modes**: `DURATION` (presets + custom minutes + `+5/+10/+15m` extension), `END_OF_TRACK`, and `END_OF_QUEUE` (using Media3 `Timeline.getFirstWindowIndex/getLastWindowIndex` across all Repeat × Shuffle combinations without mutating user repeat/shuffle preferences).
- **Fade-out**: Configurable `0–60 s` quadratic ease-out curve (`linear²`) applied strictly through `PlaybackGainCoordinator.setSleepFadeFactor(...)` — never modifies device media volume or overwrites ReplayGain.
- **Zero-Spike Expiry & Restoration**: Holds `sleepFadeFactor = 0.0f` across `player.pause()` until `onIsPlayingChanged(false)` plus a `150 ms` `AudioTrack` drain settle window before silently restoring `1.0f` (or restores immediately if the user manually presses Play).
- **Documented Limitation**: Persists wall-clock deadline metadata asynchronously to `sleep_timer.json` to restore active timers if the app restarts before expiry, but does **not** schedule OS `AlarmManager` exact alarms across full OS process death.
