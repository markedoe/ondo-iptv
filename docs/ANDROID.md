# Ondo for Android — plan

**English** · [Français](ANDROID.fr.md)

## Status (v1, October 2026)

v1 lives in `android/` and is released following [RELEASE-ANDROID.md](RELEASE-ANDROID.md):

- **Core** `android/core`: Kotlin port of IPTVCore (M3U, XSPF, JSON, Xtream, XMLTV, catch-up, search,
  playback items), tested with the same cases as the Swift tests.
- **App** `android/app` (Compose, Material 3, type-safe Navigation, tab bar or rail depending on the screen):
  home (carousel, Movies in Progress, Series in Progress, My List, New Movies, New Series, channels),
  Movies and Series tabs (Continue Watching, My List, Award winners in Movies, one row per category, filterable
  grids), a long press that takes a card in progress out of Home or Continue Watching (a menu on a phone, OK held
  and a question on a TV: `ui/components/Shelves.kt`), movie and series pages (Resume, From Start, next episode,
  seasons, More Like This, awards, tappable cast and director), live TV (categories, My List, current and next programme), TV guide grid with programme
  reminders (WorkManager notifications), My List tab with collections, search (channels, movies, series, actors
  and directors; filters by release years and by festival or ceremony), categories hidden by language or
  country code, by style or word, or one by one, help, settings (playlists, adult categories hidden
  by default, Update awards automatically, Languages and categories, App language, player buffers, TMDb token),
  welcome screen with a demo and a "player without content" disclaimer.
- **TMDb** with the viewer's own token (`core/library/Tmdb.kt`, `OndoRepository.checkAndSaveTmdbToken`): checked
  with TMDb's `/authentication` before it is saved (accepted, refused, unreachable), kept in the app's private
  storage and carried by sync (`key:tmdb`, docs/SYNC.md); a Trending row and carousel titles, TMDb's backdrop when
  the provider's is missing or does not load, its trailer, rating with votes and the photos of its cast and directors
  (`TmdbClient.titleInfo`, one call, by the provider's TMDb id or the title), TMDb's people in Search, and a person's
  titles from TMDb's credits. What TMDb answers is kept on the device (`SqliteMetadataStore`, `metadata.sqlite`, like
  the store on Apple devices; keys `movie:603` or `movie:sandstorm:2021`): pages show it at once, TMDb is asked
  again only after 30 days (7 for a title it does not know), Home's carousel titles are asked ahead two at a time,
  the credits kept find people in Search and their titles without network, and a dead image address is remembered
  for 7 days.
  A 401 later (a token revoked, or one that came by sync) shows as refused in Settings, and nothing more is asked.
- **Player** Media3 / ExoPlayer: HLS, MPEG-TS, MP4, MKV, provider headers, fallback addresses,
  resume, picture-in-picture, MediaSession, audio and subtitle tracks, the viewer's audio and subtitle languages
  learned from their choices and used first for the next streams, forced subtitles still shown with subtitles off
  (`player/TrackPreferences.kt`), separate buffers for live TV and for movies & series, never more than half of
  the memory the app has left when the player starts, so that a 4K film plays on a TV box that gives the app
  128 MB (`player/PlayerLoadControl.kt`, `core/playback/PlaybackBuffer.kt`), back to the live edge on its own when
  a channel falls behind, More → Aspect ratio (Fit, Fill (crop), Stretch, kept for the next videos), More → Subtitle
  size (75 % to 160 % of the size Android's caption settings give, kept too: `player/SubtitleSizing.kt`), More → Sleep
  timer (Off, 15, 30, 45, 60, 90, 120 min; the player pauses when it is up: `player/SleepTimer.kt`), More → Stream
  Statistics (format, video and received bitrates, smoothness, freezes and lost pictures, and whether they come from the
  network or the player: `core/playback/PlaybackHealth.kt`, the Apple rules), More → Report a Problem… (a
  playback report with no address, login or title). Movie and series pages show the format (MKV, HLS…).
  On a phone, its own Compose controls over Media3's video view (`player/PhonePlayerControls.kt`, Media3's own
  controller off): a tap shows them, a double tap on a side third skips 10 s (taps add up), a finger held 500 ms
  plays at 2×, slides change the brightness (the window's) and the volume (the music stream's), a pinch fills or
  fits, a lock; Subtitles, Audio, Speed, Aspect ratio and More in bottom sheets; Picture-in-Picture. Up next
  (`player/UpNext.kt`): the last 20 seconds of an episode offer the next one, played at the end unless declined. A
  live channel's Channels list (`player/ChannelList.kt`, a sheet on a phone, a side panel on a TV).
