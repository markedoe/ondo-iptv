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

- **Category**: Entertainment (consistent with the Mac app).
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

### C5. App Review Information (in version 1.0)

Contact information (name, phone, email), *Sign-in required*: no. The notes are uploaded by fastlane
(`fastlane/metadata/review_information/notes.txt`): player with no content, "Try the demo" button,
HTTP, Cast and notifications.

## D. Publish

1. *Actions → Release → Run workflow* (target `all`), or a tag:
   ```bash
   git tag v1.0.0 && git push origin v1.0.0
   ```
   The workflow (macOS 26, Xcode 26 — required by Apple since April 2026) runs the core tests,
   builds, signs and uploads **iOS** then **macOS** to TestFlight (build = latest TestFlight build + 1,
   version = the tag), then the FR/EN listing and the iPhone / iPad screenshots.
2. **TestFlight**: builds appear after 10 to 30 min of processing. Internal testers group
   (up to 100 team members, no review) to try it on your devices.
3. **Screenshots**: iPhone 6.9", iPad 13" and Mac (1280×800) are generated by the *App Store
   screenshots* workflow (FR/EN) on the `appstore-screenshots` branch; the *Release* workflow uploads them
   with the listing. Re-run *App Store screenshots* before a release if the interface has changed.
4. In each version (iOS 1.0 and macOS 1.0): choose the build → **Add for Review** →
   **Submit for Review**. The two versions are independent: **iOS / iPadOS can ship on its own**;
   run the Mac build from TestFlight on your Mac at least once before submitting the macOS version.

The *Release* workflow has never run yet: if its first run fails (signing, export, upload), the job log
says why — it is usually fixed with a one-line change in `fastlane/Fastfile`.

## E. Things to check before the review

- `ITSAppUsesNonExemptEncryption = NO` is declared: no export compliance question on each build.
- `PrivacyInfo.xcprivacy` is present (reasons for using sensitive APIs).
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
- **Picture in Picture** and background playback; lock screen controls; VLC (MKV, AVI).
- **Reminders**: permission requested on the first reminder, notification received, tapping the
  notification opens the channel (app closed and open).
- **Multiview** (iPad, Mac) with 4 real streams: smoothness, a single audio track, switching to full screen.
- **Mac**: player / multiview windows, Playback menu and shortcuts.
