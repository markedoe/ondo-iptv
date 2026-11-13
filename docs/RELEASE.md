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
- Primary Language: **English (U.S.)**. The listing is localized in the seven languages of the
  app (`fastlane/metadata/en-US`, `fr-FR`, `es-ES`, `es-MX`, `pt-BR`, `zh-Hans`, `ar-SA`, `hi`); the screenshots
  are English and French, and the other languages show the primary language's. A new language's listing reaches
  App Store Connect with the next tag.
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
(`fastlane/metadata/review_information/notes.txt`): player with no content, how to test with the test playlist
hosted on the project's site (`https://markedoe.github.io/ondo-iptv/review/ondo-review.m3u`, see
[site/review](../site/review/README.md): our own test channel and series, Blender Foundation films under CC BY;
the apps ship no demo), why HTTP (`NSAllowsArbitraryLoads`), then each permission, background mode and entitlement:
local network, notifications, background audio, AirPlay and Cast, the Mac sandbox (network client,
user-selected files).

## D. Publish

1. **What's New**: before each new version, write its release notes in
   `fastlane/metadata/en-US/release_notes.txt` and `fastlane/metadata/fr-FR/release_notes.txt`, both together,
   and commit them before the tag (a run started from a tag uploads the listing as the tag holds it). They
   go up with the listing and follow its rules: `check_app_store.py` fails if one is missing, empty or longer
   than 4000 characters, has test or placeholder words, names another platform, promises a feature to come
   or speaks of downloads.
