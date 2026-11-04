# Self-hosted Linux runners

*[Version française](RUNNERS.fr.md)*

The Linux test jobs can run on our own Linux machine (a VPS) instead of GitHub's. Nothing changes until the
`LINUX_RUNNER` repository variable is set: unset, every job runs on GitHub's machines, exactly as before.

Three rules, checked in CI by `.github/scripts/check_runners.py` (ci.yml and security.yml):

- **Only our own launches.** A job reaches the VPS only for a push to our branches, a run by hand
  (`workflow_dispatch`) or a schedule. A pull request (`pull_request`, `pull_request_target`), from a fork or not,
  always runs on GitHub's machines, whatever the variable says.
- **No publishing secret on the VPS.** A job that reads any secret other than the run's own `GITHUB_TOKEN`, passes
  `secrets: inherit` or uses an `environment:` stays on GitHub's machines.
- **One way only.** A job's `runs-on` is either a GitHub label (`ubuntu-…`, `macos-…`, `windows-…`) or exactly
  this expression, copied byte for byte:

  ```yaml
  runs-on: ${{ fromJSON((github.event_name == 'push' || github.event_name == 'workflow_dispatch' || github.event_name == 'schedule') && vars.LINUX_RUNNER || '"ubuntu-24.04"') }}
  ```

  For any other event the condition is false and the job gets `"ubuntu-24.04"`; for our own launches it gets the
  variable, or `"ubuntu-24.04"` while the variable is empty. Any other expression, variable, input, matrix value,
  runner group or `self-hosted` label in `runs-on` fails the check.

## What can run on the VPS

| Workflow | Job | Notes |
| --- | --- | --- |
| android.yml | `core` (Kotlin core tests) | Java from `setup-java`; needs `python3` |
| android.yml | `app` (lint, unit and Compose tests, Roborazzi screenshots, bundle) | needs the Android SDK; see [Screenshots](#roborazzi-screenshots) |
| android.yml | `listing` (Google Play text lengths) | `python3` only |
| ci.yml | `core-linux` (SwiftLint, configuration checks, IPTVCore tests) | runs in the `swift:6.0-noble` container: needs Docker |
| security.yml | `leaks` (leak guard, gitleaks), `neutrality` | `python3`, `git`, `curl`, `tar` |
| mutation.yml | `pitest` (weekly) | Java from `setup-java` |

Every other job stays on GitHub's machines:

| Job | Why |
| --- | --- |
| release.yml, release-android.yml, deliver.yml, promote-android.yml, play-catchup.yml, play-status.yml, app-store-assets.yml, funding-pages.yml | publishing secrets (App Store Connect key, Android keystore, Google Play service account, Stripe key) |
| every macOS job (ci.yml `apple`, `release-build`, `ui-tests`, appstore-screenshots.yml, release.yml) | macOS |
| android.yml `screenshots`, `references`; ci.yml `publish-screenshots`; appstore-screenshots.yml `publish`; awards.yml `refresh` | they hold a `contents: write` token that can push to the repository; short jobs with nothing to gain on the VPS |
| pages.yml `deploy` | the `github-pages` environment and an OIDC token (`id-token: write`) |
| tv-release-check.yml `tv` | the Android TV emulator needs `/dev/kvm` (nested virtualisation, which most VPS do not offer) and `sudo` for its udev rule; the runner user has no `sudo` |
| playback-lab.yml `check` | installs libVLC and ffmpeg with `sudo apt-get` |

A new Linux job that should use the VPS takes the expression above; the check refuses it if it reads a secret.

## Switching

- **To the VPS:** Settings → Secrets and variables → Actions → **Variables** tab → *New repository variable*:
  name `LINUX_RUNNER`, value the runners' labels as a JSON array, e.g. `["self-hosted","linux","x64","ondo"]`
  (or a JSON string for one label: `"ondo"`). After the move to an organization the variable can also be set for
  the organization; the repository's own value wins.
- **Back to GitHub:** delete the variable (or empty it). The next runs take `ubuntu-24.04`.
- The value must be valid JSON: a bare `ondo` makes every workflow fail before its first job.
- Jobs wait in the queue while no runner with those labels is online; GitHub cancels a job that waited 24 hours.
  If the VPS is down, delete the variable.

## What the VPS needs

GitHub's `ubuntu-24.04` image comes with many tools; the moved jobs install only Java (`setup-java`), Gradle
(`setup-gradle`) and, inside their container, the Swift toolchain and `python3`. The machine must have:

- **Ubuntu 24.04, x86_64** (Robolectric's native graphics ship Linux x86_64 libraries built against glibc), at least
  4 vCPU, 16 GB of memory and 60 GB of disk, like GitHub's machine.
- **The runner's own dependencies** (`./bin/installdependencies.sh` in the runner folder), and `git` (2.18 or later,
  so `actions/checkout` clones with git), `curl`, `ca-certificates`, `tar`, `unzip`, `python3` (3.10 or later).
- **Docker** for `container:` jobs (ci.yml `core-linux` runs in `swift:6.0-noble`). Prefer **rootless Docker** for
  the runner user: membership of the `docker` group is root on the machine. Docker pulls `swift:6.0-noble` from
  Docker Hub.
- **The Android SDK** for android.yml `app`: the command-line tools in `ANDROID_HOME`, the licences accepted
  (`sdkmanager --licenses`), the platform `android-37` (`compileSdk`, `android/gradle/libs.versions.toml`) and the
  build tools the Android Gradle plugin asks for, installed beforehand or left for Gradle to download into a
  writable SDK folder.
- **The environment of GitHub's machine**, in the runner's `.env` file (read at each start): `ANDROID_HOME=…`,
  `LANG=C.UTF-8`, `TZ=UTC` (some tests format dates and numbers).
- **No `sudo`** for the runner user: no moved job uses it.

### Roborazzi screenshots

android.yml `app` compares the screenshots with the committed references (at most 0.1 % of the pixels may differ,
`screenshots/Baselines.kt`). They are drawn by Robolectric's native graphics (`@GraphicsMode(NATIVE)`,
`robolectric.pixelCopyRenderMode=hardware`): the fonts come from Robolectric's Android jar, not from the machine,
but the native drawing libraries run on the machine's CPU and glibc, and anti-aliasing can differ by a few pixels.
Before switching for good:

