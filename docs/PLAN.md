# Implementation plan — "Ondo"

**English** · [Français](PLAN.fr.md)

IPTV player (movies, series, live TV) for **iPhone**, **iPad** and **macOS**, in native Swift/SwiftUI,
under the MIT license. Ondo provides no content: it plays the sources added by the user. The Android app
follows its own plan: [ANDROID.md](ANDROID.md).

## 1. Technical choices

| Topic | Choice | Why |
|---|---|---|
| Language / UI | Swift 5.10+ (Swift 6 compiler), SwiftUI, Observation (`@Observable`) | a single codebase for iPhone + native Mac |
| Targets | **iOS 17+**, **macOS 14+** (iPad included via the iOS target) | `@Observable`, `NavigationSplitView`, `ContentUnavailableView` |
| Xcode project | **XcodeGen** (`project.yml`) | readable, versionable project, no hand-written `.pbxproj` |
| Business logic | SPM package **`IPTVCore`** (pure Foundation) | testable on Linux and in CI, reusable (tvOS later) |
| Playback | AVFoundation (`AVPlayer`) and **VLCKit** 3.6 (LGPL), chosen by the `PlayerEngine` setting (automatic / Apple / VLC); on iPhone/iPad, MKV films remuxed to HLS (fragmented MP4, no transcoding) and served to `AVPlayer` on 127.0.0.1 | native PiP, AirPlay, HDR; VLC for what `AVPlayer` cannot open (AVI, raw `.ts`, MKV on the Mac…) |
| Storage | versioned JSON files in *Application Support* + Keychain for passwords; *Caches* folder for catalogues, guides and per-playlist details; Nuke disk cache for pictures | simple, robust, exportable; SQLite if performance requires it |
| Networking | `URLSession`, per-playlist UA/Referer headers, gzip via zlib | |
| Tests | XCTest (`swift test` on Linux/Docker and macOS) | |
| CI | GitHub Actions: Linux tests + iOS Simulator build + macOS build | also catches regressions in the Mac version |

## 2. Architecture

```
ondo-iptv/
├── project.yml                     # XcodeGen: "Ondo" target, iOS + macOS
├── Packages/IPTVCore/              # business logic, no UI (Sources/IPTVCore; Sources/CZlib wraps the system zlib)
│   ├── Models/        Playlist, Channel, Movie, Series, Episode, EPGProgram, FavoriteEntry, HistoryEntry, PlaybackItem
│   ├── Parsing/       M3UParser, M3UContentBuilder, XSPFParser, JSONPlaylistParser, PlaylistFormat, SubtitleParser
│   ├── Xtream/        XtreamClient (API), tolerant DTOs, XtreamURLBuilder (live/vod/series/timeshift)
│   ├── EPG/           XMLTVParser (SAX + gzip), EPGIndex (now/next, channel matching)
│   ├── Catchup/       CatchupURLBuilder (default/append/shift/flussonic/xtream)
│   ├── Library/       PlaylistLoader, SearchEngine, UserLibrary (favorites, history, resume), FileStore, DetailsArchive
│   ├── Metadata/      TMDbClient, People, AwardsCatalogue
│   ├── Playback/      PlaybackHealth, PlaybackLog, ProblemReport, BufferPolicy
│   ├── Remux/         MatroskaRemux (MKV → HLS of fragmented MP4), EBML, Matroska, FragmentWriter
│   ├── Subtitles/     OnlineSubtitles, OpenSubtitles, SubDL, SubSource, Addic7ed
│   ├── Networking/    HTTPClient, TransferMeter, DownloadDiagnosis + DownloadReport (downloads, TestFlight edition)
│   └── Utilities/     Gzip (CZlib), ZipArchive, TextUtilities
├── App/
│   ├── OndoApp.swift               # scenes: main window, player windows, Multiview, Help, Settings (the last four on Mac)
│   ├── Model/         AppModel (global state, AppModel+… extensions), AppSettings, AppPaths, Keychain, PlaybackSession
│   ├── Features/      Root, Onboarding, Home, Live, Movies, Series, Guide, Search, Favorites, Playlists, Settings…
│   ├── Player/        PlayerController, PlayerView, PlayerEngine + VLCBackend, RemuxServer, CastManager, PiP, zapping
│   └── Shared/        RemoteImage (Nuke), components, platforms (#if os)
└── .github/workflows/ci.yml
```

