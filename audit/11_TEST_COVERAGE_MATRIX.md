# HiFiDeck — Complete Test Coverage Matrix

**Audit Scope:** Exact inventory of all **71 JVM unit tests** (`app/src/test`) and **15 Android instrumented tests** (`app/src/androidTest`), mapped to features, verification outcomes, and explicit coverage gaps.  
**Audit Date:** 2026-11-24

---

## 1. Test Suite Summary

| Suite | Location | Test Files | Total `@Test` Methods | Executed During Audit | Passed | Failed | Skipped / Blocked |
|---|---|---:|---:|---:|---:|---:|---:|
| **JVM Unit Tests** | `app/src/test/java/com/example/mdownload/` | 9 | **71** | **71** | **71** | 0 | 0 |
| **Android Instrumented Tests** | `app/src/androidTest/java/com/example/mdownload/` | 4 | **15** | **11** (+1 attempted/stalled) | **11** | 0 | 4 (2 opt-in live-network, 2 Compose-rule blocked) |
| **Total** | — | **13** | **86** | **82** | **82** | **0** | **4** |

---

## 2. Complete JVM Unit Test Inventory (`:app:testDebugUnitTest` — 71/71 PASS)

Executed via `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks` → **71 passed, 0 skipped, 0 failed**.

| Test File | # | Test Method | Feature Domain Covered |
|---|---:|---|---|
| **`AdvancedAudioFeaturesTest.kt`** (16) | 1 | `replayGain_dbToLinearAndBackAreAccurate` | ReplayGain math (`ReplayGainCalculator`) |
| | 2 | `replayGain_modeSelectionAndAlbumFallback` | ReplayGain `TRACK`/`ALBUM`/`OFF` modes |
| | 3 | `replayGain_missingMetadataAndClippingPrevention` | Missing-tag preamp & peak clipping protection |
| | 4 | `replayGain_composesCleanlyWithSleepFade` | ReplayGain × Sleep Timer linear composition |
| | 5 | `replayGain_parsesOpusR128Q78AndEbuR128ScannerOutput` | Opus RFC 7845 Q7.8 & FFmpeg `ebur128` parser |
| | 6 | `replayGain_readsId3v2TxxxAndFlacVorbisTagsFromFiles` | Synthetic MP3 ID3v2 `TXXX` & FLAC Vorbis RG reader |
| | 7 | `audioInspector_parsesFlacStreamInfoAndLosslessCaveatTruthfully` | FLAC `STREAMINFO` & app-download transcode caveat |
| | 8 | `audioInspector_parsesMp3HeadersWithoutFakeBitDepth` | MP3 MPEG/Xing parser (no fake bit depth) |
| | 9 | `audioInspector_parsesWavAndHandlesCorruptFilesSafely` | WAV `RIFF`/`fmt ` parser & corrupt file safety |
| | 10 | `metadataValidator_validatesSingleAndBatchRules` | Single & batch metadata validation rules |
| | 11 | `metadataTagIO_mp3RoundTripPreservesLyricsReplayGainYtIdUnknownFramesAndAudioPayload` | Synthetic ID3v2.3 MP3 rewrite + audio SHA-256 |
| | 12 | `metadataTagIO_flacRoundTripPreservesCommentsLyricsReplayGainAndAudioPayload` | Synthetic FLAC rewrite + audio SHA-256 |
| | 13 | `batchMetadataPatch_preservesTitleTrackNumberAndUncheckedFields` | Batch metadata selective patch rules |
| | 14 | `smartPlaylists_evaluatesAll7BuiltInsAndCustomAndOrRules` | All 7 built-in Smart Playlists + custom AND/OR rules |
| | 15 | `listeningSessionTracker_countsQualifiedPlaysExcludesSeeksAndHandlesRepeatOne` | Qualified play threshold, seek exclusion, Repeat-1 |
| | 16 | `sleepTimerMath_monotonicRemainingFadeCurveAndExtension` | Sleep Timer countdown, fade curve & extension math |
| **`CoreDomainTest.kt`** (12) | 17 | `urlParser_validatesAndNormalizesSupportedYoutubeLinks` | YouTube URL validation & normalization |
| | 18 | `urlParser_supportsYtDlpSourcesAndSmartSharePayloads` | Generic yt-dlp URLs (SoundCloud, etc.) |
| | 19 | `urlParser_preservesVideoIdForRdMixPlaylists` | YouTube RD Mix playlist handling |
| | 20 | `urlParser_extractsUrlsFromSharedYoutubeText` | Share sheet text URL extraction |
| | 21 | `textUtils_handlesVietnameseAsciiAndTitleCleanup` | Vietnamese diacritic folding & title cleanup |
| | 22 | `textUtils_profileSuffixesAndArchiveNamesMatchHiFiDeck` | Audio profile suffixes & archive naming |
| | 23 | `taskFeedback_detectsRateLimitAndBotVerification` | HTTP 429 & YouTube bot-check detection |
| | 24 | `appSettings_effectiveMultithreadClampingAndGentleOverride` | Download concurrency clamping & gentle mode |
| | 25 | `audioTagFingerprint_extractsHiddenVideoIdFromBytesAndFilename` | `MDOWNLOAD_YT_ID` duplicate fingerprinting |
| | 26 | `libraryQueryHelper_filtersByScopeAccentInsensitivelyAndSortsAscDesc` | Library search scopes & Vietnamese accent-insensitive sort |
| | 27 | `storageFolder_formatsTreeDocumentIdsAndEffectiveLabels` | SAF document tree path label formatting |
| | 28 | `queueOrderHelper_reordersQueuedJobsWithoutCrossingActiveDownloads` | Download queue drag-reorder invariants |
| **`CircularScrubTest.kt`** (10) | 29 | `clockwiseDragIncreasesPreviewPosition` | Clockwise circular scrub delta |
| | 30 | `counterclockwiseDragDecreasesPreviewPosition` | Counterclockwise circular scrub delta |
| | 31 | `wrapAroundAcrossPiSeamHasNoJumpOrDiscontinuity` | `±π` angle seam wraparound continuity |
| | 32 | `clampsCleanlyAtZeroAndDurationAndRecoversImmediatelyOnReverse` | `[0, duration]` clamp & immediate reverse |
| | 33 | `microJitterAndPlainTapAreIgnoredWithoutSeekCommit` | Micro-jitter / tap deadband filtering |
| | 34 | `cancelAndMidDragTrackChangeNeverCommitSeek` | Gesture cancellation & track-change abort |
| | 35 | `multiRevolutionScrubbingWorksSmoothlyOnLongAudioFiles` | Multi-revolution 5-min cap on long tracks |
| | 36 | `ignoresUnknownZeroDurationAndUnseekableMedia` | Unseekable / zero-duration guard |
| | 37 | `commitsSingleSeekTargetOnReleaseOnly` | Commit-on-release-only seek behavior |
| | 38 | `touchZonesStayDisjointFromInnerArtworkAndDeltaFormatsCleanly` | `CircularScrubConfig` polar radius bounds (math only) |
| **`LyricsPipelineTest.kt`** (8) | 39 | `parsesValidLrcWithMultipleTimestampsOffsetsAndHeaderTags` | LRC multi-timestamp, offset & header tag parsing |
| | 40 | `ignoresMalformedTimestampsAndPreservesValidLines` | Malformed LRC line tolerance |
| | 41 | `preservesVietnameseUtf8TextLosslessly` | UTF-8 Vietnamese lyrics fidelity |
| | 42 | `handlesPlainLyricsMissingLyricsAndDuplicateTimestamps` | Plain text / empty / duplicate timestamp handling |
| | 43 | `syncEngineTracksSeeksOffsetsIntroInstrumentalGapsAndPlaybackSpeed` | Sync engine seek, offset, intro gap & speed |
| | 44 | `matcherNormalizesMetadataRanksCandidatesAndRejectsLowConfidenceMatches` | LRCLIB candidate scoring & rejection |
| | 45 | `mp3Id3v2UsltAndSyltRoundTripPreservesExistingTagsCoverAndAudioStreamBitForBit` | `LyricsTagIO` MP3 USLT/SYLT round-trip |
| | 46 | `flacVorbisCommentRoundTripPreservesMetadataBlocksAndAudioStreamBitForBit` | `LyricsTagIO` FLAC Vorbis lyrics round-trip |
| **`ArtistManagementTest.kt`** (7) | 47 | `createCustomArtist_persistsCoverAndAppearsEvenWhenEmpty` | Custom artist creation & cover |
| | 48 | `editAutoDiscoveredArtist_renamesTracksAndAppliesCustomCover` | Artist rename alias & custom cover |
| | 49 | `editArtist_mergesIntoExistingArtistWhenNameMatches` | Artist merge on matching name |
| | 50 | `syncArtistTracks_addsMislabeledSongsAndRemovesUncheckedSongs` | Bulk artist track reassignment |
| | 51 | `assignSingleTrackAndReset_restoresOriginalTag` | Single track artist override & reset |
| | 52 | `deleteArtist_revertsOverridesAndAliasesWithoutDeletingTracks` | Artist delete without deleting audio files |
| | 53 | `clearCoverAndRemoveDeletedTrack_cleanStateDeterministically` | Cover cleanup & deleted track pruning |
| **`OrbitalNavigationTest.kt`** (7) | 54 | `emptyAndSingleSongQueue_handledSafelyWithoutCrashing` | Empty & 1-track orbital session guards |
| | 55 | `upwardDragAdvancesToNextTracks_downwardDragMovesToPreviousTracks` | Vertical drag direction mapping |
| | 56 | `smallMovementsWithinHysteresisBand_doNotTriggerAccidentalTrackSwitch` | Drag hysteresis deadband |
| | 57 | `releaseWithoutChangingSelection_returnsNullSoPlaybackDoesNotRestart` | Release-without-change no-op |
| | 58 | `boundaryClampingAndMidDragQueueMutation_stayWithinValidBounds` | Queue bounds & mid-drag mutation safety |
| | 59 | `playbackUiState_respectsShuffleOrderAndMapsBackToMedia3TimelineIndex` | Shuffle order → Media3 timeline mapping |
| | 60 | `orbitalGeometry_curvesAcrossScreenSizes_leavesSpaceForLeftLabel_andWindowsLargeQueues` | Orbital arc layout math across viewports |
| **`MusicPlayerDomainTest.kt`** (6) | 61 | `deduplicateTracks_mergesDuplicateUrisAndOverlappingSafMediaStoreEntries` | MediaStore + SAF track deduplication |
| | 62 | `libraryGrouping_groupsAlbumsArtistsAndFoldersCleanly` | Album / Artist / Folder grouping |
| | 63 | `libraryQueryHelper_sortsByAlbumAndMissingAlbumLast` | Album sorting with missing-album-last |
| | 64 | `repeatModeAndAudioOutputInfo_formatAndCycleAccurately` | RepeatMode cycling & output formatting |
| | 65 | `playbackUiState_computesNavigationAvailabilityProperly` | Prev/Next availability flags |
| | 66 | `playerSettingsDefaultsAndVietnameseLocalizationWork` | Player defaults & Vietnamese strings |
| **`SettingsPolicyTest.kt`** (4) | 67 | `wifiOnlyBlocksMobileAndResumesOnWifi` | Wi-Fi-only download wait policy |
| | 68 | `backgroundSettingDoesNotBlockForeground` | Background download policy |
| | 69 | `offlineAlwaysWaits` | Offline download wait policy |
| | 70 | `localizationPreservesTitlesAndTranslatesQueueCounts` | Localization formatting |
| **`ExampleUnitTest.kt`** (1) | 71 | `addition_isCorrect` | Template sanity check |

