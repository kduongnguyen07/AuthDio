# HiFiDeck — Visual Reference Analysis

## 1. Authoritative Visual Reference

- **Reference Image URL:** `https://cdn.dribbble.com/userupload/44171393/file/original-91805e192340f45c80090fb68f1d77f2.png?resize=752x&vertical=center`
- **Inspected Views:** Two high-contrast dark mobile music player screens:
  1. **Left Screen (Now Playing):** Atmospheric dark player featuring a top orchid-plum ambient glow fading into pure obsidian, ultra-clean left-aligned light-weight title & artist header with a right-aligned heart/action outline, a **concentric frosted-glass orbital disc carousel** with circular artwork, peeking previous/next circular album discs on both edges, an integrated **circular progress arc** with centered elapsed time at the bottom notch, minimalist floating transport controls, and a floating smoked-glass bottom navigation dock with a glowing circular center artwork orb.
  2. **Right Screen (Track List / Library with Docked Mini-Player):** The same atmospheric obsidian-and-orchid backdrop hosting a **borderless, cardless vertical track list** (rounded-square artwork, crisp white title, muted lavender-gray artist, right-aligned tabular duration, hairline horizontal dividers) anchored above a **double-deck floating smoked-glass dock** combining a mini-player (with a subtle left-to-right translucent progress wash across its surface) and the 5-slot floating navigation pill.

---

## 2. Visual Deconstruction

### Color System (Derived from Reference)
| Role | Dark Token (Reference-Primary) | Approx. Hex | Usage |
| --- | --- | --- | --- |
| **Primary Canvas** | `ObsidianVoid` | `#050508` | Main viewport lower canvas and pure dark backdrop |
| **Secondary Canvas** | `ObsidianDeep` | `#0D0A14` | Elevated background layers, dialogs, and modal backdrops |
| **Atmospheric Glow Peak** | `AuraOrchidPeak` | `#9657A8` | Radial ambient glow blooming from top edge (subtly tinted by active artwork when playing) |
| **Atmospheric Glow Mid** | `AuraPlumMid` | `#5B2D70` | Mid-upper atmospheric wash blending seamlessly into void |
| **Atmospheric Glow Fade** | `AuraDeepFade` | `#1E0F2A` | Smooth falloff into pure obsidian at ~48% screen height |
| **Smoked Glass Surface** | `SmokedGlassSurface` | `#161221` (88% α) | Floating bottom dock, floating mini-player, bottom sheets, compact panels |
| **Smoked Glass Elevated** | `SmokedGlassElevated` | `#211B30` (92% α) | Active chips, inputs, interactive cards, selected states |
| **Specular Glass Rim** | `GlassRimTop` / `GlassRimBottom` | `#FFFFFF` @ 20% → 6% α | Top-lit hairline gradient border on floating glass capsules |
| **Primary Luminous Accent** | `OrchidAccent` | `#C78BFF` | Active toggles, selection highlights, interactive accents |
| **Progress Arc Gradient** | `PeriwinkleGlow` → `OrchidAccent` → `#FFFFFF` | `#7DB4FF` → `#C78BFF` → `#FFFFFF` | Circular orbital progress ring and active download progress |
| **Mini-Player Progress Wash** | `ProgressWashFill` | `#58427D` (38% α) | Left-to-right translucent progress fill sweeping inside the floating mini-player |
| **Primary Ink** | `InkPrimaryDark` | `#F8F7FB` | Titles, active icons, primary labels |
| **Secondary Ink** | `InkSecondaryDark` | `#9E95B0` | Artist names, subtitles, metadata |
| **Tertiary / Inactive Ink** | `InkTertiaryDark` | `#69617A` | Inactive icons, hints, disabled states |
| **Hairline Divider** | `HairlineDividerDark` | `#FFFFFF` @ 8% α | Subtle horizontal separators between borderless list rows |

A cohesive **Light Theme ("Pearl Orchid")** is also provided when the user explicitly selects Light mode in Settings (`#F7F5FA` canvas, soft `#DFC6EC` top radial ambient mist, translucent frosted white glass capsules, `#7B42B6` orchid accent, `#181322` primary ink).

