# Ondo repository rules

This repository is **public**. Nothing private may enter it: not in files, not in commit messages, not in
screenshots.

- **Secrets**: App Store Connect keys (`.p8`), Key ID, Issuer ID, Team ID, the Android keystore (`.jks`) and
  its passwords, the Google Play service account JSON, tokens… go only into GitHub secrets
  (`Settings → Secrets and variables → Actions`, see `docs/RELEASE.md` and `docs/RELEASE-ANDROID.md`).
  Never write them in a repository file, a commit, a log or a CI artifact.
- **Privacy**: no personal email address (only `noreply` addresses), no real name, no personal folder path,
  no local network IP address, no real IPTV subscription data (panel URL, credentials). Tests and screenshot
  fixtures use `example.com` and public streams.
- **Neutrality**: do not name other IPTV applications; Ondo is a player, it provides no content; never use
  "free" / "gratuit" to describe IPTV.
- **Always bilingual, English and French**: everything written for people exists in both languages and both
  are updated together: documentation in English with a French version next to it (`README.fr.md`,
  `docs/*.fr.md`), store listings (`en-US` and `fr-FR`), the website, release notes. Code, comments and
  commit messages stay in English.
- **Commit messages**: a short subject line and, when needed, one or two short lines on what changed. Never
  describe or quote the requests or the conversation behind a change.
- **The apps speak seven languages**: English, French, Spanish, Portuguese (Brazilian wording), Chinese
  (Simplified), Arabic (right to left) and Hindi. Every text of a new feature is translated into all of them
  in the same change: Apple `App/Resources/Localizable.xcstrings` (`fr`, `es`, `pt`, `zh-Hans`, `ar`, `hi`)
  and Android `res/values-{fr,es,pt,zh-rCN,ar,hi}/strings.xml`. `check_translations.py` (CI) fails on a
  missing Apple translation and Android lint on a missing Android one.
- **App Store: never get banned by Apple.** Apple can remove a developer from its program for hidden or dormant
  features (App Review Guidelines 2.5.19) and for getting around its rules, so:
  - The build submitted is the build App Review sees. A feature the App Store edition does not have is left out of
    its build (`#if TESTFLIGHT_DOWNLOADS || DEBUG`), never switched off at run time: no unlock code, no remote
    switch, no setting, date or launch argument that turns something on, no constant `false` feature left in.
  - The TestFlight edition (downloads) goes to internal testers only and is never submitted, not even for Beta App
    Review; a tag or
    `edition: app-store` builds the App Store edition. The user has given a standing go for production: the Ship
    workflow (`ship.yml`, after the night) tags the night-tested commit `vX.Y.Z` and starts what a tag push starts (iOS
    and macOS to App Review, Android with its listing to closed testing for review) only when its gates hold: the
    night's CI and Android long tests passed on that same commit, the apps' equal version above the last `v*` tag,
    release notes rewritten in every App Store and Google Play language, something the apps ship changed, the release
    queues idle, nothing in review, pending or rejected on App Store Connect for Apple, closed testing not behind
    internal testing for Google Play. So when landing work meant for the stores, bump the version and rewrite the
    release notes in every language in the same change; the workflow never does. A human tag push stays possible;
    nothing reaches Google Play production automatically yet; a push to `main` reaches TestFlight (never submitted for App Review: the TestFlight
    edition for internal testers and, built before it, the App Store edition for the external groups, submitted for
    Beta App Review only, every push), Google Play
    internal testing and Google Play closed testing, where Google reviews the release: closed testing receives the
    latest internal build once any review in progress is over, and a push never cancels or restarts a review
    (`.github/workflows/deliver.yml`, `release-android.yml`, `play-catchup.yml`). The user has given a standing go for
    that: once the short checks are green, push to `main` without asking first; the testers' builds leave after them.
    The long tests (macOS CI, Android app tests) run at night (`docs/CI.md`): nothing is submitted for App Review or
    published on Google Play production without a green night (or manual) run of them on that commit.
  - No private API (no class, selector or symbol looked up by name, no method swapping), no code downloaded and run.
  - The Mac sandbox has only the entitlements a feature needs; never `com.apple.security.network.server` (App Review
    refused 1.1.0 for it: a player listens to no one), so the MKV remux and its 127.0.0.1 server are iPhone and iPad only.
  - Money inside the apps only through Apple's In-App Purchase (Google Play Billing on Android), tips included; no
    link or button in the apps to another way to pay or donate. Donation links (the Stripe support pages of
    `funding/stripe.json`) live only on the website's home page and in the README, never on a page the apps open
    (support, privacy), and there is no `.github/FUNDING.yml`: its Sponsor button shows on the GitHub issue form the
    apps open.
  - The listing matches the build: no feature it lacks, no other platform named, nothing "coming soon", no test or
    placeholder words, this year's copyright. The review notes explain every permission, background mode and
    entitlement, and give a way to test without a subscription: a test playlist hosted on our own site
    (`site/review`) with only content we have the rights to (CC-licensed or our own). The apps ship no demo and no
    third-party channel.
  - Never "beta" in the App Store build, its listing or the site pages the listing opens (2.2: betas belong on
    TestFlight). No beta badge in any edition.
  - `.github/scripts/check_app_store.py` (CI, security and release workflows) checks this: extend it with each
    new permission, entitlement or edition difference.
- **Keys and logins are checked when saved**: every API key, token, login or other setting that depends on an
  outside service is checked with that service as soon as the user saves it, with a clear message (accepted,
  refused, service unreachable); a refused one is not saved. Fields and help texts use the service's own words for
  its credentials (e.g. TMDb's "API Read Access Token"), with a short explanation of where to find them.
- **Every bug fix comes with its regression test**: a test that fails on the code before the fix and passes after
  it, on each platform the bug touches (Android and Apple when the logic is shared), in the same change. A behaviour
  that must hold on every screen (hidden categories, display titles, D-pad paths) gets one test that lists every
  surface, so a new screen is added to it.
- **Responsiveness comes first**: an IPTV player must feel instant, above all on Android TV. Launch shows Home at
  the top (hero focused) from cached content at once; refreshes (playlist, catalogue, guide, metadata) run in the
  background and never block or move the screen, with a small progress indicator on the Home header while they run;
  no heavy work on the main thread; zapping, opening a page and starting playback stay fast. Every change that can
  touch startup, scrolling, focus or playback time is measured (timing tests, call counters, baseline profile) and a
  slowdown is treated as a bug, with its regression test.
- **Guard**: `.claude/hooks/leak_guard.py` is wired as a `PreToolUse` hook (`.claude/settings.json`): it
  blocks writing secrets or personal data into the repository and checks the changes before `git commit` /
  `git push`. CI runs it again on the whole history with gitleaks. For a false positive, add a regex to
  `.leakguard-allow` instead of bypassing the hook.
  The guard also checks the `git config user.email` address (`noreply` addresses only). It does not read the
  content of binary files: before adding an image, check that it holds no personal metadata (GPS position,
  device name).
- **Agents and requests sized to the task**: before spawning an agent, decide whether it needs the strongest model
  or whether a smaller one does the job. Searches, file sweeps, log reading, status checks and mechanical edits go
  to a Sonnet (or Haiku) agent; design, debugging, review of a change and anything where a wrong answer costs a
  release keep the strongest model. Do a single lookup directly rather than through an agent, send independent
  requests together, and read only the part of a file or log that is needed.