---

## 3. Complete Android Instrumented Test Inventory (`app/src/androidTest` — 15 Methods)

Executed on **vivo X200s (`10AF8A0YUH005HA`, OriginOS 7 / Android 17, 153 MediaStore tracks)**:

| Test File | # | Test Method | Audit Status | Notes / Evidence |
|---|---:|---|---|---|
| **`ExampleInstrumentedTest.kt`** (1) | 1 | `useAppContext` | **PASS (vivo X200s)** | Package `com.example.mdownload` verified. |
| **`MusicPlayerInstrumentedTest.kt`** (10) | 2 | `playbackStateStore_roundTripsQueuePositionAndModes` | **PASS (vivo X200s)** | Queue/position/shuffle/repeat JSON persistence. |
| | 3 | `playlistStore_roundTripsPlaylistsAtomically` | **PASS (vivo X200s)** | Atomic manual playlist persistence. |
| | 4 | `mediaItemMapper_preservesFullTrackMetadataAndExtras` | **PASS (vivo X200s)** | Note: does not catch missing `dateModifiedSec`. |
| | 5 | `manifest_exposesMediaSessionServiceAndMediaButtonReceiver` | **PASS (vivo X200s)** | Manifest service & receiver resolution. |
| | 6 | `realDevicePlayback_exercisesMedia3ServiceQueueControlsAndMetadata` | **PASS (vivo X200s)** | Live Media3 service bind, play/pause, queue ops on device audio. |
| | 7 | `artistStore_roundTripsUtf8NamesCoversOverridesAndAliases` | **PASS (vivo X200s)** | UTF-8 artist catalog persistence. |
| | 8 | `listeningHistoryAndSmartPlaylistRepos_roundTripPersistAtomically` | **PASS (vivo X200s)** | Listening history & smart playlists persistence. |
| | 9 | `replayGainAudioProcessorAndCoordinator_scalesPcmAndRestoresAfterSleepFade` | **PASS (vivo X200s)** | Direct processor PCM16 scaling + coordinator composition (note: does NOT test `isProcessingPcm` `false→true` transition bug `AUD-01`). |
| | 10 | `sleepTimerController_integratesWithGainCoordinatorStartExtendAndCancel` | **PASS (vivo X200s)** | **Previously unexecuted in prior report; newly verified passing in `4.953s` batch.** |
| | 11 | `audioAnalysisRepository_inspectsRealTrackCachesAndInvalidatesCleanly` | **PASS (vivo X200s)** | **Previously unexecuted in prior report; newly verified passing against real track in `4.953s` batch.** |
| **`SettingsDeviceTest.kt`** (3) | 12 | `persistenceAndCleanupProtectPendingAudio` | **BLOCKED (Stalled)** | Hung on vivo X200s due to `createAndroidComposeRule<MainActivity>()` lifecycle stall (killed cleanly). |
| | 13 | `storageCanRecoverAndVerifyRealAudio` | **SKIPPED (Opt-in)** | Requires `-e liveDownload true` (performs real download; intentionally skipped). |
| | 14 | `settingsSectionsThemeAndConfirmation` | **NOT RUN (Blocked rule)** | Shares `createAndroidComposeRule<MainActivity>()` with stalled test #12. |
| **`LiveDownloadTest.kt`** (1) | 15 | `downloadExportAndPlayback` | **SKIPPED (Opt-in)** | Requires `-e liveDownload true` (downloads real YouTube track; intentionally skipped). |

