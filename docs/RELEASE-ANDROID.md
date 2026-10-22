# Publishing Ondo on Google Play (Android)

**English** · [Français](RELEASE-ANDROID.fr.md)

Building, testing, store listing screenshots, signing and uploading to Google Play are automated
(Gradle + fastlane + GitHub Actions). What remains manual depends on your Google Play account and only has
to be done once. Allow an hour, plus Google's delays (account verification, 14-day closed test for a recent
personal account).

Procedure written on October 13, 2026. Play Console labels change often: when in doubt, follow the Play
Console help, which is authoritative.

## A. Google Play Console account

1. Create the account at <https://play.google.com/console/signup> (one-time registration fee of $25).
   - **Personal account**: identity verification (ID document, phone). For a personal account created
     after November 13, 2023, Google requires a **closed test with at least 12 opted-in testers for
     14 consecutive days** before production can be opened (see E2).
   - **Organization account**: D-U-N-S number required, no mandatory closed test.
2. Turn on 2-Step Verification for the Google account you use.

## B. Create the app in the Play Console

*Home → Create app*:

| Field | Value |
|---|---|
| App name | `Ondo` |
| Default language | English (United States) – en-US |
| App or game | App |
| Free or paid | Free (cannot be changed later) |
| Declarations | check the Developer Program Policies and the US export laws |

The package name is set on the first upload: `com.markedoe.ondo` (debug builds use `com.markedoe.ondo.debug`
and never go to the Play Store).

## C. Upload key and GitHub secrets

Google Play signs the distributed app (**Play App Signing**, mandatory for AABs). You only keep the
**upload key**, which signs the bundle you upload.

### C1. Create the upload key (once, on your computer)

```sh
keytool -genkeypair -v -keystore ondo-upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

`keytool` ships with Java (Android Studio includes it). Choose a strong password, and keep the `.jks` file
and its password **outside the repository** (password manager). If the key is lost, it can be reset from
the Play Console (*App integrity*), without republishing the app.

### C2. Service account for the Google Play API

1. *Play Console → Setup → API access* (or *Users and permissions*, depending on the console version):
   follow the link to Google Cloud, create a project if needed and enable the
   **Google Play Android Developer API**.
2. In Google Cloud: *IAM & Admin → Service accounts → Create service account* (`ondo-release`), with no
   Cloud role. In the *Keys* tab, *Add key → JSON*: a JSON file is downloaded.
3. In the Play Console: *Users and permissions → Invite new users*: the service account's address, with
   permissions **for the Ondo app**: *Release apps to testing tracks*, *Manage store presence*, *Release to
   production, exclude devices, and use Play App Signing* (if you want to release to production from
   GitHub).

### C3. Repository secrets

*GitHub → Settings → Secrets and variables → Actions → New repository secret*:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | the `.jks` file in base64: `base64 -i ondo-upload.jks \| pbcopy` (macOS) or `base64 -w0 ondo-upload.jks` (Linux) |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | `upload` (the alias chosen in C1) |
| `ANDROID_KEY_PASSWORD` | key password (often the same) |
| `PLAY_SERVICE_ACCOUNT_JSON` | the whole content of the service account's JSON file |

Never put the `.jks` or the JSON in the repository: `*.jks`, `*.keystore` and `secrets/` are ignored by git
and rejected by the leak guard (`.claude/hooks/leak_guard.py`).

## D. Store listing and declarations (Play Console → *Monitor and improve* / *Policy and programs*)

The store listing (title, descriptions, icon, feature graphic, phone, tablet and TV screenshots, TV banner, in
English and French; the app itself is in seven languages, see the README) is uploaded by fastlane from
`fastlane/metadata-android/`; the screenshots are produced by the
Roborazzi tests on every release. The release notes, `fastlane/metadata-android/en-US/changelogs/default.txt`
and `fr-FR/changelogs/default.txt` (500 characters at most, checked by the *Android* workflow), are sent with
every release: update both before tagging. The rest is declared once in the console, in the *Set up your app*
section of the dashboard:

### D1. Privacy policy

URL: `https://markedoe.github.io/ondo-iptv/privacy.html` (the site is published by the *Website* workflow).