- **Replay** from the provider's archive (`OndoRepository.replayRequest`, `core/catchup/`): Play replay on a past
  programme of the guide, Restart on the one on now (guide, phone and TV controls), Back to live; a replay keeps its
  channel (channel keys, Channels list), goes in no history, and the Xtream archive is asked for in the panel's time
  zone (`content.account`). A live channel without a window to seek in, paused by the viewer for 10 s or more,
  resumes from the archive where it was paused (`player/LivePause.kt`, as on Apple).
- **Frame rate** on a TV with Android 12 or later: a film, an episode or a replay asks the screen for its own frame
  rate with `Surface.setFrameRate(…, CHANGE_FRAME_RATE_ALWAYS)`, as Google recommends for movies; Android switches
  only if the viewer set the TV to match the content's frame rate, and the player waits for the screen meanwhile
  (`player/FrameRateMatching.kt`). Live channels keep the screen as it is (each channel change would blank it).
- **Storage**: JSON documents in the app's private storage (`data/AppStore.kt`), left out of backups
  (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`): settings, playlists with their logins, My List,
  collections, progress and reminders, each playlist's catalogue and guide (the guide used for 6 hours before it
  is downloaded again, as on Apple devices; Refresh downloads it at once), and the movie and series pages opened (up to
  1,000 movies and 300 series per playlist, fetched again after 7 days for a movie and 12 hours for a series,
  still shown when the provider cannot be reached; `data/DetailsArchive.kt`). Posters and thumbnails are kept
  on disk by Coil 3 until the app is uninstalled (in its own storage, not the cache folder Android empties; up to a
  tenth of the free space, 256 MB to 2 GB; `OndoApplication.kt`).
- **Sync on the local network** (Settings → Sync on this network, off until turned on; `data/LocalSync.kt`,
  `data/SyncData.kt`, `core/sync/`, specified in [SYNC.md](SYNC.md)): while Ondo is in front, the device announces
  itself with Bonjour (`NsdManager`, `_ondo._tcp`), answers sessions on a TCP port, finds the other Android devices
  and syncs with them; iPhone, iPad and Mac connect to it. Playlists, My List, collections, progress and the TMDb
  token; only local addresses are reached or answered.
- **Languages**: English, French, Spanish, Portuguese, Chinese (Simplified), Arabic (right to left) and Hindi
  (`res/values-*`), also offered in Android 13's per-app language setting (`res/xml/locales_config.xml`).
- **Android TV / Google TV** (`ui/tv/`): a Leanback launcher entry and banner (320 × 180 at xhdpi,
  drawn by `fastlane/creative/render.mjs --tv`), touch screen not required. Compose for TV (`androidx.tv:tv-material`)
  in black and white with the Inter typeface (`TvDesign.kt`, `ui/theme/Theme.kt`): a top bar with the tabs, Search and Settings, which stays on screen as the page
  scrolls under it (`TvShell.kt`; Back goes to the page's top and the bar, then to Home, then out), a Home led by a framed picture (`TvHomeScreen.kt`) and movie and series
  pages (`TvDetailScreens.kt`) with backdrops behind the text and round buttons beside Play, Settings in two panes with each playlist's buttons in
  reach of the remote (`TvSettingsScreen.kt`; Delete asks first, starting on Cancel), cards that rise with a soft shadow and a sheen on focus and
  rows peeking past the right edge; the other pages are the Compose screens within the TV's safe area
  (48 × 27 dp). A focus that comes back to the card left (`FocusMemory`) and enters each page by itself
  (`FocusEntry`), rows scrolled to a third of the way. Player: its own controls for the remote over Media3's video
  view (`player/TvPlayerControls.kt`, `player/PlayerControls.kt`): large round buttons, a progress bar whose target moves
  in growing steps and is reached once the remote stops (one seek per move), Subtitles, Audio, Speed and More panels
  with the tracks named by language (`player/TrackChoices.kt`), hidden 5 s after the last key while playing; OK
  pauses, Left / Right go back and forward 10 s where the stream allows it (else they show the controls), Up / Down,
  CH+ / CH−, the number keys and Last Channel change channel (`player/RemoteKeys.kt`, `core/library/ChannelZapping.kt`), a channel banner, Back closes a panel, then the controls. Google's
  TV app quality: no Picture-in-Picture on a TV, the screen kept on while playing only, no browser (the privacy
  policy and support pages open in an in-app page limited to them), no file picker or share button where the TV
  has none, a smaller image memory cache on a low-memory device.
- **Tests**: unit (library, showcase, screens, repository, ViewModel), Compose (Robolectric), the remote on a TV
  (`TelevisionTest`, `PlayerRemoteTest`, `TvPlayerRemoteTest`, `TvPlayerControlsTest`), the phone player's gestures
  (`PhonePlayerOverlayTest`), Roborazzi screenshots that double as Google Play screenshots (phone,
  7" and 10" tablets, TV, English and French).

Planned next (not included in v1): Chromecast, downloads, profiles and PIN, online
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