### Typography
- **Character:** Clean geometric/humanist sans-serif with notable contrast between an **airy, thin/light display heading** (`Skin` at `30sp`, `FontWeight.Light`, `-0.5sp` tracking) and crisp medium-weight list titles (`15sp`, `FontWeight.Medium`).
- **Tabular Numerals:** All timestamps (`2:56`, `3:23`, `1:50 / 3:03`) and download speeds use `fontFeatureSettings = "tnum"` so live ticks never shift horizontally.
- **Alignment:** Left-aligned editorial titles and list text; centered minimalist metadata only where structurally anchored (e.g., bottom notch of the orbital progress ring).

### Layout & Proportions
- **Edge-to-Edge Canvas:** Full-bleed atmospheric gradient extending behind both the status bar and system gesture navigation bar.
- **Screen Margins:** `20dp`–`24dp` horizontal margins on Now Playing and list screens; `14dp` horizontal inset on the floating bottom dock.
- **Artwork Proportions:**
  - Now Playing center disc: `192dp` circle nested inside a `274dp` frosted-glass orbital halo ring, flanked by `148dp` peeking previous/next circular discs clipped cleanly at the left and right viewport edges.
  - Library track rows: `52dp × 52dp` rounded-square (`14dp` corner radius).
  - Mini-Player: `44dp × 44dp` rounded-square (`12dp` corner radius).
  - Album grid cards: `1:1` square with `18dp` rounded corners and clean title/artist below (no heavy surrounding border boxes).
- **Card Restraint:** Following the reference, individual track rows are **borderless and cardless**, separated only by fine `0.7dp` hairline dividers so artwork and typography breathe directly on the atmospheric canvas.

### Component Language
- **Top Chrome:** Minimal centered pill handle (`36dp × 4dp`, white @ 30% α) with thin chevron/back on the left and overflow/utility icons on the right — replacing heavy opaque `TopAppBar` boxes.
- **Floating Unified Bottom Dock:** Smoked-glass pill (`30dp` radius, `64dp` height) with 5 balanced slots (`Home`, `Library`, **Center Glowing Circular Artwork Orb → Now Playing**, `Downloads`, `Settings`). When playback is active, the **Floating Mini-Player** (`26dp` radius) stacks seamlessly above it with a live left-to-right translucent progress wash.
- **Transport Controls:** Pure, crisp white vector glyphs (`Shuffle`, `Previous`, `Play/Pause`, `Next`, `Repeat`) directly on the obsidian canvas — no bulky circular button containers or heavy Material FABs.

---

## 3. Functional Adaptation for HiFiDeck

HiFiDeck is both an **offline-first Media3 music player** and a **yt-dlp + FFmpeg audio downloader**. To preserve 100% of existing capabilities while staying visually true to the reference:

1. **Navigation Architecture (5-Slot Floating Dock + Discover Access):**
   - Slot 1: **Home** — Music-first entry point (Continue Listening, Quick URL Download capsule with inline format profiles, Recently Added Albums, Recently Downloaded Tracks, and 1-tap YouTube Search).
   - Slot 2: **Library** — Reference-style borderless track list (`Songs`), plus `Albums`, `Artists`, `Playlists`, and `Folders` via sleek smoked-glass filter pills.
   - Slot 3 (Center Glowing Orb): **Now Playing** — Displays the current track's circular artwork (or signature iridescent HiFiDeck orb when idle); tapping opens Now Playing directly (or starts playback from the library when idle).
   - Slot 4: **Downloads** — Unified smoked-glass Queue & History hub with live glowing gradient progress bars, parallel/thread steppers, reorder controls, retry-failed actions, and instant Play / Add-to-Queue on completed tracks.
   - Slot 5: **Settings** — Cohesive obsidian-orchid expandable settings sections preserving all existing options, OriginOS battery guidance, storage folder picker, and test tags.
   - **Discover (YouTube Search):** Accessible in 1 tap from the top Search action on Home/Downloads/Library (opens the redesigned native YouTube Search screen with a clean back chevron).
2. **Non-Decorative Interactive Enhancements:**
   - The reference's circular progress ring is made **fully interactive** (drag along the ring to scrub, plus tap peeking left/right album discs or swipe horizontally to skip previous/next).
   - The reference's heart icon maps to HiFiDeck's real **Add to Playlist** action, and the top-right overflow (`⋮`) exposes **Playback Speed**, **Playing Queue**, and **Audio Codec Info** without cluttering the serene Now Playing layout.