2. **The pipeline.** Two events deliver, and *Ship* tags the next version by itself after a green night:
   - **A push to `main`.** Once *CI*'s short checks are green (Linux, a few minutes; the long macOS tests run at
     night, [CI.md](CI.md#short-checks-on-every-push-long-tests-at-night)), the *Deliver* workflow starts *Release* with target `builds` and
     the `testflight` edition and `external` on: for iOS and macOS, first the **App Store edition** (checked as for a
     release: `check_app_store.py`, review playlist, listing addresses), uploaded for the **external testers**, then
     the **TestFlight edition** (internal testers, no review), whose build number comes after it; a job on Linux then
     waits for App Store Connect's processing and sends the App Store edition to the external groups through Beta App
     Review (step 3, *External testers*). It sends nothing
     when the commit changed nothing the app ships (`App/`, `Packages/` without their tests, `project.yml`,
     `fastlane/Fastfile`, `Gemfile*`), when a newer push on `main` changes what the app ships (that push's delivery
     sends it), or when the commit carries a version tag.
   - **A version tag: your go for App Review** (CLAUDE.md). The tag must equal `MARKETING_VERSION` in
     `project.yml` and `versionName` in `android/app/build.gradle.kts` (the run stops otherwise), and *CI*'s long
     (macOS) jobs must have passed **on the tagged commit**: the night's run on `main`, or *CI* started by hand on it.
     Otherwise the first job, *Long tests passed on this commit*, refuses before anything is built, captured or
     submitted; start *CI* by hand on the tag (`gh workflow run ci.yml --ref v1.1.0`) or wait for the night, then
     *Re-run all jobs* on the release. The same applies to target `release` with `submit` (the commit the run is
     started from, which it builds) and to target `submit` (the tag it is started from, below). The check exists only
     in the workflow files of commits made after it was added: a run by hand uses the workflow file of the ref it starts
     from, so an older tag or branch has no check. Submit and publish production only from a tag cut after this change:
     ```bash
     git tag v1.1.0 && git push origin v1.1.0
     ```
     *Release* (target `release`) builds the **App Store edition** of iOS then macOS, waits until App Store
     Connect has processed each build, then for each platform uploads the listing and the screenshots, selects
     the build it just uploaded and submits it for review, released as soon as Apple approves it. A submission of
     the same version still waiting for review is withdrawn first: the new build replaces it. The Mac is
     released even when iOS fails (two independent versions). Then the TestFlight edition of the tag is built again
     for the testers, unless another *Release* run is already waiting (a newer tag's, a push's builds): it is not
     replaced. The same tag sends Android to closed testing (RELEASE-ANDROID.md).
   - **Ship, after each green night: the standing go** (CLAUDE.md). The *Ship* workflow (`ship.yml`, every day at
     13:17 and 19:17 UTC, some hours after the night's runs; `.github/scripts/ship.py`) does what a tag push does, by itself,
     when every gate holds, and otherwise ships nothing and says why in its log and summary:
     - the latest scheduled runs of *CI* and *Android* on `main` both passed **on the same commit**, that commit is on
       `main`, and `ci_scope.py production ci|android` accept it (the long tests passed on exactly it). That commit
       is the one shipped, never a newer `main`;
     - `MARKETING_VERSION` and `versionName` at that commit are equal and above the highest `vX.Y.Z` tag (as numbers:
       2.0.10 comes after 2.0.9), and that tag does not exist yet. With no version tag at all, Ship does nothing:
       create the first one on the commit last released, either with *Run workflow* on *Ship* with `baseline_tag`
       (e.g. `v2.0.0`), `baseline_sha` and `dry_run` off (it checks that the commit is on `main` and carries that
       version, creates the tag and starts nothing), or by pushing it yourself (which also releases it);
     - every App Store language's `release_notes.txt` and every Google Play language's `changelogs/default.txt`
       were rewritten since that tag (the log lists the missing ones);
     - something the apps ship changed since that tag (*Deliver*'s own paths; a version bump alone does not count);
     - nothing waits or runs in the release queues (*Release*, *Release Android*, *Promote Android*, *Play
       catch-up*, the Google Play status, images and tips workflows): nothing waiting is ever pushed out;
     - Apple only: App Store Connect, read only, shows no iOS or macOS version waiting for or in review, approved but
       not released, rejected or in any state not known to be settled, no review submission under way, and no
       version at or above this one already past preparation. A read error blocks Apple;
     - Google Play only: no review seems to be going on. No API shows a review; Ship reads the tracks as *Play
       catch-up* does (inside an edit that is deleted, never committed), and closed testing behind internal testing
       (a promotion waiting for a review to end) or an unreadable answer holds Android back.

     One platform blocked, the other still goes when its gates hold, and the tag is created for it.

     **Catch-up on the existing tag.** When the version of the night's commit is exactly the highest tag (the same
     string, as *Release* compares it) and a platform did not get it, Ship starts that platform's release on **that
     tag**, with no new tag, when the tag is on the night's history and carries that version, it passed the notes and
     shipped-change gates against the tag before it (a baseline tag never does, so Ship never releases it), the
     platform's long tests passed on its commit and the queues are idle:
     - Apple: only if **no *Release* run ever ran on the tag** (one running: Ship waits; one passed: nothing to do; one
       failed: a human looks, the Ship run turns red and Ship never starts it again), Apple's gates hold and App Store
       Connect has no version of that number past preparation on iOS or macOS (waiting, in review, approved, live,
       rejected or withdrawn: a human). By hand: `gh workflow run release.yml --ref vX.Y.Z -f target=release -f submit=true`;
     - Google Play: when Google Play holds no bundle of the version code a *Release Android* run on the tag built
       (1000 + its run number), no review seems to be going on, and no run on the tag failed (red, a human) or is
       running. A green run that delivered nothing (Google Play refused the internal upload during a review, which
       `KEEP_PLAY_REVIEW` turns into a warning) is followed by one more at most; after two, the Ship run turns red and
       a human looks.

     When it ships, it creates the tag on that commit (GitHub API) and starts *Release* (target `release`, `submit`)
     and *Release Android* from the tag. Android goes through internal testing with the listing (`internal`,
     `completed`, listing `upload`), as a push's delivery does: every Google Play change then waits for a review in
     progress instead of cancelling and restarting it (RELEASE-ANDROID.md), and the build reaches closed testing for
     review right after, or through *Play catch-up* once the review is over. A tag made by a workflow fires no push, so
     Ship starts them itself; a tag you push yourself starts them through its push, and Ship then only catches up a
     platform whose release never ran on that tag (or, for Google Play, ran green without delivering). If a start
     fails once the tag exists, the run turns red and gives the command to run by hand.
     Ship never bumps a version, never commits and never touches Google Play production. **When you land work, write
     its release notes in every language and raise the version** (below): Ship ships nothing otherwise. A run by hand
     is a dry run by default (*Run workflow*, `dry_run` on): it says what would ship and creates nothing; only `main`
     ships.
   - **Withdraw from review** (the user's decision): *Actions → Release → Run workflow*, target `withdraw-ios` or
     `withdraw-mac`: removes that platform's submission from App Review and changes nothing else.
   - **Rehearsal**: *Actions → Release → Run workflow*, target `release`, `submit` off (the default): the App
     Store edition builds and the listing, without the submission.

   **After a release**, raise `MARKETING_VERSION` in `project.yml` and `versionName` in
   `android/app/build.gradle.kts` to the next version in the same commit: once a version is on the App Store,
   App Store Connect refuses new builds of it, so the TestFlight uploads of the next pushes need the next version.

   Each run of *Release* (macOS 26, Xcode 26.6 as in CI: `DEVELOPER_DIR` of each macOS job; Xcode 26 is
   required by Apple since April 2026) runs the core tests (a TestFlight edition run skips them when CI already
   passed, its macOS jobs run, on the same commit or tree, which a push's run never has: a push's delivery runs them
   here; the App Store edition always runs them; [CI.md](CI.md)), then three
   checks, any of which stops the run before any build or submission: *Review playlist answers* (App Review
   tests the app with the playlist the review notes give: `check_review_playlist.py --online` fetches it from
   main's site and checks that each of its streams and its guide answer; App Store edition only, every target
   but `status` and the withdrawals), *App Store edition checks*
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
   - `builds`: both builds without the listing; with `edition` `testflight` and `external` on, what a push to `main`
     sends (the App Store edition for the external testers first);
   - `all`: both builds, then the listing;
   - `ios` or `mac`: one build;
   - `metadata`: the listing only (`metadata-ios` or `metadata-mac` for one platform);
   - `submit`: the submission for review (step 5), started from the version's tag (*Use workflow from → Tags*: from a
     branch or another tag the run refuses), with `ios_build` and `mac_build` taken from **that tag's own release run**:
     App Store Connect does not say which commit a build came from, so the tag ties the builds to the commit whose long
     tests the run checks. Leave `ios_build` or `mac_build` empty to submit one platform only. A failed check submits neither; a failed iOS submission does not stop the macOS one;
   - `status`, read only: the state of each version and its selected build, whether the review contact is
     complete and the review notes mention downloads or `network.server`, the screenshots compared with the
     `appstore-screenshots` branch, and the review submissions.
3. **TestFlight**: builds appear after 10 to 30 min of processing. Internal testers group
   (up to 100 team members, no review) to try it on your devices. The uploads of pushes to `main` turn off
   TestFlight's e-mail to the testers for each new build (`quiet_testers` in the Fastfile; a tag's upload waits for
   the processing, so Apple may have sent it already): the TestFlight app shows the latest one.
   Each tester can also turn off the e-mails of an app in the TestFlight app (the app's page, *Email
   Notifications*).

   **What to Test** is the same on every build, internal and external, TestFlight and App Store editions, iOS and
   macOS, pushes and tags: the release notes of the listing (`fastlane/metadata/<language>/release_notes.txt`) in each
   of its languages, set as soon as App Store Connect lists the build (`fill_what_to_test`: the build's localizations
   only, nothing is distributed or submitted). A build where it could not be set fails the run (step *What to Test*)
   once every upload is done.

   **Two editions.** *Run workflow* asks for an `edition`: `testflight` (the default) builds the app with
   downloads, for internal testers only; `app-store` builds it without (Apple's rule 5.2.3 on saving media
   from other sources), and is **the only one to submit**. A tag builds the App Store edition. The App Store
   edition is built without the download code (`#if TESTFLIGHT_DOWNLOADS || DEBUG`), so nothing in it can
   turn downloads on: a feature switched off, or an unlock code, would be a hidden or dormant feature, which
   rule 2.5.19 forbids and for which Apple can remove a developer from its program. TestFlight edition builds
   stay with internal testers; the external testers get the App Store edition (below). TestFlight offers testers the latest
   build of each platform, and each run builds one edition: a tag builds the TestFlight edition again by itself
   after the App Store one; after an App Store edition built by hand (`ios`, `mac` or `all`), run *Release* again
   with target `builds` and `edition` left at `testflight`, otherwise testers get the build without downloads.

   **External testers** (CLAUDE.md, the owner's standing go). Every push to `main` that delivers sends the **App Store
   edition**, exactly the build App Review would get, to **every external TestFlight group** of the app (by id,
   whatever its name; `External testers` is created only when the app has none), through
   **Beta App Review** (never App Review: the App Store version and its submission are not touched). The TestFlight
   edition never goes there: `check_app_store.py` fails if a lane or a workflow could distribute it externally or
   submit it for beta review, or if the external path builds anything but the App Store edition.
   - *Release*'s macOS job builds and uploads the App Store edition of iOS then macOS without waiting, each before the
     TestFlight edition of its platform (`AFTER_BUILD_NUMBER`: the TestFlight edition takes the next number, so the
     internal testers' latest build stays the TestFlight edition). A failed check or App Store edition build does not
     stop the TestFlight edition, and step *External testers builds* turns the run red. The core tests run once in
     this job, before both editions (a push's *CI* run has the short checks only; its macOS jobs come at night). If the TestFlight edition fails after
     the App Store edition went up, the internal testers' latest build of that platform is the App Store edition one
     (without downloads) until the next delivery; the run is red. Only a run on `main` sends anything to external
     testers (never a tag, never another branch).
   - *Release*'s job *External testers* (Linux) hands the two build numbers to the workflow *TestFlight external*
     (`testflight-external.yml`), which has its own queue: its wait (up to 3 hours) never holds back a tag or the next
     delivery, and a newer push's builds replace builds still waiting there. It runs only when *Release* started it
     (a run started by hand stops), on `main`, with `fastlane ios external_testers` then `fastlane mac
     external_testers`: the groups as App Store Connect holds them (each external group's name, testers and public link are logged; internal groups are listed and never used), the processing first (up to 90 min per build), then
     the beta review slot (a build of the same version still waiting for beta review is expired and replaced, never a
     build an App Store version uses: the lane stops instead; while one is in review, the new build is not sent and
     the next push's follows), *Test Information* (the beta app description: the listing's `description.txt`, with
     its privacy and marketing addresses; *What to Test*: its `release_notes.txt`; the review notes and test playlist:
     `review_information/notes.txt`; no sign-in; export compliance: no non-exempt encryption), language by language:
     a language App Store Connect refuses is logged, the others are set and the build is sent, and the run is red at
     the end. Then the build is submitted for Beta App Review and added to every external group; the testers are e-mailed once
     Apple approves it.
   - **What App Store Connect holds** (*TestFlight → Test Information*, the groups and their testers) is read, never
     assumed: the job logs each group, internal or external, and how many testers it has, whether the **Feedback Email**
     and the Beta App Review contact are set (never their values), and stops only when one is really missing, naming
     the field. The Feedback Email and the testers' addresses are never in the repository; testers are added in
     *TestFlight → External Testing*, by e-mail or with a public link. An empty Beta App Review contact is copied from
     the App Review contact. The first build of a version waits for Beta App Review (usually within a day or two);
     the next builds of that version are often approved within minutes.
   - **No iOS build on Macs.** A Mac with Apple silicon can install the iOS build from TestFlight, and the Mac app
     (same bundle ID) then refuses to install until it is deleted. Each TestFlight group has the setting (*TestFlight →
     the group → Settings → Test iPhone and iPad apps on Apple silicon Macs*; API: `iosBuildsAvailableForAppleSiliconMac`):
     the external testers job turns it off in every group where it is on, and logs it. It does not remove an iOS build
     already installed on a Mac: delete it there, then install the Mac build. The App Store's own setting (*Pricing and
     Availability → iPhone and iPad Apps on Apple Silicon Mac → Make this app available*) is not in the API: App Store
     Connect shows it only while no macOS version is on the Mac App Store, and *TestFlight status* says whether one is.
   - **To look before changing anything**: *Actions → TestFlight status → Run workflow* (`testflight-status.yml`,
     Linux, read only, outside the release queue): the groups, their tester counts and whether they offer iOS builds
     on Apple silicon Macs, whether a macOS version is on the Mac App Store, Test Information per language
     (set or missing), the Beta App Review contact (set or missing), and each platform's latest builds with whether
     they are in an external group, their internal, external and beta review states and their
     What to Test.
   - By hand: *Run workflow*, target `builds`, `edition` `testflight`, `external` on.

   **No beta badge.** No edition shows one. Nothing in the app, its listing or the site pages the listing opens
   (home, support, privacy: App Review visits them) may call the app a beta (rule 2.2); `check_app_store.py`
   checks all three.
4. **Screenshots**: iPhone 6.9", iPad 13" and Mac (1280×800) are generated by the *App Store
   screenshots* workflow (FR/EN) on the `appstore-screenshots` branch; the *Release* workflow uploads them
   with the listing. *Release* runs it first, for a tag and the targets `release`, `all` and `metadata*`, and the
   listing waits for it. The listing must match the build: when a capture fails, a tag and the targets `release` and
   `all` stop before anything is built, uploaded or submitted; re-run *App Store screenshots*, then the release
   (*Re-run all jobs*). Only the `metadata*` targets, which submit nothing, go on with the screenshots already on the
   branch. It no longer runs on pushes: run it by hand to look at the screenshots before a release
   ([CI.md](CI.md#app-store-screenshots)).

   **Header and search results** (iPhone and iPad on iOS / iPadOS 27 and later; not the Mac): the *Header and
   Search Results* tab of the version page. The images are `fastlane/creative/<locale>/header.png` (21:9,
   3840×1646, PNG only) and `search.jpg` (3:2, 3840×2560), in every language of the listing (a test fails on a missing
   one; a language without screenshots of its own shows the English ones), drawn from the App Store screenshots by
   `fastlane/creative/render.mjs` (see its first lines); render them again when the screenshots change.
   *Actions → App Store header and search results → Run workflow*: `status` shows what App Store Connect holds
   (read only), `upload` puts the images in the Asset Library without touching any version, `place` shows them
   on the iOS version being prepared. A version tag's release and target `submit` do both on their own, after the listing
   and before the iOS submission, so every submitted iOS version carries them. A version in review or approved cannot
   change: `place` refuses it. The
   assets are reviewed with the version they are placed on, or on their own: `submit` sends them for review
   once an iOS version is approved, and refuses while an iOS version or review submission is in review, so that
   nothing changes what App Review is looking at. Once Apple approves them, `publish` shows them on the live
   version without a new version (or in App Store Connect: Header and Search Results → Browse Assets →
   Publish). Nothing is submitted without your go.

   **Tips (In-App Purchases)**: the same workflow with `iap` reads, without changing anything (GET requests only),
   where the three consumable tips stand on App Store Connect (`com.markedoe.ondo.tip.small`, `.medium`, `.large`,
   from `App/Model/Tips.swift`): for each product its type, state, Family Sharing, whether a review note is set,
   each localization (name, description set or not), the price schedule (base territory and customer price), the
   number of territories it is available in, the review screenshot and its delivery state, and its versions; then the
   tips App Store Connect lacks, and every App Store version of each platform with its state. A read the API key may
   not make (HTTP 403) is reported and the rest goes on.

   **Creating the tips**: `iap_create` creates the three tips, or completes them, from `fastlane/tips.json` (the
   apps' own names in the seven languages and a description per language, within Apple's 30 and 45 characters):
   consumable, Family Sharing off, reference names `Tip small` / `Tip medium` / `Tip large`, an English review note
   naming the product, saying it unlocks nothing and where to find it (Settings → Leave a tip), a name and
   description in every listing language (the localizations of the tip's version, API 4.4.1 and later), the price
   with France as base territory (the price point whose customer price is exactly 1.99, 4.99 or 9.99 EUR; none is
   an error, never a nearby price; the other territories follow Apple's equalization), all territories and the new
   ones, and the review screenshot `fastlane/creative/iap-review.png` when it exists (or the file given:
   `asset_library.py iap_create path/to.png`). It is idempotent: what is already right is left alone, nothing is
   deleted (a different, finished review screenshot already there is kept: delete it in App Store Connect to
   replace it; a half-uploaded or refused one is replaced), nothing is submitted. The `iap` report shows for each
   tip version whether Apple holds a review image on the version itself (API 4.4.1): check it after the first run. A tip App Review holds is not touched.

   **Submitting the tips**: Apple reviews a first In-App Purchase together with an app version. A tag's release
   uploads the review screenshot its screenshots job captured (artifact `iap-review-screenshot`, `en-US` file) to
   each tip that has none, then, for iOS and then macOS, asks `asset_library.py iap_pending` which tips are
   `READY_TO_SUBMIT`. When some are, and only when the App Store edition sells the tips (StoreKit in its build, the
   test of `check_app_store.py`), deliver uploads the listing without submitting and `asset_library.py iap_submit`
   selects the build, puts the version and each tip's version in one review submission and submits it. Otherwise
   (tips approved, with App Review, or an App Store edition without them) deliver submits as before. A tip not ready
   (missing, or lacking a part such as its screenshot, rejected, needing action, removed from sale) stops the
   submission when the build sells the tips: App Review would find tips it cannot buy. That check runs before the
   withdrawal of a waiting submission too, so a release never cancels one and then refuses. Everything is checked
   before `iap_submit` writes anything; a draft submission left by an interrupted run is completed on the next one
   when it holds only this version and its tips, never when it holds anything else. The tips go
   with the iOS version; the macOS version takes those still waiting. precheck still leaves In-App Purchases out:
   it cannot read them with an App Store Connect API key. The `submit` target does the same.

   **What only you can do** (no API shows it): accept the **Paid Applications** agreement and fill in the banking
   and tax forms in App Store Connect (*Business*); without them App Store Connect does not sell In-App Purchases.
5. A tag submits by itself (step 2). By hand, in each version (iOS and macOS, e.g. 1.1.0): choose the build of the
   **app-store** edition → **Add for Review** → **Submit for Review**. Or *Run workflow* with target `metadata` (the listing;
   `metadata-ios` or `metadata-mac` for one platform, since a version in review cannot be edited), then target
   `submit` with `ios_build` and `mac_build`: the build numbers of the App Store edition, read in the log of
   the release run that built it (`CURRENT_PROJECT_VERSION=…`), never the latest build, which may be a
   TestFlight edition one. The version is released as soon as Apple approves it. The two versions are
   independent: **iOS / iPadOS can ship on its own**; before submitting the macOS version, install from
   TestFlight the App Store edition build you will submit (by its build number: the latest Mac build is
   normally a TestFlight edition one) and run it on your Mac at least once.

If a run fails (review playlist, App Store edition checks, signing, export, upload), the job log says why. To
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
    permission, background mode and Mac entitlement, and give the test playlist hosted on our own site; no
    playlist address in the apps or the notes points anywhere else (the apps ship no demo).
- The support, privacy and website addresses answer (the release workflow checks them).
- `ITSAppUsesNonExemptEncryption = NO` is declared: no export compliance question on each build.
- `PrivacyInfo.xcprivacy` is present (reasons for using sensitive APIs) and covers the shipped code, and the Mac
  sandbox has exactly the entitlements the app needs: `check_privacy_manifest.py` and
  `check_mac_entitlements.py` pass (CI runs them, and the release workflow before every upload).
- The screenshots only show the generated showcase (open films, fictional channels): no logo or
  protected visual.

## F. Manual checks on a device (TestFlight)

The CI builds, tests the logic and walks through the interface in the simulator with test streams;
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
