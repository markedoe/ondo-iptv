# Ondo repository rules

This repository is **public**. Nothing private may enter it: not in files, not in commit messages, not in
screenshots.

- **Secrets**: App Store Connect keys (`.p8`), Key ID, Issuer ID, Team ID, the Android keystore (`.jks`) and
  its passwords, the Google Play service account JSON, tokens… go only into GitHub secrets
  (`Settings → Secrets and variables → Actions`, see `docs/RELEASE.md` and `docs/RELEASE-ANDROID.md`).
  Never write them in a repository file, a commit, a log or a CI artifact.
- **Privacy**: no personal email address (only `noreply` addresses), no real name, no personal folder path,
  no local network IP address, no real IPTV subscription data (panel URL, credentials). Tests and the demo
  use `example.com` and public streams.
- **Neutrality**: do not name other IPTV applications; Ondo is a player, it provides no content; never use
  "free" / "gratuit" to describe IPTV.
- **Always bilingual, English and French**: everything written for people exists in both languages and both
  are updated together: documentation in English with a French version next to it (`README.fr.md`,
  `docs/*.fr.md`), store listings (`en-US` and `fr-FR`), the website, release notes. Code, comments and
  commit messages stay in English.
- **The apps speak seven languages**: English, French, Spanish, Portuguese (Brazilian wording), Chinese
  (Simplified), Arabic (right to left) and Hindi. Every text of a new feature is translated into all of them
  in the same change: Apple `App/Resources/Localizable.xcstrings` (`fr`, `es`, `pt`, `zh-Hans`, `ar`, `hi`)
  and Android `res/values-{fr,es,pt,zh-rCN,ar,hi}/strings.xml`. `check_translations.py` (CI) fails on a
  missing Apple translation and Android lint on a missing Android one.
- **App Store: never get banned by Apple.** Apple can remove a developer from its program for hidden or dormant
  features (App Review Guidelines 2.3.1) and for getting around its rules, so:
  - The build submitted is the build App Review sees. A feature the App Store edition does not have is left out of
    its build (`#if TESTFLIGHT_DOWNLOADS || DEBUG`), never switched off at run time: no unlock code, no remote
    switch, no setting, date or launch argument that turns something on, no constant `false` feature left in.
  - The TestFlight edition (downloads) goes to internal testers only and is never submitted; a tag or
    `edition: app-store` builds the App Store edition. Nothing is submitted for review without the user's go.
  - No private API (no class, selector or symbol looked up by name, no method swapping), no code downloaded and run.
  - Money inside the apps only through Apple's In-App Purchase (Google Play Billing on Android), tips included; no
    link or button in the apps to another way to pay or donate. Donation links (GitHub Sponsors) live only on the
    website's home page and in the README, never on a page the apps open (support, privacy), and there is no
    `.github/FUNDING.yml`: its Sponsor button shows on the GitHub issue form the apps open.
  - The listing matches the build: no feature it lacks, no other platform named, nothing "coming soon", no test or
    placeholder words, this year's copyright. The review notes explain every permission, background mode and
    entitlement, and give a way to test without a subscription (the demo).
  - `.github/scripts/check_app_store.py` (CI, security and release workflows) checks this: extend it with each
    new permission, entitlement or edition difference.
- **Keys and logins are checked when saved**: every API key, token, login or other setting that depends on an
  outside service is checked with that service as soon as the user saves it, with a clear message (accepted,
  refused, service unreachable); a refused one is not saved. Fields and help texts use the service's own words for
  its credentials (e.g. TMDb's "API Read Access Token"), with a short explanation of where to find them.
- **Guard**: `.claude/hooks/leak_guard.py` is wired as a `PreToolUse` hook (`.claude/settings.json`): it
  blocks writing secrets or personal data into the repository and checks the changes before `git commit` /
  `git push`. CI runs it again on the whole history with gitleaks. For a false positive, add a regex to
  `.leakguard-allow` instead of bypassing the hook.
  The guard also checks the `git config user.email` address (`noreply` addresses only). It does not read the
  content of binary files: before adding an image, check that it holds no personal metadata (GPS position,
  device name).