---

## 4. High-Risk Coverage Gaps (Why Confirmed P0/P1 Bugs Escaped Existing Tests)

| Confirmed Bug | Why Existing Tests Missed It | Required New Test |
|---|---|---|
| **`AUD-01`** (Double ReplayGain attenuation on PCM transition) | `replayGainAudioProcessorAndCoordinator_scalesPcmAndRestoresAfterSleepFade` configures the processor *before* applying gain via coordinator and never attaches a real/fake `Player` to assert `player.volume == 1.0f` after `onConfigure()`. | Coordinator ↔ Processor lifecycle test: apply gain before `onConfigure()`, then call `onConfigure()` and assert `player.volume == 1.0f`. |
| **`AUD-02`** (Sleep Timer expiry spike + `END_OF_QUEUE` Repeat bug) | `sleepTimerController_integratesWithGainCoordinatorStartExtendAndCancel` only tests start/extend/cancel — never actual expiration or `END_OF_QUEUE` with `REPEAT_MODE_ALL`/`REPEAT_MODE_ONE`. | Unit tests for `expireAndPause()` fade retention until paused + `END_OF_QUEUE` across all 3 `RepeatMode`s. |
| **`META-01` & `META-02`** (ID3v2.4 unsync, ID3v2.2 wipe, MP4/Ogg rewrites, `wt` failure) | `AdvancedAudioFeaturesTest` only tests clean synthetic ID3v2.3 MP3 and FLAC files on local `File` paths — **zero tests** for ID3v2.4 unsync, ID3v2.2, MP4/M4A, Ogg/Opus, or `content://` `MetadataRepository` writes! | Add golden fixtures for ID3v2.4 unsync, ID3v2.2, MP4 (checking ReplayGain + read-back + comment clear), Ogg artwork, FLAC >16 MiB, and simulated `OutputStream` failure rollback. |
| **`UI-01` & `UI-02`** (Diagonal seek dead zones & Light-Mode sheets) | `CircularScrubTest#touchZonesStayDisjointFromInnerArtworkAndDeltaFormatsCleanly` only tests polar math constants in `CircularScrubConfig`, **not Compose pointer hit-testing** on `CircularAlbumArtwork`. Zero Compose UI tests render Light Theme sheets. | Compose `pointerInput` hit-test at 45°/135°/225°/315° on `CircularAlbumArtwork`; Compose Light-Theme contrast check on all 4 bottom sheets. |
| **`SEC-01` / `SEC-02` / `SEC-04`** (`HF_FILE`, regex injection, SSRF) | `CoreDomainTest` tests valid YouTube/SoundCloud URLs only — no adversarial hostnames (`[::ffff:127.0.0.1]`, numeric IPv4), no backslash titles, no `resolveProducedFile()` traversal tests. | Add adversarial JVM security unit tests covering `YouTubeUrlParser`, `YtDlpEngine` escaping, and `DownloadQueueManager.resolveProducedFile()`. |
| **`PERF-03`** (`O(N log N × M)` Smart Playlists) | `smartPlaylists_evaluatesAll7BuiltInsAndCustomAndOrRules` runs on a tiny fixture (<10 tracks). | Add 5,000-track Smart Playlist evaluation benchmark assertion (<50 ms). |
