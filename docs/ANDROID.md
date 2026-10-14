# Ondo for Android — plan

**English** · [Français](ANDROID.fr.md)

## Status (v1, October 2026)

v1 lives in `android/` and is released following [RELEASE-ANDROID.md](RELEASE-ANDROID.md):

- **Core** `android/core`: Kotlin port of IPTVCore (M3U, XSPF, JSON, Xtream, XMLTV, catch-up, search,
  playback items), tested with the same cases as the Swift tests.
- **App** `android/app` (Compose, Material 3, type-safe Navigation, tab bar or rail depending on the screen):
  home (carousel, Continue Watching, My List, Recently Added, channels), Movies and Series tabs
  (one row per category, filterable grids), movie and series pages (Resume, From Start, next
  episode, seasons, More Like This), live TV (categories, favorites, current and next programme),
  search, settings (playlists, appearance, adult categories hidden by default), welcome screen with a
  demo and a "player without content" disclaimer.
- **Player** Media3 / ExoPlayer: HLS, MPEG-TS, MP4, MKV, provider headers, fallback addresses,
  resume, picture-in-picture, MediaSession, Back to Live.
- **Tests**: unit (library, showcase, screens, repository, ViewModel), Compose (Robolectric), Roborazzi
  screenshots that double as Google Play screenshots (phone, 7" and 10" tablets, English and French).

Planned next (not included in v1): Android TV, grid guide, catch-up from the guide, Chromecast,
downloads, profiles and PIN, sync, second libVLC engine.

## Initial plan

To be started once the iPhone / iPad / Mac version is validated and published. Goal: the same app, native,
with no compromise on quality, reusing everything that can be reused (specifications, test suites,
texts, icon, store listing) rather than Swift code.

## 1. Technical choices

| Topic | Choice | Why |
|---|---|---|
| Language / UI | Kotlin, Jetpack Compose, Material 3 (Ondo theme) | current Android standard |
| Targets | Android 8+ phones and tablets (API 26), **Android TV / Google TV** (Compose for TV) | IPTV is mostly watched on TV |
| Playback | **AndroidX Media3 / ExoPlayer** (HLS, DASH, MPEG-TS, subtitles), FFmpeg extension for audio codecs | Android's standard player, PiP, MediaSession |
| Second engine | libVLC Android (LGPL) as a fallback, as on iOS | MKV / AVI / exotic streams |
| Casting | Google Cast (Media3 `CastPlayer`) | native Chromecast |
| Networking | OkHttp + Kotlin coroutines / Flow | |
| Storage | Room (catalogue, guide), DataStore (settings), Android Keystore (passwords) | |
| Images | Coil 3 | equivalent of Nuke |
| Sync | Google Drive *appDataFolder* (optional) instead of iCloud, same JSON format | |
| Injection | Hilt | |
| Tests | JUnit 5 + Turbine (core), Compose UI tests, Roborazzi (screenshots) | |
| CI/CD | GitHub Actions (lint, tests, build), Gradle Play Publisher → Google Play (internal track then production) | |

## 2. Core: `core` (pure Kotlin, no Android)

Faithful port of `IPTVCore`, driven by the **same test files** (`Packages/IPTVCore/Tests/IPTVCoreTests/Fixtures`
and the Swift test cases) to guarantee identical behavior:

- M3U (byte-by-byte parser, `tvg-*` and `catchup*` attributes, `#EXTVLCOPT` headers), XSPF, JSON
- Xtream Codes (tolerant decoding), streaming XMLTV (gzip, window / channel filters)
- Catch-up URLs (default, append, shift, flussonic, Xtream), numbering, EPG matching
  (automatic, suggestions, manual choice), search
- Library: favorites, history, lists, reminders, hidden channels — with the **same deterministic
  merge** (timestamped markers) so that sync stays compatible

## 3. Screens (parity with iOS)

Home, Live TV, Guide (grid + channel guide), Movies, Series, Search (including programmes), Favorites
and lists, Downloads, Settings, Profiles / parental control, Player (channels / guide panel,
restart / rewatch / live, Last Channel, zapping by number including from the remote, tracks,
subtitles, PiP, sleep timer), Multiview (tablet / TV).

Android TV specifics: D-pad navigation, full-screen guide, CH+/CH− keys, digits, "Info",
launch from the home screen (recommended channels).

## 4. Milestones

1. `core` + tests ported from the Swift suite (Linux CI)
2. Phone app: sources, Live TV, Media3 player, guide
3. Movies / series, search, library, profiles, reminders (WorkManager + notifications)
4. Android TV (Compose for TV), multiview, Cast
5. Sync, backup / restore compatible with iOS
6. Google Play listing (texts and screenshots reused), privacy policy (same page), release

## 5. Google Play considerations

- "Unauthorized content / intellectual property" policy: as on the App Store, no channels
  provided, clear disclaimer, legal demo playlist.
- Declarations: no data collection (*Data safety* form), minimal permissions
  (network, notifications, foreground for playback).
