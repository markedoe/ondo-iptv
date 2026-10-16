# Ondo

**English** · [Français](README.fr.md)

IPTV player for **iPhone / iPad**, **macOS** and **Android** (movies, series and live TV), native on each
platform (Swift / SwiftUI, Kotlin / Jetpack Compose). Design and roadmap: [docs/PLAN.md](docs/PLAN.md),
Android version: [docs/ANDROID.md](docs/ANDROID.md).

> Ondo is **only a player**: it does not host, provide or sell any content, channel, playlist or IPTV
> subscription. It plays the sources that users add themselves and are allowed to watch.

## Features

### iPhone, iPad and Mac

- **Home**: carousel of featured movies and series, "Continue Watching", My List, new releases, trending;
  live TV last; when the playlist was last updated, above its name.
- **Movies & series**: poster grids sorted by playlist order, name, date added, release date or rating,
  detail pages (plot, cast, trailer), seasons / episodes,
  "Continue Watching", resume, automatic next episode, **TMDb trending**; **tap an actor or a director** to
  see their other movies and series in the playlist (from the pages already opened, or all of them with a
  TMDb token); a genre, the release year or an award (then its festival) opens the titles that share it; when the
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
  switching; **MKV films remuxed for AVPlayer** on the device, without transcoding (HDR10, HLG, Dolby Vision,
  Dolby Atmos, PiP), VLC playing those it cannot decode; zapping, audio / subtitle tracks, **external SRT subtitles**, **online subtitle search**
  (OpenSubtitles, SubDL and SubSource with your key, checked with the service when saved, Addic7ed without one), aspect ratio, speed, sleep timer,
  lock, gestures (iPhone), forced landscape, automatic reconnection, switch to another version of a dead
  channel, explained errors, lock screen / media keys, keyboard shortcuts, Playback menu, one window per
  stream and **full screen** (button, F key, double-click) on the Mac; **exact resume**, **last channel**,
  **zapping by number**, channels / guide panel in the player; **buffer length** for live TV and for movies &
  series, **stream statistics** that tell a network freeze from a player one, and **Report a Problem…**: a
  playback report with no address, login or title, to share or send in a GitHub issue.
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
- **Data**: JSON backup / restore, clearable cache.
- **Help** in the app (Settings, and Help, Ondo Help on the Mac): one page per feature, in every language.
- **Languages**: English, French, Spanish, Portuguese, Chinese (Simplified), Arabic (right to left) and Hindi.

### Android (phones and tablets)

- **Home**: carousel, "Continue Watching", My List, recently added, channels; when the playlist was last updated,
  above its name.
- **Movies & series**: one row per category, filterable grids sorted like on Apple (release date
  included), detail pages (Resume, From Start, next
  episode, seasons, More Like This); a director's or an actor's name opens their other movies and series
  (among the pages already opened), and a genre, the year or an award the titles that share it.
- **Live TV**: categories, My List, current and next programme; **search** (the magnifying glass of each screen),
  filtered by release years or by festival / ceremony (winners only, if you like), and actors and directors by
  name, like on Apple.
- **TV guide** (the calendar button of Live TV): a grid of the channels chosen there, and **reminders**, a
  notification a few minutes before a programme starts.
- **My List** tab: the movies, series and channels kept, and your **collections** (Collection on a movie or series
  page), as on Apple.
- **Categories**: hidden by language or country code (`|PT|`, `FR:`…), by style or word (manga, Bollywood…) or one
  by one, like on Apple.
- **Help** in Settings.
- **Awards** on movie pages and an "Award winners" row in Movies, from the same bundled data.
- **Sources**: M3U / M3U8, XSPF, JSON playlists and **Xtream Codes** accounts (same core, ported to Kotlin
  and tested with the same cases).
- **Player** (Media3 / ExoPlayer): HLS, MPEG-TS, MP4, MKV, provider headers, fallback addresses, resume,
  picture-in-picture, media controls, back to live.
- **Settings**: playlists, appearance, adult categories hidden by default; welcome screen with a demo.
- Coming next: Android TV, catch-up, Chromecast, downloads, profiles and PIN, online subtitles. Details: [docs/ANDROID.md](docs/ANDROID.md).

## Languages

The apps (iPhone, iPad, Mac and Android) are available in seven languages, picked from the device settings, or
for Ondo alone from the system's per-app language setting, which **Settings → App Language** opens (iPhone, iPad,
Android 13+; on the Mac, System Settings → General → Language & Region → Applications):

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
CI enforces it: `check_translations.py` fails on a missing Apple translation and Android lint on a missing
Android one. The store listings, the website and this documentation are in English and French.

## Structure

```
App/                    SwiftUI app (single iOS + macOS target)
Packages/IPTVCore/      Business logic (pure Foundation, tested on Linux and macOS)
android/app/            Android app (Jetpack Compose, Media3)
android/core/           Business logic in pure Kotlin (port of IPTVCore, same test cases)
fastlane/               App Store and Google Play publishing, listings of both stores
docs/                   Plan, publishing (App Store, Google Play), Android version
project.yml             XcodeGen project
```

## Building

Requirements: Xcode 16+ and [XcodeGen](https://github.com/yonaskolb/XcodeGen).

```bash
brew install xcodegen
xcodegen generate
open Ondo.xcodeproj
```

Choose the **Ondo** scheme, then an iPhone (simulator or device) or **My Mac**. For a device, select your
signing team in the *Signing & Capabilities* tab.

### Dependencies

- [IPTVCore](Packages/IPTVCore) (this repository)
- [VLCKit](https://code.videolan.org/videolan/VLCKit) 3.6 (LGPL 2.1) via
  [vlckit-spm](https://github.com/tylerjonesio/vlckit-spm), for the formats AVFoundation does not support.
- [Google Cast SDK](https://developers.google.com/cast) 4.8 (iPhone/iPad) via
  [google-cast-sdk](https://github.com/SRGSSR/google-cast-sdk), to cast to Chromecast.
- [Nuke / NukeUI](https://github.com/kean/Nuke) 12 (MIT), loading and caching of logos and posters.

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
  manifest covering the APIs used by the shipped code, Mac sandbox entitlements, repository neutrality (no
  other IPTV app named, IPTV never called free, fictional channels in tests).
- **Release build** (`#if DEBUG` code is left out of the App Store version).
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

The repository is public: publishing keys live only in GitHub secrets ([docs/RELEASE.md](docs/RELEASE.md)).
A guard blocks secrets and personal data before any commit: a Claude Code hook (`.claude/settings.json`), a
git hook (`git config core.hooksPath .githooks`) and CI (`.claude/hooks/leak_guard.py` + gitleaks on the
whole history).

## Support the project

Ondo has no ads and no tracking. If you find it useful, you can support its development on
[GitHub Sponsors](https://github.com/sponsors/markedoe). Inside the apps, money goes only through the App Store and Google Play, as
their rules require: the apps link to no other way to pay.

## License

MIT, see [LICENSE](LICENSE).
