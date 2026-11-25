# Metadata & Lyrics Binary Format Integrity Report (`STAB-02` / `META-02`)

**Date:** 2026-11-24  
**Primary Files:**
- `app/src/main/java/com/example/mdownload/core/metadata/AudioContainerPrimitives.kt`
- `app/src/main/java/com/example/mdownload/core/metadata/MetadataTagIO.kt`
- `app/src/main/java/com/example/mdownload/player/lyrics/LyricsTagIO.kt`
- `app/src/main/java/com/example/mdownload/core/audio/ReplayGainMetadataReader.kt`
- `app/src/main/java/com/example/mdownload/player/playback/MediaItemMapper.kt`
- `app/src/main/java/com/example/mdownload/player/playback/PlaybackController.kt`
- `app/src/main/java/com/example/mdownload/player/ui/TrackMetadataEditorSheet.kt`

---

## 1. Original Defects & Root Causes (`META-02`)

1. **Duplicated Tag Engines (A2)**: `MetadataTagIO` and `LyricsTagIO` maintained separate, diverging ID3v2/FLAC/MP4/Ogg parsers and writers.
2. **ID3v2.4 Per-Frame Unsynchronization Corruption (A3)**: Preserved ID3v2.4 frames with flag bit `0x02` (per-frame unsync) had `removeUnsync(body)` applied during read, yet the raw `0x02` flag bit was preserved verbatim when rewriting — causing decoders and subsequent edits to double-de-unsynchronize `0xFF 0x00` bytes (corrupting JPEG `APIC` artwork and binary `PRIV` frames).
3. **ID3v2.2 Silent Tag Loss (A4)**: ID3v2.2 uses 3-char frame IDs and 3-byte sizes; writing an ID3v2.3 tag without migrating unknown 3-char frames silently discarded existing tags.
4. **MP4/M4A Broken Read-Back & Dropped Tags (A5)**: `MetadataTagIO.readMp4Metadata()` was a stub returning `null`; FFmpeg remuxes lacked `-movflags +use_metadata_tags+faststart` (dropping ReplayGain & freeform tags), omitted explicit comment clearing, lacked `mdat` SHA-256 verification, and lacked process timeouts. Furthermore, FFmpeg with `use_metadata_tags` stores custom keys in QuickTime `moov/meta/keys + ilst` (`mdta`) rather than iTunes `moov/udta/meta/ilst/----`, which `ReplayGainMetadataReader` previously did not parse.
5. **Ogg/Opus Silent Artwork Drop & FFmpeg Stream Tag Bug (A6)**: Artwork changes on Ogg/Opus were silently ignored; during real-device testing on vivo X200s, we also discovered FFmpeg's Ogg muxer requires `-metadata:s:a:0` (stream-level Vorbis/Opus comments) and container file extensions on temp outputs in `LyricsTagIO.embedLyricsIntoLocalFile`.
6. **Unvalidated FLAC `PICTURE` Blocks (A7)**: FLAC `PICTURE` blocks wrote `0x0` dimensions and performed no size, MIME, magic-byte, or 24-bit FLAC ceiling validation.
7. **Stale Media3 Metadata & Batch Cancel Dishonesty (A8)**: Edited metadata was not pushed to the live Media3 `MediaItem` (`replaceMediaItem`), and cancelling a batch edit left already-committed tracks unrefreshed without reporting `cancelled = true`.

---

## 2. Implementation Summary

- **Shared Primitives (`AudioContainerPrimitives.kt`)**:
  - Unified ID3v2 reader/writer: returns sealed `Id3v2ReadResult` (`NoTag`, `Supported`, `UnsupportedVersion(major)`). Explicitly rejects ID3v2.2 (`Unsupported`). Clears per-frame unsync bit `0x02` (and strips Data Length Indicator bit `0x01` + 4-byte prefix when consistent) after `removeUnsync(body)`.
  - Unified FLAC block parser + `validateAndBuildFlacPictureBlock()`: validates non-empty, `<= 4 MiB`, allowed MIME (`image/jpeg`, `image/png`, `image/webp`), header magic bytes, real pixel dimensions (`1..4096` via pure-Kotlin PNG IHDR / JPEG SOF / WebP parser), and FLAC 24-bit metadata block ceiling (`0xFFFFFF`).
  - Unified MP4 atom reader (`readMp4Metadata`): reads both iTunes `moov/udta/meta/ilst` (including `----` freeform atoms) AND QuickTime `moov/meta/keys + ilst` (`mdta`), plus `computeMp4MdatSha256(file)`. Shared with `ReplayGainMetadataReader.readMp4ReplayGain()`.
  - Unified Ogg/Opus reader (`extractOggCommentPacketFromFile` lifted to 4 MiB / 256 pages + `computeOggAudioPacketsSha256`).
