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

The store listing (title, descriptions, icon, feature graphic, phone and tablet screenshots in English and
French; the app itself is in seven languages, see the README) is uploaded by fastlane from
`fastlane/metadata-android/`; the screenshots are produced by the
Roborazzi tests on every release. The rest is declared once in the console, in the *Set up your app*
section of the dashboard:

### D1. Privacy policy

URL: `https://markedoe.github.io/ondo-iptv/privacy.html` (the site is published by the *Website* workflow).

### D2. App access

*All functionality in my app is available without any access restrictions*. For the review, add an
instruction: "On the welcome screen shown at first launch, tap **Try the demo**: a playlist of public
channels (iptv-org project) is loaded. Ondo does not provide any content; users add their own sources."

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

Ondo has no server, no account, no analytics, no ads and no crash reporting: all data stays on the device,
and network requests only go to the servers the user has entered.

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

### E1. First bundle (manual)

Google does not let the API create the first release: the first AAB is uploaded by hand.

1. *GitHub → Actions → Release Android → Run workflow* (track `internal`, status `draft`). If the fastlane
   upload fails because the app has no release yet, that's fine: download the **ondo-release-bundle**
   artifact (file `app-release.aab`) from the run.
2. *Play Console → Test and release → Internal testing → Create new release*: accept Play App Signing,
   upload `app-release.aab`, add testers (email list) and roll out.
3. Subsequent releases go out from GitHub: `git tag android-v1.0.1 && git push origin android-v1.0.1`
   (internal track, release rolled out), or *Run workflow* with the desired track. The `versionCode`
   increases on every run (1000 + run number), the `versionName` comes from the tag.

### E2. Closed test (recent personal account)

*Test and release → Closed testing → Create track*: add at least **12 testers** (email list or Google
Group), send them the opt-in link, and keep them opted in for **14 consecutive days** (if the number drops
below 12, the counter restarts from zero). Ask them to actually use the app (demo mode, their own
playlists). Then: *Dashboard → Apply for production* and answer the questionnaire about the test.

The workflow also publishes to this track: *Run workflow* with the `alpha` track (= closed testing in
fastlane).

### E3. Production

*Production → Create new release* (or the workflow with the `production` track: the release is created as
a draft, to check and send for review in the console). Choose the countries, then *Send changes for
review*. The review takes from a few hours to a few days.

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
| *Android* | every push touching `android/` | Kotlin core tests, unit and Compose tests (Robolectric), Roborazzi screenshots (published to the `android-screenshots` branch), lint, debug APK and release bundle |
| *Release Android* | `android-v*` tag or manual | tests, bundle signed with the upload key, screenshots copied into the store listing, upload to Google Play with the store listing |
