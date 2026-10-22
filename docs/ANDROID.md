# Ondo for Android — plan

**English** · [Français](ANDROID.fr.md)

## Status (v1, October 2026)

v1 lives in `android/` and is released following [RELEASE-ANDROID.md](RELEASE-ANDROID.md):

- **Core** `android/core`: Kotlin port of IPTVCore (M3U, XSPF, JSON, Xtream, XMLTV, catch-up, search,
  playback items), tested with the same cases as the Swift tests.
- **App** `android/app` (Compose, Material 3, type-safe Navigation, tab bar or rail depending on the screen):
  home (carousel, Movies in Progress, Series in Progress, My List, New Movies, New Series, channels),
  Movies and Series tabs (Continue Watching, My List, Award winners in Movies, one row per category, filterable
  grids), movie and series pages (Resume, From Start, next episode, seasons, More Like This, awards, tappable
  cast and director), live TV (categories, My List, current and next programme), TV guide grid with programme
  reminders (WorkManager notifications), My List tab with collections, search (channels, movies, series, actors
  and directors; filters by release years and by festival or ceremony), categories hidden by language or
  country code, by style or word, or one by one, help, settings (playlists, appearance, adult categories hidden
  by default, Update awards automatically, Languages and categories, App language, player buffers), welcome
  screen with a demo and a "player without content" disclaimer.
- **Player** Media3 / ExoPlayer: HLS, MPEG-TS, MP4, MKV, provider headers, fallback addresses,
  resume, picture-in-picture, MediaSession, audio and subtitle tracks, separate buffers for live TV and for
  movies & series, back to the live edge on its own when a channel falls behind, More → Stream Statistics
  (format, video and received bitrates, smoothness, freezes and lost pictures, and whether they come from the
  network or the player: `core/playback/PlaybackHealth.kt`, the Apple rules), More → Report a Problem… (a
  playback report with no address, login or title). Movie and series pages show the format (MKV, HLS…).
- **Storage**: JSON documents in the app's private storage (`data/AppStore.kt`), left out of backups
  (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`): settings, playlists with their logins, My List,
  collections, progress and reminders, each playlist's catalogue, and the movie and series pages opened (up to
  1,000 movies and 300 series per playlist, fetched again after 7 days for a movie and 12 hours for a series,
  still shown when the provider cannot be reached; `data/DetailsArchive.kt`). Posters and thumbnails are kept
  on disk by Coil 3 (up to 512 MB, in the app's cache; `OndoApplication.kt`).
- **Languages**: English, French, Spanish, Portuguese, Chinese (Simplified), Arabic (right to left) and Hindi
  (`res/values-*`), also offered in Android 13's per-app language setting (`res/xml/locales_config.xml`).
- **Android TV / Google TV** (`ui/tv/Television.kt`): a Leanback launcher entry and banner (320 × 180 at xhdpi,
  drawn by `fastlane/creative/render.mjs --tv`), touch screen not required; the same Compose screens, with a
  navigation rail, the TV's safe area (48 × 27 dp), a frame around the focused item, a focus that comes back to
  the card left (`FocusMemory`) and enters each page by itself (`FocusEntry`), rows scrolled to a third of the
  way, a side-by-side carousel and detail header on short landscape screens (also a phone on its side). Player:
  OK pauses, Left / Right seek, Up / Down, CH+ / CH−, the number keys and Last Channel change channel
  (`player/RemoteKeys.kt`, `core/library/ChannelZapping.kt`), a channel banner, Back hides the controls. Google's
  TV app quality: no Picture-in-Picture on a TV, the screen kept on while playing only, no browser (the privacy
  policy and support pages open in an in-app page limited to them), no file picker or share button where the TV
  has none, a smaller image memory cache on a low-memory device.
- **Tests**: unit (library, showcase, screens, repository, ViewModel), Compose (Robolectric), the remote on a TV
  (`TelevisionTest`, `PlayerRemoteTest`), Roborazzi screenshots that double as Google Play screenshots (phone,
  7" and 10" tablets, TV, English and French).

Planned next (not included in v1): catch-up from the guide, Chromecast, downloads, profiles and PIN, online
subtitles, second libVLC engine; on a TV, launcher channels and voice search.

## Initial plan

Written before v1, which differs: JSON documents in private storage instead of Room, DataStore and the Android
Keystore, no Hilt, core tests on JUnit 4, fastlane instead of Gradle Play Publisher, and no DASH, FFmpeg
extension, Cast or playback foreground service yet.

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