1. Set `LINUX_RUNNER`, run *Android* by hand on a working branch (`record_screenshots` off) and read the result:
   passed, the references hold on the VPS too.
2. If screenshots differ, open the **android-screenshot-diffs** artifact. Differences only at glyph edges: run
   *Android* by hand with `record_screenshots` on that branch (the tests run on the VPS; the commit is made by the
   `references` job, which stays on GitHub's machines), then run it once more without recording on the VPS **and**
   once with the variable deleted, on GitHub's machines: both must pass, so that switching back stays possible.
   Larger differences: keep the job on GitHub and look for the cause first.

## Hardening

The repository is public: anyone can open a pull request, and a self-hosted runner runs whatever code a job
checks out. The expression above keeps every pull request off the VPS; these settings make sure of the rest.

- **Ephemeral runners: one job per runner.** Each runner registers for a single job, runs it and is removed;
  its folder is wiped before the next one. Nothing a job leaves behind (files, processes, caches, credentials) is
  seen by the next job. Use just-in-time runners (below) or `config.sh --ephemeral`.
- **A runner group for this repository only.** Organization settings → Actions → Runner groups → *New runner
  group* (e.g. `ondo`): *Repository access: Selected repositories* → `ondo-iptv` only; tick **Allow public
  repositories** (off by default, and needed since the repository is public). *Workflow access* can be limited to
  selected workflows.
- **Approval for pull requests from outside.** Settings → Actions → General → *Fork pull request workflows from
  outside collaborators* → **Require approval for all outside collaborators** (in the organization's settings
  too). Pull requests already run on GitHub's machines; this also stops a stranger's workflow from running at all
  before someone reads it.
- **Read-only token by default.** Every workflow starts with `permissions: contents: read`; a job that writes asks
  for it alone, and those jobs stay on GitHub. Settings → Actions → General → *Workflow permissions* → **Read
  repository contents and packages permissions**, and *Allow GitHub Actions to create and approve pull requests* off.
- **No long-lived token reachable by jobs.** No publishing secret ever reaches the VPS (checked in CI). Registering
  a new runner for every job needs one credential that can create runners: use a **GitHub App** of the organization
  with the single permission *Self-hosted runners: Read and write* (organization), which cannot read code, secrets
  or settings. Its private key is readable by root only and used by the supervisor below, never by the runner
  user; each job gets a single-use runner configuration, not the key. Do not use a personal access token.
- **A machine for this alone**, kept up to date (unattended upgrades), SSH by key only, nothing else installed or
  stored on it.
- **Network.** Nothing listens but SSH (the runner only opens outbound connections). Firewall the egress if
  possible: allow DNS and outbound HTTPS (and HTTP for Ubuntu's packages inside containers); refuse the cloud
  provider's metadata address, the private address ranges (RFC 1918) of the provider's network, SMTP and outbound
  SSH. For a strict list of domains, put an HTTPS proxy with an allowlist in front (`HTTPS_PROXY` in `.env`): GitHub
  (`github.com`, `api.github.com`, `*.actions.githubusercontent.com`, `objects.githubusercontent.com`,
  `codeload.github.com`, `ghcr.io`), Docker Hub, Maven Central, Google's Maven (`dl.google.com`,
  `maven.google.com`), the Gradle plugin portal and distributions (`plugins.gradle.org`, `services.gradle.org`),
  Ubuntu's archive and Swift packages' hosts.

### Just-in-time runners with systemd

A root service asks GitHub for a single-use runner configuration, runs one job as the unprivileged `runner` user in
a fresh folder, and starts again. Instances `ondo-runner@1`, `ondo-runner@2`… run several jobs at once.

`/usr/local/sbin/ondo-runner` (owned by root, mode 700):

```sh
#!/bin/sh
# One job: a single-use runner in a fresh folder, then exit (systemd starts the next one).
set -eu
ORG="<organization>"
GROUP_ID="<runner group id>"            # GET /orgs/<organization>/actions/runner-groups
APP_ID="<app id>"; INSTALLATION_ID="<installation id>"
KEY=/etc/ondo-runner/app.pem            # root only, mode 600
DIR=/home/runner/job-$1

b64() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
now=$(date +%s)
header=$(printf '{"alg":"RS256","typ":"JWT"}' | b64)
claims=$(printf '{"iat":%s,"exp":%s,"iss":"%s"}' $((now - 60)) $((now + 540)) "$APP_ID" | b64)
jwt="$header.$claims.$(printf '%s' "$header.$claims" | openssl dgst -sha256 -sign "$KEY" | b64)"
token=$(curl -fsS -X POST -H "Authorization: Bearer $jwt" -H "Accept: application/vnd.github+json" \
  "https://api.github.com/app/installations/$INSTALLATION_ID/access_tokens" | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')
jit=$(curl -fsS -X POST -H "Authorization: Bearer $token" -H "Accept: application/vnd.github+json" \
  "https://api.github.com/orgs/$ORG/actions/runners/generate-jitconfig" \
  -d "{\"name\":\"ondo-$(hostname)-$1-$now\",\"runner_group_id\":$GROUP_ID,\"labels\":[\"self-hosted\",\"linux\",\"x64\",\"ondo\"]}" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["encoded_jit_config"])')
unset jwt token

rm -rf "$DIR"
install -d -o runner -g runner "$DIR"
tar -xzf /opt/actions-runner.tar.gz -C "$DIR"   # the runner release, checked against its published SHA-256
cp /etc/ondo-runner/env "$DIR/.env"
chown -R runner:runner "$DIR"
cd "$DIR"
runuser -u runner -- ./run.sh --jitconfig "$jit" || true
cd / && rm -rf "$DIR"
```

`/etc/systemd/system/ondo-runner@.service`:

```ini
[Unit]
Description=Ondo ephemeral GitHub Actions runner %i
After=network-online.target docker.service
Wants=network-online.target

[Service]
ExecStart=/usr/local/sbin/ondo-runner %i
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

Then `systemctl enable --now ondo-runner@1`. With rootless Docker, the runner user's Docker must be running
(`loginctl enable-linger runner`) and `DOCKER_HOST` set in `/etc/ondo-runner/env`.

### Registering by hand (a first test)

Organization settings → Actions → Runners → *New runner* → *New self-hosted runner* → Linux, x64: the page gives
the download commands and a registration token valid one hour. As the `runner` user:

```sh
./config.sh --url https://github.com/<organization> --token <token from that page> \
  --runnergroup ondo --labels ondo --ephemeral --unattended
./run.sh
```

The runner takes one job and unregisters. The labels `self-hosted`, `Linux` and `X64` are added on their own
(labels are matched without regard to case). Until the move, a runner can be registered on the repository the same
way (repository Settings → Actions → Runners), without runner groups.

## Moving the repository to an organization

To do after the move (nothing changed yet). GitHub redirects `github.com/markedoe/ondo-iptv/…`, its git URLs and
`raw.githubusercontent.com/markedoe/ondo-iptv/…` to the new owner, **as long as no new repository takes the old
name**: never create `markedoe/ondo-iptv` again, the apps already shipped rely on these redirects. GitHub Pages is
**not** redirected.

**Pages: `https://markedoe.github.io/ondo-iptv/` becomes `https://<organization>.github.io/ondo-iptv/`.**

- Shipped apps open the old address (support and privacy pages): keep it alive with a user site
  `markedoe/markedoe.github.io` holding `ondo-iptv/` redirect pages, or move both to a custom domain.
- Update: `.github/about.json` (homepage); `fastlane/metadata/*/support_url.txt`, `marketing_url.txt`,
  `privacy_url.txt` (every locale); `fastlane/metadata/review_information/notes.txt` (the review playlist);
  `site/review/ondo-review.m3u` and `site/review/README.md` / `README.fr.md`; `funding/stripe.json` (image);
  `App/Features/Help/HelpView.swift` (`supportURL`); `android/app/src/main/kotlin/com/markedoe/ondo/ui/OndoApp.kt`
  (`PRIVACY_URL`, `SUPPORT_URL`); `tools/awards/generate_awards.py` (user agent); `docs/RELEASE.md`,
  `docs/RELEASE.fr.md`, `docs/RELEASE-ANDROID.md`, `docs/RELEASE-ANDROID.fr.md`.
- release.yml, release-android.yml and play-catchup.yml check the review playlist at
  `https://$OWNER.github.io/ondo-iptv/…`, with `OWNER` taken from `GITHUB_REPOSITORY`: they follow the move on their
  own, so the site must be published under the organization (and the review notes updated) before the next release.

**`markedoe/ondo-iptv` written out** (redirected, to update in the same change):

- README.md, README.fr.md: badges, issues link, screenshot images (`raw.githubusercontent.com`).
- `App/Model/AppModel+Awards.swift`, `android/app/src/main/kotlin/com/markedoe/ondo/data/AwardsDownload.kt`:
  the `awards-data` download.
- `App/Player/ProblemReportView.swift`, `android/core/src/main/kotlin/com/markedoe/ondo/core/playback/ProblemReport.kt`
  and its test `ProblemReportTest.kt`: the issue form.
- `site/privacy.html`, `site/support.html`, `site/review/README.md`, `site/review/README.fr.md`.
- `docs/RELEASE.md`, `docs/RELEASE.fr.md` (the repository's name).
- `.github/scripts/tests/test_leak_guard.py`: an example URL only; it can stay.

**Workflows and scripts:** none writes the repository's name; they use `GITHUB_REPOSITORY` / `github.repository`
(pushes of the screenshot and awards branches, `gh run list --repo`, `gh workflow run --repo`). Nothing to change.

**Also:**

- Install the Claude GitHub App on the organization and give it `ondo-iptv`; update the repositories the Claude
  sessions are allowed to use.
- Check that the repository's secrets, variables, environments (`github-pages`), Pages settings and rules are there
  after the transfer; set the Actions settings of [Hardening](#hardening) on the organization too.
- `fastlane/metadata/copyright.txt` and LICENSE name the owner: change them only if the owner changes.
- Never change the app identifiers `com.markedoe.ondo` (bundle ID, Android package, in-app purchase IDs, Kotlin
  packages): they are the apps' identity in the stores.
