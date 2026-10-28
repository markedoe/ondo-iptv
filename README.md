# Ondo

**English** · [Français](README.fr.md)

[![CI](https://github.com/markedoe/ondo-iptv/actions/workflows/ci.yml/badge.svg)](https://github.com/markedoe/ondo-iptv/actions/workflows/ci.yml) [![Android](https://github.com/markedoe/ondo-iptv/actions/workflows/android.yml/badge.svg)](https://github.com/markedoe/ondo-iptv/actions/workflows/android.yml) [![Security](https://github.com/markedoe/ondo-iptv/actions/workflows/security.yml/badge.svg)](https://github.com/markedoe/ondo-iptv/actions/workflows/security.yml) [![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

IPTV player for **iPhone / iPad**, **macOS** and **Android** (phones, tablets and Android TV) — movies, series and live TV —, native on each
platform (Swift / SwiftUI, Kotlin / Jetpack Compose). It runs on iOS / iPadOS 17, macOS 14 and Android 8.0
(API 26) or later. Design and roadmap: [docs/PLAN.md](docs/PLAN.md), Android version:
[docs/ANDROID.md](docs/ANDROID.md).

> Ondo is **only a player**: it does not host, provide or sell any content, channel, playlist or IPTV
> subscription. It plays the sources that users add themselves and are allowed to watch.

> **Beta.** Ondo is still young: screens and features may change, and bugs are expected. The TestFlight edition
> (iPhone, iPad, Mac) and the Google Play test builds show a *Beta* badge. Feedback and bug reports are welcome
> in the [issues](https://github.com/markedoe/ondo-iptv/issues).

## Screenshots

The built-in demo playlist (fictional titles), taken by CI each time the interface changes.

<p align="center">
  <img src="https://raw.githubusercontent.com/markedoe/ondo-iptv/appstore-screenshots/en-US/01-home-iphone-max.png" width="200" alt="Home on iPhone">
  <img src="https://raw.githubusercontent.com/markedoe/ondo-iptv/appstore-screenshots/en-US/02-movie-iphone-max.png" width="200" alt="A movie page on iPhone">
  <img src="https://raw.githubusercontent.com/markedoe/ondo-iptv/appstore-screenshots/en-US/03-series-iphone-max.png" width="200" alt="A series page on iPhone">
</p>
<p align="center">
  <img src="https://raw.githubusercontent.com/markedoe/ondo-iptv/appstore-screenshots/en-US/01-home-ipad-13.png" width="410" alt="Home on iPad">
  <img src="https://raw.githubusercontent.com/markedoe/ondo-iptv/appstore-screenshots/mac/en-US/01-home-mac.png" width="410" alt="Home on the Mac">
</p>
<p align="center">
  <img src="https://raw.githubusercontent.com/markedoe/ondo-iptv/android-screenshots/play/en-US/phoneScreenshots/01-home.png" width="200" alt="Home on Android">
  <img src="https://raw.githubusercontent.com/markedoe/ondo-iptv/android-screenshots/play/en-US/phoneScreenshots/02-movie.png" width="200" alt="A movie page on Android">
  <img src="https://raw.githubusercontent.com/markedoe/ondo-iptv/android-screenshots/play/en-US/phoneScreenshots/05-live.png" width="200" alt="Live TV on Android">
</p>
<p align="center">
  <img src="https://raw.githubusercontent.com/markedoe/ondo-iptv/android-screenshots/play/en-US/tvScreenshots/01-home.png" width="410" alt="Home on Android TV">
  <img src="https://raw.githubusercontent.com/markedoe/ondo-iptv/android-screenshots/play/en-US/tvScreenshots/02-movie.png" width="410" alt="A movie page on Android TV">
</p>

## Features

### iPhone, iPad and Mac

- **Home**: carousel of featured movies and series, movies and series in progress (one row each), My List,
  new releases, trending; live TV last; when the playlist was last updated, above its name.
- **Movies & series**: poster grids sorted by playlist order, name, date added, release date or rating,
  watched movies hidden if you like (Search and My List keep them),
  detail pages (plot, cast, trailer), seasons / episodes,
  "Continue Watching", resume, automatic next episode, **TMDb trending**; **tap an actor or a director** to
  see their other movies and series in the playlist (series from the provider's list, movies from the pages
  already opened, or all of them with a TMDb token); a genre, the release year or an award (then its festival) opens the titles that share it; when the
  provider's background image is missing or broken, the page shows TMDb's (with a token) or the blurred poster.
- **Search** (the magnifying glass at the top of each iPhone tab): channels, programmes, movies and series,
  **actors and directors by name**, **filtered by release years** and **by festival or ceremony** (winners only if you like).
- **Awards**: Palme d'Or, Oscars, Golden Lion, Golden Bear, Césars, BAFTAs, Golden Globes… on movie pages
  (won, in competition or nominated) and an "Award Winners" row in Movies. The data comes from
  [Wikidata](https://www.wikidata.org) (CC0), is bundled with the app and matched on the device; a monthly
  workflow refreshes it and the apps fetch the newer file once a week, unless turned off in Settings
  ([tools/awards](tools/awards/README.md)).
- **Sources**: M3U / M3U8 playlists (URL or file), **Xtream Codes** accounts (API or "force M3U"), XSPF,
  JSON; several playlists, automatic refresh.
- **Live TV**: categories, numbers, logos, current programme and its progress, My List, recents, search;
  hidden, renamed or renumbered channels.
- **TV Guide**: XMLTV (gzip), the provider's guide and/or custom URLs, grid with sticky titles, programme
  details, time offset, fallback to the Xtream short EPG, **manual channel ↔ guide matching**, **programme
  search**, **reminders** (local notifications).
- **Catch-up**: Xtream `timeshift`, M3U modes `default`, `append`, `shift`, `flussonic`; **restart the
  current programme**, watch a past programme and go back to live from the player, **live pause** (HLS DVR
  window, otherwise resumed from the archive).
- **Player**: two engines, **AVPlayer** (PiP, AirPlay, HDR) and **VLC** (MKV, AVI, TS…), with automatic
  switching; **MKV films remuxed for AVPlayer** on iPhone and iPad (VLC plays them on the Mac), without transcoding (HDR10, HLG, Dolby Vision,
  Dolby Atmos, PiP), VLC playing those it cannot decode; zapping, audio / subtitle tracks, **external SRT subtitles**, **online subtitle search**
  (OpenSubtitles, SubDL and SubSource with your key, checked with the service when saved, Addic7ed without one), aspect ratio, speed, sleep timer,
  lock, gestures (iPhone), forced landscape, automatic reconnection, switch to another version of a dead
  channel, explained errors, lock screen / media keys, keyboard shortcuts, Playback menu, one window per
  stream and **full screen** (button, F key, double-click) on the Mac; **exact resume**, **last channel**,
  **zapping by number**, channels / guide panel in the player; **buffer length** for live TV and for movies &
  series, **stream statistics** that tell a network freeze from a player one and show the format (MKV, MP4…),
  and **Report a Problem…**: a playback report with no address, login or title, to share or send in a GitHub
  issue. Movie and series pages show the format their provider sends.
- **Casting**: **AirPlay** (iPhone, iPad, Mac: the video goes to the TV, not only the sound) and
  **Chromecast** (iPhone, iPad).
- **Multiview** (iPad, Mac): up to 4 channels, a single active sound.
- **Profiles** ("Who's watching?"), profile PIN, **kids profiles**, parental control by PIN.
- **My List** (a tab on iPhone): movies, series and channels kept in one place, reorderable; **collections**
  to group them your way.
- **Categories**: hide a whole language or country in one go (providers start category names with codes
  such as `|PT|` or `FR:`), a rule that also covers the categories added later; offered once when a playlist
  has many of them (keep your languages, choose, or not now); hide a **style** (manga & anime, children,
  documentaries…) or any word of category names (Bollywood…) the same way; or hide categories one by one.
  The filter button is on the Movies, Series and Live TV screens.
- **Offline downloads** (movies, episodes; TestFlight edition, not the App Store one), with their speed and time left, the reason in plain words when the
  provider refuses, limits connections or sends nothing, and their details to share.
- **Data**: JSON backup / restore, clearable cache. Posters, thumbnails and the movie and series pages stay on the
  device: they show at once at the next launch, and the pages still open when the provider cannot be reached.
- **Sync on this network** (Settings, off until turned on): iPhone, iPad and Mac connect to the Android devices
  with Ondo open on the same network and keep the same playlists, My List, collections, progress and TMDb token
  (main profile only; two Apple devices sync through an Android one). Local addresses only
  ([docs/SYNC.md](docs/SYNC.md)).
- **Help** in the app (Settings, and Help, Ondo Help on the Mac): one page per feature, in every language.
- **Languages**: English, French, Spanish, Portuguese, Chinese (Simplified), Arabic (right to left) and Hindi.

### Android (phones, tablets and TV)

- **Home**: carousel, movies and series in progress (one row each; long-press a card, or hold OK on a TV, to take
  it out), My List, new releases, channels; when the playlist was last updated, above its name.
- **Movies & series**: one row per category, filterable grids sorted like on Apple (release date
  included), watched movies hidden if you like, detail pages (Resume, From Start, next
  episode, seasons, the provider's trailer on YouTube, More Like This); a director's or an actor's name opens their other movies and series
  (series always, movies among the pages already opened), and a genre, the year or an award the titles that share it.
- **Live TV**: categories, My List, current and next programme.
- **Search** (the magnifying glass of Home, Movies, Series, Live TV and My List; on a TV, in the top bar after the
  tabs, a page of its own that Back closes): channels, movies, series and **actors and directors by name**, filtered by
  release years or by festival / ceremony (winners only, if you like); no programme search. It opens empty each
  time, and a tab chosen afterwards opens on its own page.
- **TV guide** (the calendar button of Live TV): a grid of the channels chosen there, and **reminders**, a
  notification a few minutes before a programme starts.
- **My List** tab: the movies, series and channels kept, and your **collections** (Collection on a movie or series
  page), as on Apple.
- **Categories**: hidden by language or country code (`|PT|`, `FR:`…), by style or word (manga, Bollywood…) or one
  by one, like on Apple.
- **Help** in Settings.
- **Awards** on movie pages and an "Award winners" row in Movies, from the same bundled data, updated once a
  week unless turned off in Settings.
- **Sources**: M3U / M3U8, XSPF, JSON playlists and **Xtream Codes** accounts (same core, ported to Kotlin
  and tested with the same cases).
- **Player** (Media3 / ExoPlayer): HLS, MPEG-TS, MP4, MKV, provider headers, fallback addresses, resume,
  picture-in-picture, media controls; a channel that falls behind goes back to the live edge on its own; the
  **audio and subtitle languages** you pick are used for the next streams (forced subtitles still show with Off);
  More → **Aspect ratio** (Fit, Fill (crop), Stretch, kept for the next videos), **Subtitle size** (Small to Extra
  large, kept too) and **Sleep timer** (15 to 120 minutes, then the player pauses); explained errors, **buffer length** for live TV and for movies & series, a
  buffer that fits in the app's memory (a 4K film plays on a TV box), **stream statistics** that tell a
  network freeze from a player one and show the format (MKV, MP4…), as on Apple, and **Report a Problem…**: a
  playback report with no address, login or title, to share or send in a GitHub issue. Movie and series pages
  show the format their provider sends.
- **Phone player**: its own controls, with gestures: a tap shows them, a double tap on a side skips 10 seconds
  (taps add up), a finger held plays at 2×, slides change the brightness and the volume, a pinch fills the screen,
  a lock; Subtitles, Audio, Speed and Aspect ratio in sheets; **Up next** at the end of an episode; on a live
  channel, a **Channels** list with what is on now.
- **Replay**, when the provider keeps an archive: **Play replay** on a past programme of the guide, **Restart** on
  the one on now (guide and player), Back to live; a live channel paused for more than 10 seconds resumes where it
  was paused. An Xtream archive is asked for in the panel's time zone.
- **Data**: posters, thumbnails and the movie and series pages stay on the device, like on Apple: they show at
  once at the next launch, and the pages still open when the provider cannot be reached.
- **Sync on this network** (Settings, off until turned on): while Ondo is open, the Android devices of a home
  network keep the same playlists, My List, collections, progress and TMDb token, and iPhone, iPad and Mac sync with
  them; directly between the devices, to local addresses only, nothing through the internet
  ([docs/SYNC.md](docs/SYNC.md)).
- **TMDb**, with the viewer's own token (Settings → TMDb, checked with TMDb when saved, carried by sync): a
  Trending row on Home and in the carousel, TMDb's background image when the provider's is missing or broken, photos
  of the cast on a title's page, the people TMDb knows in Search, and all the titles of the playlist on an actor's
  or a director's page.
- **Settings**: playlists, adult categories hidden by default, languages and categories, app language,
  automatic awards updates, player buffers, TMDb token; welcome screen with a demo.
- **Android TV / Google TV**: Ondo in the TV's launcher with its banner, every screen with the remote (a visible
  focus that comes back to the card you left, layouts that keep clear of the screen's edges); a player drawn for
  the remote: large buttons, a progress bar to move through the video, Subtitles and Audio panels that list the
  tracks by language, Speed and More; OK pauses, Left and Right go back and forward 10 seconds where the stream
  allows it (otherwise they show the controls), Up and Down, CH+ / CH−, the number keys and Last Channel change
  channel; a Channels panel with what is on now, Restart, Up next at the end of an episode; a film at its own frame
  rate when the TV is set to match the content (Android 12 and later); hold OK on a channel
  to add it to My List; Help, With the remote. The privacy policy and support pages
  open inside the app.
- Coming next: Chromecast, downloads, profiles and PIN, online subtitles, a second engine (libVLC).
  Details: [docs/ANDROID.md](docs/ANDROID.md).

## Languages

The apps (iPhone, iPad, Mac and Android) are available in seven languages, picked from the device settings, or
for Ondo alone from the system's per-app language setting. **Settings → App Language** opens it: Ondo's page in
the Settings app (iPhone, iPad), System Settings → General → Language & Region, where Ondo is under Applications
(Mac), or Ondo's language page (Android 13+). Below Android 13, which has no per-app setting, it opens the
device's languages:

| Language | Apple (`App/Resources/Localizable.xcstrings`) | Android (`android/app/src/main/res/`) |
|---|---|---|
| English (development language) | `en` | `values/` |
| French | `fr` | `values-fr/` |
| Spanish | `es` | `values-es/` |
| Portuguese (Brazilian wording) | `pt` | `values-pt/` |
| Chinese (Simplified) | `zh-Hans` | `values-zh-rCN/` |
| Arabic (right to left) | `ar` | `values-ar/` |
| Hindi | `hi` | `values-hi/` |

Every text of a new feature is translated into all seven languages in the same change, plural forms included.
CI enforces it: `check_translations.py` fails on a missing Apple translation or one whose placeholders (`%@`,
`%lld`) or plural forms differ, Android lint on a missing Android one, and `TranslationFormatsTest` on an Android
translation that cannot be formatted. The store listings, the website and this documentation are in English and
French.

## Structure

```
App/                    SwiftUI app (single iOS + macOS target)
AppTests/               App unit tests
UITests/, MacUITests/   UI tests and App Store screenshots (iPhone and iPad, Mac)
Packages/IPTVCore/      Business logic (Foundation and the system zlib, tested on Linux and macOS)
android/app/            Android app (Jetpack Compose, Media3)
android/core/           Business logic in pure Kotlin (port of IPTVCore, same test cases)
fastlane/               App Store and Google Play publishing, listings of both stores
funding/                Support pages on Stripe, per app (docs/FUNDING.md)
docs/                   Plan, publishing (App Store, Google Play), Android version, support pages
site/                   Website (GitHub Pages): home, support, privacy
tools/                  Awards data generator, playback lab
project.yml             XcodeGen project
```

## Building

Requirements: Xcode 26 (used by CI and releases) and [XcodeGen](https://github.com/yonaskolb/XcodeGen).

```bash
brew install xcodegen
xcodegen generate
open Ondo.xcodeproj
```

Choose the **Ondo** scheme, then an iPhone (simulator or device) or **My Mac**. For a device, select your
signing team in the *Signing & Capabilities* tab.

A Debug build (the default) includes the TestFlight edition's downloads (`#if TESTFLIGHT_DOWNLOADS || DEBUG`);
the App Store edition is a Release build without `TESTFLIGHT_DOWNLOADS` ([docs/RELEASE.md](docs/RELEASE.md)).

### Dependencies

Apple:

- [IPTVCore](Packages/IPTVCore) (this repository)
- [VLCKit](https://code.videolan.org/videolan/VLCKit) 3.6 (LGPL 2.1) via
  [vlckit-spm](https://github.com/tylerjonesio/vlckit-spm), for the formats AVFoundation does not support.
- [Google Cast SDK](https://developers.google.com/cast) 4.8 (iPhone/iPad) via
  [google-cast-sdk](https://github.com/SRGSSR/google-cast-sdk), to cast to Chromecast. Unlike the others, the
  SDK is proprietary: Google's, under Google's terms.
- [Nuke / NukeUI](https://github.com/kean/Nuke) 12 (MIT), loading and caching of logos and posters.

Android (`android/gradle/libs.versions.toml`), all under the Apache 2.0 licence:

- [AndroidX Media3](https://developer.android.com/media/media3) 1.11 (ExoPlayer, HLS, MediaSession), the player.
- [Jetpack Compose](https://developer.android.com/compose) (BOM 2026.09), Material 3, Navigation, Lifecycle.
- [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager) 2.12, programme
  reminders.
- [Coil](https://github.com/coil-kt/coil) 3.6, loading and caching of logos and posters.
- [OkHttp](https://github.com/square/okhttp) 5.5, network requests.
- [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) 1.11 and
  [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) 1.11.

Core tests:

```bash
cd Packages/IPTVCore && swift test
```

### What CI checks beyond unit tests

Some defects only show up in real conditions, where tests on simulated data cannot see them:

- **Adding a playlist end to end** (`AppTests/PlaylistOverHTTPTests.swift`, UI test
  `testAddingAPlaylistByURL`): a real local HTTP server, a redirect, the "player" identity sent to servers,
  HTTP errors, and an Xtream panel that decodes like PHP. A password with `+ / & =` must arrive intact.
- **Resume on a real stream** (`ResumePlaybackTests`): playback starts at the saved position, a pause made
  while the film opens stays paused, and the beginning of the film never overwrites the resume point.
- **Plain HTTP allowed everywhere** (`AppTransportSecurityTests`, `NetworkSecurityConfigTest` on Android).
- **Shipped configuration** (`ShippedConfigurationTests`): Chromecast discovery, lock screen audio, export
  compliance question, privacy manifest.
- **Scripts** (`.github/scripts/check_*.py`, runnable locally): texts translated into every language, a privacy
  manifest covering the APIs used by the shipped code, Mac sandbox entitlements, the same awards data in both
  apps, repository neutrality (no other IPTV app named, IPTV never called free, fictional channels in tests),
  and the App Store edition: no download code or dormant feature, no private API, never "beta", a listing and
  review notes App Review accepts (also checked on every change and before each release).
- **Release build, App Store edition** (iOS and macOS): the `#if DEBUG` code and the TestFlight edition's
  downloads (`#if TESTFLIGHT_DOWNLOADS || DEBUG`) are left out, as in the build that is submitted.
- **Playback lab** (`tools/playback-lab`, when the VLC engine or the playback code changes): libVLC, the library
  of the VLC engine, plays generated streams from a provider that misbehaves on purpose; freezes, film start
  and seek times, the resume position and the app's freeze verdict are checked against limits.
- **Release build on an Android TV emulator** (*TV release check*, run by hand): the R8 build driven with the
  remote's keys against a made-up Xtream panel, from adding a playlist to the player, the guide, sync and a movie
  at a 4K film's bitrate; a crash, an ANR or Android ending the app fails it. A TV box's memory limit is asked
  for, applied when the emulator allows it; the limit in force is printed.
- **Mutation testing** of the Kotlin core, once a week (Pitest): small bugs are planted in the code and those
  no test catches are reported; it does not fail the build.
- **Before each release**: the demo playlist used by Apple's and Google's review must answer.

## Android

Requirements: JDK 17+ and the Android SDK (Android Studio). Open the `android/` folder in Android Studio, or
from the command line:

```bash
cd android
./gradlew -p core test                 # Kotlin core
./gradlew :app:testDebugUnitTest       # unit and Compose tests (Robolectric)
./gradlew :app:recordRoborazziDebug    # + Google Play listing screenshots
./gradlew :app:assembleDebug           # debug APK
```

To test the app on a phone without Google Play, install the debug APK from the **android-builds** artifact
of the latest *Android* workflow run. Publishing to Google Play: [docs/RELEASE-ANDROID.md](docs/RELEASE-ANDROID.md).

## Secrets and privacy

The repository is public: publishing keys live only in GitHub secrets ([docs/RELEASE.md](docs/RELEASE.md),
[docs/RELEASE-ANDROID.md](docs/RELEASE-ANDROID.md)).
A guard blocks secrets and personal data before any commit: a Claude Code hook (`.claude/settings.json`), a
git hook (`git config core.hooksPath .githooks`) and CI (`.claude/hooks/leak_guard.py` + gitleaks on the
whole history).

## Support the project

Ondo has no ads and no tracking. If you find it useful, you can support its development
[once, with the amount you choose](https://buy.stripe.com/00w14n5x6gc12kO6qL5Rm06), or every month: [€3](https://buy.stripe.com/aFaeVd0cM8Jz6B4aH15Rm02), [€5](https://buy.stripe.com/6oU3cve3C9NDe3wg1l5Rm04) or [€10](https://buy.stripe.com/dRm4gzaRqf7XcZs2av5Rm00).
By card, Apple Pay or Google Pay, without creating an account. Inside the apps, money goes only through the App
Store and Google Play, as their rules require: the apps link to no other way to pay.

## License

MIT, see [LICENSE](LICENSE).