- **FFmpeg Lossless Remux Hardening (`MetadataTagIO.kt` & `LyricsTagIO.kt`)**:
  - M4A/MP4: `-c copy -map_metadata 0 -movflags +use_metadata_tags+faststart`, explicit `-metadata comment=` for comment clearing, explicit preservation of `MDOWNLOAD_YT_ID`, `lyrics`, and ReplayGain/freeform tags, async stdout/stderr pipe drain, 30 s timeout + `destroyForcibly()`.
  - Ogg/Opus: explicit `MetadataWriteResult.Unsupported` on artwork add/replace/remove (and UI Choose/Remove controls disabled with clear message in `TrackMetadataEditorSheet.kt`); `-map_metadata:s:a:0 0:s:a:0` and `-metadata:s:a:0` for stream-level Vorbis/Opus comment updates; container extension preserved on `LyricsTagIO.embedLyricsIntoLocalFile` temp output.
- **Live Media3 Metadata Sync & Batch Cancel**:
  - `MediaItemMapper` propagates `albumArtist`, `genre`, `year`, `trackNumber`, and `dateModifiedSec`.
  - `PlaybackController.refreshTrackMetadataFromLibrary()` updates matching active/queued `MediaItem`s in-place via `Player.replaceMediaItem()` (with fresh artwork bytes when artwork changed) without restarting playback.
  - `MetadataRepository.saveBatchMetadata()` invokes `onTrackCommitted` per succeeded track and returns `BatchMetadataResult(..., cancelled = true)` on cancellation; `MainViewModel` refreshes all committed tracks inside `finally { withContext(NonCancellable) { ... } }`.

---

## 3. Metadata Integrity Verification Matrix

All tests executed on disposable fixtures only (zero real user music files modified).

| Format | Tag Read-Back | Comment Clear | ReplayGain Preserved | Lyrics Preserved / Re-Embedded | Artwork Handling | Audio Payload SHA-256 Identical | Execution Environment |
|---|---|---|---|---|---|---|---|
| **MP3 (ID3v2.3)** | PASS | PASS (`""`) | PASS (`-5.50 dB` TXXX) | PASS (`USLT` preserved & updated) | PASS (`APIC` replaced bit-exact) | PASS | JVM + **vivo X200s (`libffmpeg.so`)** |
| **MP3 (ID3v2.4 unsync)** | PASS (2 round-trips) | PASS | PASS (`-6.25 dB` TXXX) | PASS (`USLT` preserved & updated) | PASS (`0xFF 0x00` `APIC` & `PRIV` bit-exact) | PASS | JVM (`MetadataIntegrityP0Test`) |
| **MP3 (ID3v2.2)** | Rejected (`Unsupported`) | N/A | Preserved (file untouched) | Rejected (`Unsupported`) | Preserved (file untouched) | PASS (untouched) | JVM (`MetadataIntegrityP0Test`) |
| **FLAC** | PASS | PASS (`""`) | PASS (`-4.25 dB` Vorbis) | PASS (`LYRICS` preserved & updated) | PASS (validated dims, MIME, magic, `<=4 MiB`) | PASS | JVM + **vivo X200s (`libffmpeg.so`)** |
| **M4A / MP4 (AAC)** | PASS (`udta` + `mdta`) | PASS (`""`) | PASS (`-6.10 dB` survived FFmpeg remux) | PASS (`lyrics` preserved & updated) | PASS (`covr` supported) | PASS (`mdat` SHA-256 bit-exact) | JVM + **vivo X200s (`libffmpeg.so`)** |
| **Ogg / Opus** | PASS (`s:a:0` stream tags) | PASS (`""`) | PASS (`-3.50 dB` survived FFmpeg remux) | PASS (`LYRICS` preserved & updated) | **Rejected (`Unsupported`)** & disabled in UI | PASS (audio packets SHA-256 bit-exact) | JVM + **vivo X200s (`libffmpeg.so`)** |

---

## 4. Remaining Limitations

1. **ID3v2.2 editing**: Explicitly rejected (`Unsupported`) rather than lossily upgraded; users with legacy ID3v2.2 files receive a clear error message and their tags remain intact.
2. **Ogg/Opus artwork editing**: Explicitly unsupported due to Linux `execve` `MAX_ARG_STRLEN` (128 KiB) limits when passing Base64 `METADATA_BLOCK_PICTURE` via CLI FFmpeg arguments. Supporting Ogg artwork safely in a future milestone would require a native pure-Kotlin Ogg page/packet rewriter.
3. **MP4 `mdat` verification when artwork changes**: Because MP4 cover art is stored inside `moov` (or attached pic stream remuxed by FFmpeg), `mdat` SHA-256 verification applies to text/lyrics-only MP4 remuxes; artwork changes validate container structure and tag read-back.
