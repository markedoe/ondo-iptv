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
  live TV last.
- **Movies & series**: poster grids, sorting, detail pages (plot, cast, trailer), seasons / episodes,
  "Continue Watching", resume, automatic next episode, **TMDb trending**.
- **Sources**: M3U / M3U8 playlists (URL or file), **Xtream Codes** accounts (API or "force M3U"), XSPF,
  JSON; several playlists, automatic refresh. (A Real-Debrid source exists in the code but is turned off in
  the App Store version: `FeatureFlags.realDebrid`.)
- **Live TV**: categories, numbers, logos, current programme and its progress, favorites, recents, search;
  hidden, renamed or renumbered channels.
- **TV Guide**: XMLTV (gzip), the provider's guide and/or custom URLs, grid with sticky titles, programme
  details, time offset, fallback to the Xtream short EPG, **manual channel ↔ guide matching**, **programme
  search**, **reminders** (local notifications).
- **Catch-up**: Xtream `timeshift`, M3U modes `default`, `append`, `shift`, `flussonic`; **restart the
  current programme**, watch a past programme and go back to live from the player, **live pause** (HLS DVR
  window, otherwise resumed from the archive).
- **Player**: two engines, **AVPlayer** (PiP, AirPlay, HDR) and **VLC** (MKV, AVI, TS…), with automatic
  switching; zapping, audio / subtitle tracks, **external SRT subtitles**, **online subtitle search**
  (OpenSubtitles, SubDL and SubSource with your key, Addic7ed without one), aspect ratio, speed, sleep timer,
  lock, gestures (iPhone), forced landscape, automatic reconnection, switch to another version of a dead
  channel, explained errors, lock screen / media keys, keyboard shortcuts, Playback menu, one window per
  stream and **full screen** (button, F key, double-click) on the Mac; **exact resume**, **last channel**,
  **zapping by number**, channels / guide panel in the player.
- **Casting**: **AirPlay** (iPhone, iPad, Mac: the video goes to the TV, not only the sound) and
  **Chromecast** (iPhone, iPad).
- **Multiview** (iPad, Mac): up to 4 channels, a single active sound.
- **Profiles** ("Who's watching?"), profile PIN, **kids profiles**, parental control by PIN.
- **Custom lists**, reorderable favorites, hidden categories.
- **Offline downloads** (movies, episodes), with the reason in plain words when the provider refuses,
  limits connections or sends nothing.
- **Data**: JSON backup / restore, clearable cache. Interface in English and French.

### Android (phones and tablets)

- **Home**: carousel, "Continue Watching", My List, recently added, channels.
- **Movies & series**: one row per category, filterable grids, detail pages (Resume, From Start, next
  episode, seasons, More Like This).
- **Live TV**: categories, favorites, current and next programme; **search**.
- **Sources**: M3U / M3U8, XSPF, JSON playlists and **Xtream Codes** accounts (same core, ported to Kotlin
  and tested with the same cases).
- **Player** (Media3 / ExoPlayer): HLS, MPEG-TS, MP4, MKV, provider headers, fallback addresses, resume,
  picture-in-picture, media controls, back to live.
- **Settings**: playlists, appearance, adult categories hidden by default; welcome screen with a demo.
- Coming next: Android TV, grid guide and catch-up, Chromecast, downloads, profiles and PIN, online
  subtitles. Details: [docs/ANDROID.md](docs/ANDROID.md).

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
- **Scripts** (`.github/scripts/check_*.py`, runnable locally): texts translated into French, a privacy
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

## License

MIT, see [LICENSE](LICENSE).
