# HiFiDeck Android — Audio Pipeline & Processing Architecture

This document describes the production audio playback, DSP gain coordination, quality inspection, EBU R128 loudness analysis, and non-destructive metadata pipeline implemented in HiFiDeck (`com.example.mdownload`).

---

## 1. End-to-End Playback & DSP Signal Chain

HiFiDeck uses **AndroidX Media3 (`1.9.2`)** hosted inside a foreground `MediaSessionService` (`HiFiPlaybackService`). Audio decoding, gain scaling, and sleep timer fade-out are unified in a single deterministic signal path:

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
2. **Audio Offload / Passthrough Fallback & Transparency**:
   - If a stream bypasses the PCM processor (e.g., compressed offload, passthrough, or before PCM configuration), `PlaybackGainCoordinator` stages the target on `ReplayGainAudioProcessor` for immediate activation on next PCM configure and applies safe attenuation fallback (`effectiveLinearGain.coerceIn(0f, 1f)`) on `player.volume`.
   - The active stage (`DSP_PCM_PROCESSOR`, `PLAYER_VOLUME_FALLBACK`, or `BYPASS`) is surfaced transparently in Settings (`ReplayGainSettingsContent`) and the Audio Quality Inspector (`AudioInspectorSheet`).
3. **No Fabricated Bit-Perfect / DAC Claims**:
   - Android's `AudioTrack` routes audio through `AudioFlinger` / system mixer on standard playback paths unless an explicit USB-DAC direct path is opened. HiFiDeck reports only observable facts from Media3's decoded `Format` (`sampleRate`, `channelCount`, `pcmEncoding`) and never claims bit-perfect hardware DAC output or system mixer bypass.

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
- Invokes the bundled `libffmpeg.so` (`applicationInfo.nativeLibraryDir`) off the main thread (`Dispatchers.IO`) with `LD_LIBRARY_PATH` pointed at `youtubedl-android`'s unpacked FFmpeg shared libraries.
- Command: `libffmpeg.so -nostats -i <file> -filter_complex ebur128=peak=sample -f null -`.
- Parses integrated loudness (`I: <x> LUFS`) and sample peak (`Peak: <x> dBFS`).
- Computes ReplayGain 2.0 estimate against `-18.0 LUFS` (`trackGainDb = -18.0f - measuredIntegratedLufs`), clearly tagged as `"Measured (EBU R128 scan)"` and cached in `AudioAnalysisRepository` (`audio_analysis_cache.json`, keyed by `uri + sizeBytes + lastModifiedMs`).
- Supports cooperative coroutine cancellation (`ensureActive()` + `proc.destroyForcibly()`).

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

## 4. Non-Destructive Metadata Editor (`MetadataTagIO` & `MetadataRepository`)

### 4.1 Native Tag Rewriting (Zero Audio Re-Encoding)
`MetadataTagIO` edits container metadata directly in Kotlin without invoking lossy audio re-encoding:
- **MP3**: Rewrites ID3v2.3/v2.4 header frames (`TIT2`, `TPE1`, `TALB`, `TPE2`, `TCON`, `TYER`/`TDRC`, `TRCK`, `TPOS`, `COMM`, `USLT`, `APIC`), preserving **all unknown/existing frames** (including `TXXX` ReplayGain tags, `RVA2`, `PRIV`, `MDownload` custom tags) and copying the MPEG audio bitstream byte-for-byte.
- **FLAC**: Updates the `VORBIS_COMMENT` and `PICTURE` metadata blocks while preserving `STREAMINFO`, `SEEKTABLE`, unknown metadata blocks, and all audio frames verbatim.
- **MP4 / M4A**: Updates `moov/udta/meta/ilst` atoms (`©nam`, `©ART`, `©alb`, `aART`, `©gen`, `©day`, `trkn`, `disk`, `©cmt`, `©lyr`, `covr`) while adjusting `stco`/`co64` chunk offsets cleanly and preserving `mdat` audio bytes untouched (including `----` freeform ReplayGain atoms).
- **Ogg Vorbis / Ogg Opus**: Rewrites the Vorbis/Opus comment header packet and recalculates Ogg page CRC32 checksums cleanly while preserving all audio packets untouched.
- **WAV / Unsupported Containers**: Explicitly rejected with `MetadataWriteOutcome.Unsupported` rather than risking corruption or silent lossy transcoding.

### 4.2 Atomic Write, Integrity Verification & Scoped Storage
1. **Validation (`MetadataValidator`)**: Rejects blank titles (single-track mode), out-of-range years (`1000..2100`), negative/invalid track or disc numbers, and active download conflicts.
2. **Temp-File Rewrite + Audio Stream SHA-256 Verification**:
   - Writes edited output to a temporary file first.
   - Verifies non-empty output, parses the rewritten tags back, and compares the **SHA-256 hash of the raw audio stream payload** before vs after rewriting — aborting if a single audio byte changed.
3. **Android 15+ (API 35+) Scoped Storage Compliance**:
   - Detects `RecoverableSecurityException` / write-permission denial for non-owned `MediaStore` tracks and surfaces `pendingWritePermissionUris` to `MDownloadRoot`.
   - `MDownloadRoot` launches `MediaStore.createWriteRequest(contentResolver, uris)` via `ActivityResultContracts.StartIntentSenderForResult`.
   - Upon user grant, `MainViewModel.onWritePermissionResult(true)` automatically retries the exact pending single or batch commit.
4. **Post-Commit Synchronization**:
   - Triggers `MediaScannerConnection.scanFile`, invalidates `ArtworkCache` & `AudioAnalysisRepository` cache entries for edited URIs, refreshes `MusicLibraryRepository`, `LyricsRepository`, active `PlaybackController` queue metadata, and live `PlaybackGainCoordinator` state.

---

## 5. Sleep Timer Coordination (`SleepTimerController`)

- Owned by `MDownloadApp` and bound directly to `HiFiPlaybackService`'s `ExoPlayer` instance — survives Compose UI destruction, screen lock, and Activity recreation.
- **Timing accuracy**: Uses monotonic `SystemClock.elapsedRealtime()` for `DURATION` deadlines so wall-clock/timezone changes cannot skew expiry.
- **Modes**: `DURATION` (presets + custom minutes + `+5/+10/+15m` extension), `END_OF_TRACK`, and `END_OF_QUEUE`.
- **Fade-out**: Configurable `0–60 s` quadratic ease-out curve (`linear²`) applied strictly through `PlaybackGainCoordinator.setSleepFadeFactor(...)` — never modifies device media volume or overwrites ReplayGain.
- **Expiry & Restoration**: Pauses playback cleanly on `player.applicationLooper` while preserving queue and track position, then immediately resets `sleepFadeFactor = 1.0f` so subsequent playback resumes at normal normalized volume.
- **Documented Limitation**: Persists wall-clock deadline metadata to `sleep_timer.json` to restore active timers if the app restarts before expiry, but does **not** schedule OS `AlarmManager` exact alarms across full OS process death.
