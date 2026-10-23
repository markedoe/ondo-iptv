# Publishing Ondo on the App Store (iPhone, iPad, Mac)

**English** · [Français](RELEASE.fr.md)

Procedure checked on October 13, 2026 against Apple's and GitHub's pages. Building, signing, uploading to
TestFlight and the listing are automated (fastlane + GitHub Actions); what remains manual depends on your
Apple or GitHub account. Allow about an hour the first time.

## A. GitHub

### A1. GitHub Actions

The `markedoe/ondo-iptv` repository is public: Actions (including macOS runners) and Pages are free of
charge. The listing URLs (`fastlane/metadata/*/…_url.txt`) and the site depend on it: update them if the
repository is renamed. Nothing else to configure: on a public repository, secret scanning and push protection
are enabled by default (you can check in *Settings → Advanced Security*), and workflows from pull requests
by new contributors wait for your approval (*Settings → Actions → General*).

### A2. Enable the site (privacy policy and support)

Apple requires a privacy policy URL. *Settings → Pages → Build and deployment → Source:
GitHub Actions*, then *Actions → Website → Run workflow*. The site is published at
`https://markedoe.github.io/ondo-iptv/`.

### A3. Repository secrets

*Settings → Secrets and variables → Actions → New repository secret*, four secrets:

| Secret | Value |
|---|---|
| `ASC_KEY_ID` | Key ID of the API key (B3), 10 characters |
| `ASC_ISSUER_ID` | Issuer ID (UUID shown at the top of *Users and Access → Integrations → App Store Connect API*) |
| `ASC_KEY_P8` | the whole content of the `AuthKey_XXXX.p8` file, including the `-----BEGIN PRIVATE KEY-----` and `-----END PRIVATE KEY-----` lines (the base64 of the file is also accepted) |
| `DEVELOPMENT_TEAM` | Team ID, 10 characters (*developer.apple.com → Account → Membership details*) |

Never put the `.p8` in the repository (`secrets/` and `*.p8` are ignored by git).

## B. Apple Developer (developer.apple.com)

### B1. Agreement

The **Account Holder** must accept the latest *Apple Developer Program License Agreement* (updated in
August 2026): banner on *developer.apple.com/account*, or *App Store Connect → Business → Agreements*.
Without it, the app cannot be created. For an app with no price, no other agreement is needed, and no
banking or tax information.

### B2. App ID

*Certificates, Identifiers & Profiles → Identifiers → +* → **App IDs** → **App**:

- **Description**: `Ondo` (letters, numbers and spaces only — no periods)
- **Bundle ID**: **Explicit**, `com.markedoe.ondo`
- **Capabilities**: nothing to check (no iCloud or Push; reminders are local notifications)

A single App ID covers the iPhone, the iPad and the Mac.

### B3. App Store Connect API key

*App Store Connect → Users and Access → Integrations → App Store Connect API*:

1. The first time, the Account Holder clicks **Request Access** and accepts.
2. **Team Keys** tab → **Generate API Key** (or **+**) → name `Ondo CI` → **Admin** access → *Generate*.
   - A **Team key** (not an *Individual key*) with the **Admin** role: automatic signing in CI creates
     cloud-managed certificates, which an App Manager or Developer key cannot do.
     The name and role cannot be changed afterwards: if you make a mistake, revoke the key and create it
     again.
   - The role of an existing key is shown in the *Access* column of the list. If the *Release* workflow
     fails at export with `Cloud signing permission error` / `No signing certificate "iOS Distribution"
     found`, the key does not have this role: create one with **Admin** access and replace the
     `ASC_KEY_ID` and `ASC_KEY_P8` secrets (the Issuer ID does not change).
3. **Download API Key**: the `.p8` can only be downloaded once. Write down the *Key ID* and the *Issuer ID*.

## C. App Store Connect (appstoreconnect.apple.com)

### C1. Create the app

*Apps → + → New App*:

- Platforms: **iOS** and **macOS** (a single listing, universal purchase)
- Name: `Ondo – IPTV Player` (if it is taken, let me know: the name comes from
  `fastlane/metadata/*/name.txt`)
- Primary Language: **English (U.S.)**. The listing is localized in English and French
  (`fastlane/metadata/en-US`, `fr-FR`); the App Store lists the seven languages of the app itself (English,
  French, Spanish, Portuguese, Chinese, Arabic, Hindi) from the binary. Listings in the other five languages
  can be added later as `fastlane/metadata/<locale>/` folders.
- Bundle ID: `com.markedoe.ondo` (the list only shows the App IDs created in B2)
- SKU: `ONDO001` (permanent)
- User Access: Full Access

If the app was created for iOS only: sidebar → **Add Platform** → macOS.

### C2. App Information

