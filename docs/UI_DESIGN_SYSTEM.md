# AuthDio — Unified UI Design System ("Obsidian Orchid")

> **Branding Note:** AuthDio was formerly known as HiFiDeck / MDownload.

## 1. Architecture Overview

All visual styling is centralized under `com.example.mdownload.ui.design` and shared primitives under `com.example.mdownload.ui.components`, eliminating scattered styling constants across screens.

```text
app/src/main/java/com/example/mdownload/
├── ui/
│   ├── design/
│   │   ├── Color.kt           # HiFiColorTokens (Dark Obsidian Orchid + Light Pearl Orchid) & artwork tint extractor
│   │   ├── Typography.kt      # Display light headers, medium track titles, tabular numerals (tnum)
│   │   ├── Shapes.kt          # Consistent corner radii (dock 30dp, miniPlayer 26dp, card 20dp, artRow 14dp, pill 999dp)
│   │   ├── Spacing.kt         # 4dp-based rhythm scale + dock clearance constants
│   │   ├── Motion.kt          # Restrained native Compose spring & tween motion specs
│   │   └── HiFiDeckTheme.kt   # Theme wrapper, LocalHiFiColors CompositionLocal, Material3 bridge
│   ├── components/
│   │   ├── AtmosphericSurface.kt # Full-bleed radial ambient aura canvas + SmokedGlassSurface with specular top rim
│   │   ├── AppNavigation.kt   # Floating 5-slot smoked-glass dock + Center Glowing Artwork Orb + stacked MiniPlayer
│   │   ├── TrackRow.kt        # Reference-style borderless track row (52dp rounded art, title/artist, tabular duration, hairline divider)
│   │   ├── MusicCard.kt       # Editorial artwork cards for Albums, Playlists, and Continue Listening
│   │   ├── ProgressIndicator.kt # Glowing orbital circular scrubber + linear gradient progress bar
│   │   ├── SearchField.kt     # Smoked-glass capsule search & URL input fields + pill filter chips
│   │   ├── EmptyState.kt      # Minimal centered empty/error/permission states
│   │   ├── CommonComponents.kt# Backwards-compatible styled cards, pills, and section headers
│   │   └── Modals.kt          # Smoked-glass MetadataEditorDialog & PlaylistTrackPickerDialog
│   └── screens/
│       ├── HomeScreen.kt      # Music-first hub: Continue listening, Quick download, Recent albums & tracks
│       ├── DiscoverScreen.kt  # Native YouTube music search in Obsidian Orchid language
│       ├── DownloadsScreen.kt # Queue, History, multithread controls, live glowing download progress
│       └── SettingsScreen.kt  # Harmonized collapsible groups across all settings categories
└── player/ui/
    ├── TrackArtworkBox.kt     # Cached async artwork box with squircle/circle support & subtle ambient glow
    ├── MiniPlayer.kt          # Floating smoked-glass mini-player with left-to-right progress wash
    ├── NowPlayingScreen.kt    # Signature orbital disc carousel, circular progress scrubber, minimal transport row
    ├── PlaybackQueueSheet.kt  # Smoked-glass modal queue sheet with live reorder & removal
    ├── AddToPlaylistDialog.kt # Smoked-glass playlist assignment dialog
    └── LibraryHubScreen.kt    # Borderless Songs list, Albums grid, Artists, Playlists, Folders & detail views
```

---

## 2. Token Specifications

### Spacing Scale (`HiFiSpacing`)
- `xxs = 4.dp` — Micro icon/label gaps
- `xs = 8.dp` — Chip spacing, tight inline rows
- `sm = 12.dp` — Compact list/row padding
- `md = 16.dp` — Standard horizontal margin and card padding
- `lg = 20.dp` — Screen-level content horizontal padding
- `xl = 24.dp` — Now Playing horizontal padding & major section gaps
- `xxl = 32.dp` — Generous vertical breathing room
- `dockBaseClearance = 96.dp` / `dockWithMiniPlayerClearance = 176.dp` — Bottom scroll clearance so content glides smoothly behind the floating smoked-glass dock without ever being obscured.

### Shape Scale (`HiFiShapes`)
- `dockCapsule = RoundedCornerShape(32.dp)` — Floating bottom navigation pill
- `miniPlayer = RoundedCornerShape(26.dp)` — Floating docked mini-player
- `sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)` — Modal bottom sheets
- `card = RoundedCornerShape(20.dp)` — Compact glass cards and dialogs
- `input = RoundedCornerShape(16.dp)` — Search fields and URL inputs
- `artworkRow = RoundedCornerShape(14.dp)` — Track list thumbnails (`52dp`)
- `artworkMini = RoundedCornerShape(12.dp)` — Mini-player thumbnails (`44dp`)
- `artworkGrid = RoundedCornerShape(18.dp)` — Album/playlist grid artwork
- `pill = RoundedCornerShape(999.dp)` — Filter chips, status pills, action capsules

### Typography (`HiFiTypography`)
- `displayLarge` (`30sp`, `Light`, `-0.5sp` tracking) — Now Playing track title
- `headlineMedium` (`24sp`, `Light`, `-0.3sp` tracking) — Screen headers
- `headlineSmall` (`20sp`, `Medium`, `-0.2sp` tracking) — Brand wordmark & section hero titles
- `titleMedium` (`15sp`, `Medium`) — Track titles, group headers
- `titleSmall` (`14sp`, `Medium`) — Mini-player title, compact card titles
- `bodyMedium` (`13.5sp`, `Normal`) — Artist subtitles, descriptions
- `bodySmall` (`12.5sp`, `Normal`) — Secondary metadata, row artist subtitles
- `labelMedium` (`12sp`, `Medium`, `tnum`) — Tabular track durations, seek timestamps, counters
- `labelSmall` (`11sp`, `Medium`, `tnum`) — Audio codec specs, badges

### Motion (`HiFiMotion`)
- `AuraTintDurationMs = 700` — Smooth crossfade when ambient top glow subtly adapts to active track artwork
- `MiniPlayerEnterExitMs = 260` — Crisp vertical dock transition
- `PlayPauseMorphMs = 180` — Responsive play/pause state feedback
- `NowPlayingTransitionMs = 300` — Smooth full-screen expansion & collapse
- All decorative/ambient animations are restrained and stateless so they never cause excessive recomposition or compete with Media3 playback.