### D2. Sign-in details (App access)

*Is any part of your app restricted?* **No**: Ondo has no account, no payment and locks nothing. The Xtream
fields (server, username, password) take the details of the user's own provider, like a playlist address: they
are not a sign-in to Ondo. Do not choose the restricted option: Play then asks for working login credentials
("Missing sign in details"). Reviewers find **Try the demo** on the welcome screen shown at first launch: a
playlist of public channels (iptv-org project).

If Play still reports "Missing sign in details" (its checks see the Xtream fields), answer **Yes** and add one set
of instructions: name `Demo – no account`, username `No account needed`, password `No password needed`, and in
*Any other instructions*: "Ondo has no accounts and no payments. On the welcome screen, tap "Try the demo": a
playlist of public TV channels (iptv-org project) loads, and every screen of the app can be reviewed with it. The
Xtream fields (server, username, password) are for the user's own provider: Ondo provides no server and no
content." Never give the credentials of a real IPTV subscription.

### D3. Ads

*No, my app does not contain ads.*

### D4. Content rating (IARC questionnaire)

Category: **All Other App Types** (not a game). Answer honestly: no violence, sexuality, crude language,
substances, gambling or location sharing in the app itself. For the question about user-generated or
user-provided content / access to unfiltered content, state that the app plays streams chosen by the user.
Categories whose name looks adult-only are hidden by default (*Show adult categories* setting).

### D5. Target audience

**18 and over**: the content depends on the user's sources. This rules out the Families program
requirements.

### D6. Data safety

Ondo has no server, no account, no analytics, no ads and no crash reporting: all data stays on the device.
Network requests go to the servers the user has entered, to the public demo playlist (iptv-org project) when
*Try the demo* is tapped, and once a week, unless turned off in Settings (*Update awards automatically*), to
GitHub for the public awards file; nothing about the user is sent (see the privacy policy).

- *Does your app collect or share any of the required user data types?* **No.**
- The form then only asks you to confirm. (No Google Cast on Android, unlike the iPhone: nothing to
  declare on that front.)

### D7. Other declarations

News app: **no**. Government, financial and health apps: **no**.
Advertising ID: **no** (the app does not use it). Financial features: **none**.

### D8. Main store listing

Category: **Video Players & Editors** (or *Entertainment*). Contact email address: an address dedicated to
support (public on the store listing). Website: `https://markedoe.github.io/ondo-iptv/`.

## E. First upload and testing

### E1. First bundle

1. *GitHub → Actions → Release Android → Run workflow* (track `internal`, status `draft`): the workflow sends
   the bundle, the listing texts and the screenshots, and creates a draft release on the internal track.
   If Google refuses it because the app has no release yet, download the **ondo-release-bundle** artifact
   (file `app-release.aab`) from the run and upload it in *Internal testing → Create new release*.
2. *Play Console → Test and release → Testing → Internal testing*: in *Testers*, create an email list with
   your Google account and save. Then *Releases* → the draft → *Edit* → *Next* → *Save and publish*. Open
   the *Join on Android* link on the phone and install Ondo from Google Play. *Test and release → App
   integrity* shows that Google Play signs the app (Play App Signing); the upload key stays yours.
3. Subsequent releases go out from GitHub: `git tag android-v1.1.1 && git push origin android-v1.1.1`
   (internal track, release rolled out), or *Run workflow* with the desired track. The `versionCode`
   increases on every run (1000 + run number). The `versionName` comes only from an `android-vX.Y.Z` tag: a
   run started from a branch keeps the default in `android/app/build.gradle.kts` (currently 1.1.0), so tag a
   version above the current one.

### E2. Closed test (recent personal account)

*Test and release → Closed testing → Create track*: add at least **12 testers** (email list or Google
Group), send them the opt-in link, and keep them opted in for **14 consecutive days** (if the number drops
below 12, the counter restarts from zero). Ask them to actually use the app (demo mode, their own
playlists). Then: *Dashboard → Apply for production* and answer the questionnaire about the test.