- **Category**: uploaded by fastlane with the listing, Entertainment
  (`fastlane/metadata/primary_category.txt`) and Photo & Video (`secondary_category.txt`), consistent with
  `LSApplicationCategoryType` (entertainment) in `project.yml`: nothing to choose here.
- **Content Rights**: the app contains no content; it plays the sources that the user adds.
  Answer "No" (it does not embed any third-party content) and keep the explanation in the review notes.
- **Age Rating → Set Up Age Ratings**: answer the questionnaire honestly (no content provided;
  *Parental Controls*: yes — kids profiles and PIN code; no web browser, user-generated content,
  messaging or advertising). Since the user can play any source, use **Override to Higher Age Rating**
  (16+ recommended). Avoid 18+: it is blocked without age verification in Australia, Brazil and Singapore.
- **Digital Services Act (EU)**: *Business → Agreements → Compliance → Digital Services Act* →
  "This is not a trader account" if you earn no revenue from the app. Without an answer, the app is removed
  from the EU.
- **Digital Goods and Services**: "No" (nothing is sold or bought in the app).
- **Privacy Policy URL**: `https://markedoe.github.io/ondo-iptv/privacy.html` (after A2).

### C3. App Privacy

Ondo itself collects nothing (no account, no analytics, no advertising), but the **Google Cast SDK**
4.8.6 built into the iPhone/iPad app declares data collection (the SDK's `NutritionLabel.txt` and
`PrivacyInfo.xcprivacy`); Apple requires it to be declared. *App Privacy → Get Started* →
**"Yes, we collect data"**, then:

| Type | Data | Purposes | Linked to the user | Tracking |
|---|---|---|---|---|
| Location | Coarse Location | Analytics, App Functionality | No | No |
| Identifiers | Device ID | Analytics, App Functionality | No | No |
| Diagnostics | Performance Data, Other Diagnostic Data | Analytics, App Functionality | No | No |
| Usage Data | Product Interaction | Analytics, App Functionality | No | No |

The listing will show "Data Not Linked to You". The app turns off the SDK's optional analytics logging
(`disableAnalyticsLogging`) and the site's privacy policy explains it. Review this at every Cast SDK
update (`project.yml`).

### C4. Pricing and Availability

*Pricing and Availability*: **Free**; countries: all except **China mainland** (it requires an
ICP license).

### C5. App Review Information (in the version to submit)

Contact information (name, phone, email), *Sign-in required*: no. The notes are uploaded by fastlane
(`fastlane/metadata/review_information/notes.txt`): player with no content, how to test with the "Try the
demo" button, why HTTP (`NSAllowsArbitraryLoads`), then each permission, background mode and entitlement:
local network, notifications, background audio, AirPlay and Cast, the Mac sandbox (network client,
user-selected files).

## D. Publish

1. **What's New**: before each new version, write its release notes in
   `fastlane/metadata/en-US/release_notes.txt` and `fastlane/metadata/fr-FR/release_notes.txt`, both together,
   and commit them before the tag (a run started from a tag uploads the listing as the tag holds it). They
   go up with the listing and follow its rules: `check_app_store.py` fails if one is missing, empty or longer
   than 4000 characters, has test or placeholder words, names another platform, promises a feature to come
   or speaks of downloads.
