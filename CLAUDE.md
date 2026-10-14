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
- **Documentation**: in English, with a French version next to it (`README.fr.md`, `docs/*.fr.md`); update
  both. App strings, store listings and the website keep their own French translations.
- **Guard**: `.claude/hooks/leak_guard.py` is wired as a `PreToolUse` hook (`.claude/settings.json`): it
  blocks writing secrets or personal data into the repository and checks the changes before `git commit` /
  `git push`. CI runs it again on the whole history with gitleaks. For a false positive, add a regex to
  `.leakguard-allow` instead of bypassing the hook.
  The guard also checks the `git config user.email` address (`noreply` addresses only). It does not read the
  content of binary files: before adding an image, check that it holds no personal metadata (GPS position,
  device name).
