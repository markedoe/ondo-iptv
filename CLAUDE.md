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