2. **The pipeline.** Two events deliver, nothing else is needed:
   - **A push to `main`.** Once *CI* is green, the *Deliver* workflow starts *Release* with target `builds` and
     the `testflight` edition: iOS and macOS go to TestFlight (internal testers, no review). It sends nothing
     when the commit changed nothing the app ships (`App/`, `AppTests/`, `Packages/`, `project.yml`,
     `fastlane/Fastfile`, `Gemfile*`), when a newer push is already on `main` (that push's delivery sends it), or
     when the commit carries a version tag.
   - **A version tag: your go for App Review** (CLAUDE.md). The tag must equal `MARKETING_VERSION` in
     `project.yml` and `versionName` in `android/app/build.gradle.kts` (the run stops otherwise):
     ```bash
     git tag v1.1.0 && git push origin v1.1.0
     ```
     *Release* (target `release`) builds the **App Store edition** of iOS then macOS, waits until App Store
     Connect has processed each build, then for each platform uploads the listing and the screenshots, selects
     the build it just uploaded and submits it for review, released as soon as Apple approves it. A submission of
     the same version still waiting for review is withdrawn first: the new build replaces it. The Mac is
     released even when iOS fails (two independent versions). Then the TestFlight edition of the tag is built again
     for the testers. The same tag sends Android to closed testing (RELEASE-ANDROID.md).
   - **Rehearsal**: *Actions → Release → Run workflow*, target `release`, `submit` off (the default): the App
     Store edition builds and the listing, without the submission.

   **After a release**, raise `MARKETING_VERSION` in `project.yml` and `versionName` in
   `android/app/build.gradle.kts` to the next version in the same commit: once a version is on the App Store,
   App Store Connect refuses new builds of it, so the TestFlight uploads of the next pushes need the next version.

   Each run of *Release* (macOS 26, Xcode 26 — required by Apple since April 2026) runs the core tests, then three
   checks, any of which stops the run before any build or submission: *Demo playlist answers* (App Review
   tests the app with the demo playlist; every target but `status`), *App Store edition checks*
   (`check_app_store.py` and the listing's addresses, see E; for the App Store edition, and for the targets
   `all`, `release`, `submit` and the three `metadata` ones whatever the edition) and *Privacy manifest and Mac
   sandbox checks* (`check_privacy_manifest.py` and `check_mac_entitlements.py`; every target that builds or
   submits). Build number = the platform's latest TestFlight build + 1.

   **Version.** A run started from a tag uses the tag's version (`v1.1.0` → `1.1.0`; a `v` tag that is not a
   version, such as `v1.1.0-rc1`, stops the run), any other run `MARKETING_VERSION` in `project.yml`; the
   `metadata`, `submit` and `status` targets pick the App Store version the same way.

   **One run at a time.** Two runs would take the same build number, so a run started while another one is
   going waits for it, whatever its tag or branch. GitHub keeps only one waiting run: starting a third one
   cancels the one that was waiting (never a running one). Start the next run once the previous one has
   finished.

   **Targets** (*Run workflow*, by hand):
   - `release`: what a tag does (above); `submit` off is a rehearsal;
   - `builds`: both builds without the listing (what a push to `main` sends to TestFlight);
   - `all`: both builds, then the listing;
   - `ios` or `mac`: one build;
   - `metadata`: the listing only (`metadata-ios` or `metadata-mac` for one platform);
   - `submit`: the submission for review (step 5); leave `ios_build` or `mac_build` empty to submit one
     platform only. A failed check submits neither; a failed iOS submission does not stop the macOS one;
   - `status`, read only: the state of each version and its selected build, whether the review contact is
     complete and the review notes mention downloads or `network.server`, the screenshots compared with the
     `appstore-screenshots` branch, and the review submissions.
3. **TestFlight**: builds appear after 10 to 30 min of processing. Internal testers group
   (up to 100 team members, no review) to try it on your devices.

   **Two editions.** *Run workflow* asks for an `edition`: `testflight` (the default) builds the app with
   downloads, for internal testers only; `app-store` builds it without (Apple's rule 5.2.3 on saving media
   from other sources), and is **the only one to submit**. A tag builds the App Store edition. The App Store
   edition is built without the download code (`#if TESTFLIGHT_DOWNLOADS || DEBUG`), so nothing in it can
   turn downloads on: a feature switched off, or an unlock code, would be a hidden or dormant feature, which
   rule 2.5.19 forbids and for which Apple can remove a developer from its program. TestFlight edition builds
   stay with internal testers: external testing goes through App Review. TestFlight offers testers the latest
   build of each platform, and each run builds one edition: a tag builds the TestFlight edition again by itself
   after the App Store one; after an App Store edition built by hand (`ios`, `mac` or `all`), run *Release* again
   with target `builds` and `edition` left at `testflight`, otherwise testers get the build without downloads.

   **Beta badge.** The TestFlight edition shows a *Beta* badge (Home toolbar, version in Settings); the App Store
   edition is built without it, because betas belong on TestFlight, not on the App Store (rule 2.2). Nothing in
   the App Store build, its listing or the site pages the listing opens (home, support, privacy: App Review visits
   them) may call the app a beta; `check_app_store.py` checks all three. The word stays in the string catalogue of
   both editions: a translation nobody sees is not a feature, leave it.
4. **Screenshots**: iPhone 6.9", iPad 13" and Mac (1280×800) are generated by the *App Store
   screenshots* workflow (FR/EN) on the `appstore-screenshots` branch; the *Release* workflow uploads them
   with the listing. Re-run *App Store screenshots* before a release if the interface has changed.

   **Header and search results** (iPhone and iPad on iOS / iPadOS 27 and later; not the Mac): the *Header and
   Search Results* tab of the version page. The images are `fastlane/creative/<locale>/header.png` (21:9,
   3840×1646, PNG only) and `search.jpg` (3:2, 3840×2560), in English and French, drawn from the App Store screenshots by
   `fastlane/creative/render.mjs` (see its first lines); render them again when the screenshots change.
   *Actions → App Store header and search results → Run workflow*: `status` shows what App Store Connect holds
   (read only), `upload` puts the images in the Asset Library without touching any version, `place` shows them
   on the iOS version being prepared. A version in review or approved cannot change: `place` refuses it. The
   assets are reviewed with the version they are placed on, or on their own: `submit` sends them for review
   once an iOS version is approved, and refuses while an iOS version or review submission is in review, so that
   nothing changes what App Review is looking at. Once Apple approves them, `publish` shows them on the live
   version without a new version (or in App Store Connect: Header and Search Results → Browse Assets →
   Publish). Nothing is submitted without your go.