The workflow also publishes to this track: *Run workflow* with the `alpha` track (`alpha` = closed testing,
`beta` = open testing). The release is created as a draft: review it and roll it out in the Play Console. Only
`internal` can be rolled out directly (status `completed`, the default).

### E3. Production

*Production → Create new release* (or the workflow with the `production` track: the release is created as
a draft, to check and send for review in the console). Start that run from the version's `android-vX.Y.Z` tag,
chosen in *Use workflow from → Tags*: the workflow refuses `production` from a branch, whose bundle would keep
the default `versionName`. Builds for the test tracks (internal, closed, open)
show a *Beta* badge on Home and next to the version in Settings; a build made for the `production` track does
not. So publish production from a bundle built for it (the workflow with the `production` track), not by
promoting a test release, which would keep its badge. Choose the countries, then *Send changes for
review*. The review takes from a few hours to a few days.

### E4. Android TV

The same app and bundle serve Android TV and Google TV: the manifest declares the TV launcher, the workflow
sends the TV screenshots (`tvScreenshots`, 1920 × 1080) and the TV banner (`images/tvBanner.png`, 1280 × 720),
and the description names Android TV, as Google asks.

1. **Before opting in, try it on your own TV**: the app is not on the TV's Play Store until Google approves it
   for TV. Install the debug build (`com.markedoe.ondo.debug`, next to the Play version) from the
   **android-builds** artifact of the latest *Android* workflow run: on the TV, *Settings → Device Preferences →
   About*, select *Build* seven times, then *Developer options → USB debugging* (or *Network debugging*); on a
   computer on the same network, `adb connect <TV address>` then `adb install app-debug.apk`. Go through every
   screen with the remote only.
2. **Opt in**: *Test and release → Setup → Advanced settings → Form factors → Add release type → Android TV*,
   accept the TV review policy. Google then checks the app against its TV app quality criteria (from a few days
   to a few weeks) and emails the result; the status is under *Android TV* in the app's settings.
3. Until the TV review passes, the phone releases continue as before. If Google refuses the TV version and it
   holds back a phone update, remove the TV release type and opt in again once fixed.

## F. Things to check before the review

- The store listing clearly states that Ondo **does not provide any content** and does not sell
  subscriptions (already in the descriptions and on the app's home screen). Do not use any wording that
  suggests "free" channels or movies: it is the main reason IPTV players get rejected.
- No screenshot shows a channel logo, a movie poster or a real brand: the screenshots use the tests'
  fictitious catalog.
- `targetSdk` = 36: this is the minimum Google Play requires for new apps and updates since
  August 31, 2026. On large screens (≥ 600 dp), Android 16 ignores orientation locks: Ondo does not use
  any.
- Test the debug APK (**android-builds** artifact of the *Android* workflow) on a phone and a tablet:
  adding an Xtream and an M3U playlist, playing a movie with resume, next episode, live, picture-in-picture,
  light and dark theme, rotation, and going back from the player.
- Then install **the internal test release** (the release bundle, shrunk and optimized by R8, which CI only
  assembles without running it) from the Play Console test link, and redo at least: first launch, adding
  a playlist, opening a detail page, playback, going back and reopening the app (playlists and progress
  must be kept). This is the version testers and then the public will use.

## G. What CI does

| Workflow | When | What it does |
|---|---|---|
| *Android* | pushes to `main` and pull requests touching `android/`, `fastlane/metadata-android/` or the workflow; manual | Kotlin core tests, unit and Compose tests (Robolectric), Roborazzi screenshots (published to the `android-screenshots` branch after a push to `main`), lint (`lintRelease`), debug APK and release bundle (**android-builds** artifact), Google Play listing lengths (title 30, short description 80, full description 4000, release notes 500 characters) |
| *Release Android* | `android-v*` tag or manual | refuses `production` outside an `android-v*` tag, checks that the five secrets are set and that the demo playlist answers, tests, screenshots copied into the store listing, bundle signed with the upload key (*Beta* badge except for `production`), **ondo-release-bundle** artifact, upload to Google Play with the store listing and release notes |