Data flow: `Playlist` → `PlaylistLoader` (M3U/Xtream) → `PlaylistContent` cached on disk →
`AppModel` (`@Observable`) → views. The EPG is loaded in the background → `EPGIndex` → now/next, grid, catch-up.

Navigation: **iPhone** = `TabView` (Home, Movies, Series, Live TV, My List); Search from the magnifying glass of
every tab, TV Guide in Live TV, Settings on Home. **iPad** and **Mac** = `NavigationSplitView` with a sidebar
(Home, Movies, Series, Live TV, TV Guide, My List, Search, then the playlists); Settings from the sidebar's gear
on iPad, native `Settings` on Mac. On the Mac: "Playback" menu with shortcuts, player in its own window (several
windows = multi-screen).

## 3. Phases and deliverables

### Phase 0 — Scoping ✅
- [x] Inventory of the features expected from an IPTV player, formats and protocols (M3U, Xtream, XMLTV, catch-up)
- [x] Plan, architecture, priorities → this document

### Phase 1 — `IPTVCore` core (P1) ✅
- [x] Data models + stable identifiers (FNV hash, for persistent favorites/history)
- [x] Extended M3U parser at byte level (attributes, headers, `EXTVLCOPT`, `EXTGRP`, `KODIPROP`, `|User-Agent=`)
- [x] Live/Movies/Series classification + episode grouping (`S01E02`, `1x02`, "Saison 2 Épisode 3")
- [x] XSPF, JSON and plain URL list parsers
- [x] Full Xtream Codes client + tolerant decoding + URL building + "Force M3U"
- [x] Streaming XMLTV parser (gzip, time window and channel filters, offset) + `EPGIndex` (id, case, name)
- [x] Catch-up URL builder (default, append, shift, flussonic, xtream + tokens)
- [x] Normalized search (accents, case, prefixes, ranking)
- [x] Versioned JSON storage with a fallback copy, tolerant decoding, backup/restore
- [x] SRT / WebVTT subtitles, TMDb trending + matching against the catalogue
- [x] Unit tests: 66 at the end of phase 1, 223 today (+ performance guards: 60,000 entries, 100,000 programmes)

### Phase 2 — iPhone + Mac MVP app (P1) ✅
- [x] XcodeGen project, Info.plist (ATS, background audio), Mac sandbox, original icon
- [x] Onboarding + adding a playlist (Xtream, M3U URL, file, public demo) with detection of Xtream links
- [x] Playlist management: edit, refresh (auto), delete, reorder, additional EPGs, UA/Referer
- [x] Home: Continue Watching, Trending, Favorites, recent channels, Recently Added, account status
- [x] Live: categories → channels (logo, number, current programme + progress, catch-up badge)
- [x] Movies / series: poster grids, sorting, detail pages, seasons, episodes, resume, auto next episode
- [x] Global search (scopes), reorderable favorites, history
- [x] Player: zapping (list + ↑/↓), EPG overlay, tracks, aspect ratio, speed, PiP, AirPlay, lock, sleep timer,
      resume, Mac keyboard shortcuts, one window per stream on Mac, "Now Playing" / media keys
- [x] URL fallback (`.ts → .m3u8`) and switch to another version of the channel (HD/FHD/backup), auto reconnect,
      restart when stalled, errors attributed to their source
- [x] Grid TV Guide + programme details + catch-up
- [x] Parental control: PIN, adult categories locked automatically
- [x] Xtream account info + connection test
- [x] Full settings; localization in English, French, Spanish, Portuguese, Chinese (Simplified), Arabic and Hindi

### Phase 3 — Advanced player (P2) ✅
- [x] **VLCKit** 3.6 engine (LGPL): MKV, AVI, raw `.ts`…; automatic mode with fallback if AVPlayer fails
- [x] External SRT/WebVTT subtitles with offset; audio/subtitle preferences remembered
- [x] iPhone gestures (double-tap ±10 s, swipe to seek, brightness), landscape forced by content type
- [x] "Force M3U" for Xtream accounts