5. A tag submits by itself (step 2). By hand, in each version (iOS and macOS, e.g. 1.1.0): choose the build of the
   **app-store** edition → **Add for Review** → **Submit for Review**. Or *Run workflow* with target `metadata` (the listing;
   `metadata-ios` or `metadata-mac` for one platform, since a version in review cannot be edited), then target
   `submit` with `ios_build` and `mac_build`: the build numbers of the App Store edition, read in the log of
   the release run that built it (`CURRENT_PROJECT_VERSION=…`), never the latest build, which may be a
   TestFlight edition one. The version is released as soon as Apple approves it. The two versions are
   independent: **iOS / iPadOS can ship on its own**; before submitting the macOS version, install from
   TestFlight the App Store edition build you will submit (by its build number: the latest Mac build is
   normally a TestFlight edition one) and run it on your Mac at least once.

If a run fails (demo playlist, App Store edition checks, signing, export, upload), the job log says why. To
sign the archive, Xcode creates an *Apple Development* certificate on each new runner; its private key
disappears with the runner, so the workflow revokes it when the job ends (step *Revoke this runner's
development certificate*, matched by serial number: distribution certificates and your Mac's certificate are
not touched). If signing fails with `Your account has reached the maximum number of certificates`,
certificates from earlier runs were left behind: in *Certificates, Identifiers & Profiles → Certificates*,
revoke the *Apple Development* certificates created by CI (keep your Mac's), then run the workflow again.
TestFlight builds are not affected.

## E. Things to check before the review

- `.github/scripts/check_app_store.py` passes (CI, the security workflow and the release workflow run it):
  - the App Store edition compiles no download code, no feature switched off for good and no text that calls
    the app a beta, and uses no private API; no build setting turns `TESTFLIGHT_DOWNLOADS` on for it, and a
    tag builds it; `FeatureFlags` reads nothing at run time (no setting, server, date, file or argument), and
    no code reads the launch arguments or the environment outside `#if DEBUG` (the UI test hooks);
  - the listing (release notes included) fits App Store Connect's limits, names no other platform, promises
    nothing to come, has no test or placeholder words and says nothing of downloads; the site pages it opens
    (home, support, privacy) have no test words either;
  - the copyright is this year's and the same as `NSHumanReadableCopyright` in `project.yml`;
  - the review notes (4000 characters at most) name no other platform, promise nothing to come, have no
    placeholder words and say nothing of downloads; they explain `NSAllowsArbitraryLoads` and every
    permission, background mode and Mac entitlement.
- The support, privacy and website addresses answer (the release workflow checks them).
- `ITSAppUsesNonExemptEncryption = NO` is declared: no export compliance question on each build.
- `PrivacyInfo.xcprivacy` is present (reasons for using sensitive APIs) and covers the shipped code, and the Mac
  sandbox has exactly the entitlements the app needs: `check_privacy_manifest.py` and
  `check_mac_entitlements.py` pass (CI runs them, and the release workflow before every upload).
- The screenshots only show the generated demo (drawn posters, fictional channels): no logo or
  protected visual.

## F. Manual checks on a device (TestFlight)

The CI builds, tests the logic and walks through the interface in the simulator with demo streams;
these points require a real device and a real subscription before submission:

- **Movies & series** from an Xtream account: home (carousel, resume), detail pages, next episode, resume.
- **Live with a DVR window** (long HLS "event" / "sliding window"): pause, rewind, scrubber
  and "Live" button; without DVR, a pause longer than 10 s resumes from the archive (channel with replay).
- **Restart / Replay** a program on an Xtream account and on a `catchup` M3U playlist.
- **Chromecast** (iPhone/iPad) and **AirPlay** (iPhone, iPad, Mac) to a TV; local network permission
  requested on the first Cast; local playback resumes when casting stops.
- **Picture in Picture** and background playback; lock screen controls.
- **MKV movies and episodes**: on iPhone and iPad, remuxed for the system player (HDR10 / Dolby Vision,
  Dolby Atmos, Picture in Picture), VLC for a codec it cannot decode (DTS, TrueHD); on the Mac, VLC.
  **AVI** with VLC.
- **Reminders**: permission requested on the first reminder, notification received, tapping the
  notification opens the channel (app closed and open).
- **Multiview** (iPad, Mac) with 4 real streams: smoothness, a single audio track, switching to full screen.
- **Mac**: player / multiview windows, Playback menu and shortcuts.