### Phase 4 — Library and sync (P2) ✅
- ~~iCloud sync~~: removed (decision of 13 Oct); the library keeps its deterministic merge
      (deletion markers), useful for a future sync or for restoring backups
- [x] Profiles ("Who's watching?"), profile PIN, kids profiles
- [x] Custom list manager, hidden categories

### Phase 5 — Extras (P3)
- [x] Offline downloads of movies/episodes (TestFlight edition; background session, pause/resume)
- [x] TMDb trending (API key supplied by the user)
- [x] Chromecast (official Google Cast SDK, iPhone/iPad) + AirPlay (iPhone, iPad, Mac)
- [x] ~~Real-Debrid source~~: removed (October 2026): code no build could use is a dormant feature for App Review
- [x] Donations on the website and in the README (GitHub Sponsors), never from inside the apps
- [ ] Optional tips inside the apps (StoreKit 2 / Play Billing), unlocking nothing: planned for 1.2.0
- [ ] tvOS / visionOS targets (the core is already compatible)

### Phase 6 — Advanced features ✅ in part
Features deemed essential for a modern player, handled in milestones:
- [x] **M1** Last Channel (button, ⌫ / ⌘⌫), zapping by number from the keyboard, channel side panel
      (search by name/number) + the channel's guide in the player
- [x] **M2** restart the current programme, rewatch a past programme from the player, Back to
      Live; pause/rewind live TV (HLS DVR window, otherwise resume via the provider's archive)
- [x] **M3** programme reminders (local notifications, adjustable lead time); "sticky"
      titles in the EPG grid
- [x] **M4** manual channel ↔ guide matching (suggestions, preview, "No guide"); search
      in programmes (on now, upcoming, catch-up)
- [x] **M5** channel management: hide (per profile), rename, renumber (per playlist)
- [x] Multiview (iPad / Mac: 2 to 4 streams, only one with audio)
- [x] Offline downloads: TestFlight edition only (internal testers); the App Store edition is built without them
      (`#if TESTFLIGHT_DOWNLOADS || DEBUG`, guidelines 2.3.1 and 5.2.3)
- ~~Recording~~: not retained (decision of 13 Oct)
- Real-Debrid source: removed (a dormant feature for App Review, guideline 2.3.1);
  Stalker/MAG portal: out of v1 (decision of 13 Oct, Ondo remains an M3U / Xtream player)
- [x] Replace the home-made image loader with Nuke/NukeUI (disk cache, coalescing, downsampling)

## 4. Phase 7 — Movies and series experience
- [x] **M-A** texts: a player without content, movies and series put forward
- [x] **M-B** home: featured carousel, rows (resume, My List, recently added), dark theme
- [x] **M-C** full-screen movie / series pages (backdrop image, actions, cast, seasons, episodes)
- [x] **M-D** Movies / Series tabs in rows by category (chips, resume, My List, search)
- [x] Mac App Store screenshots (*App Store screenshots* workflow)

## 5. Risks

| Risk | Mitigation |
|---|---|
| AVPlayer does not play raw `.ts` | Xtream `m3u8` output by default, fallback URL, VLCKit engine (phase 3) |
| Huge playlists (50,000+ entries) | background parsing, pre-normalized search index, lazy lists, disk cache |
| XMLTV guides of hundreds of MB | streaming SAX, channel/window filtering, pruned cache |
| Non-compliant Xtream panels | tolerant JSON decoding, tests on real responses |
| App Store review (IPTV apps) | no content provided, clear disclaimer, legal demo playlist |
| Licenses | MIT/BSD/LGPL dependencies, Apache 2.0 (Android libraries), Google's Cast SDK under Google's terms (iPhone/iPad) |
| No Mac in the development environment | core tested on Linux (Docker Swift 6); app built by the macOS CI |

## 6. Build and run

```bash
brew install xcodegen
xcodegen generate          # creates Ondo.xcodeproj
open Ondo.xcodeproj        # "Ondo" scheme, iPhone or "My Mac" destination

# Core tests (macOS or Linux)
cd Packages/IPTVCore && swift test
```
