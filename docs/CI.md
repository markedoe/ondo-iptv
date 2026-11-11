# Continuous integration

*[Version française](CI.fr.md)*

How the GitHub Actions workflows are organized, what decides which jobs run, what they cost and what is left to do.
Publishing is described in [RELEASE.md](RELEASE.md) (Apple) and [RELEASE-ANDROID.md](RELEASE-ANDROID.md) (Google Play).

## Short checks on every push, long tests at night

Each of *CI* and *Android* has **short jobs**, which run in every run, and **long jobs**, which run at night and by hand
only:

| | Short: every push to `main` and pull request (and every other run) | Long: at night on `main`, and by hand |
|---|---|---|
| *CI* | *Lint + IPTVCore tests (Linux)*: SwiftLint, the configuration checks and their tests, the Swift core tests on Linux (about 2 min) | *Build iOS + macOS (Debug, App Store edition)* and *App tests — unit + UI (iPhone simulator)*, both on macOS (about 20 min of wall clock, 38 job-minutes) |
| *Android* | *Core (Kotlin) tests* (about 2 min), *App — lint, debug APK, release bundle* (lint, the debug APK and the release bundle shrunk by R8: about 6 min), *Google Play listing limits* | *App — unit, Compose and screenshot tests*: the app's unit and Compose tests with Robolectric and the Roborazzi screenshots compared with the references (about 14 min) |

(Durations: the measurements below, 4 November 2026.) *TV release check* (the release build on an Android TV
emulator) stays a run by hand, outside the night.

- **The testers' builds leave after the short jobs.** A push to `main` runs the short jobs only; once that run is green,
  *Deliver* sends the push to TestFlight (internal and external testers) and to Google Play internal then closed
  testing, once per push. The long jobs do not hold it back.
- **The night** (`schedule`, 01:47 UTC: 02:47 in Paris in winter) runs the long jobs on `main`'s last commit, unless that
  very commit already passed the same workflow with its long jobs run (an earlier night or a run by hand): then *What to
  run* skips them in a few seconds. A night that failed is not a pass: the next night runs
  again. The night publishes the `ci-screenshots` and `android-screenshots` branches (also a run by hand on `main`).
- **By hand** (*Run workflow*, or `gh workflow run ci.yml --ref <branch or tag>`): the full run, short and long jobs (on a
  working branch, through the path filter below).
- **Production needs the long jobs on its commit.** A submission for App Review (a `v*` tag, *Release* target `release`
  with `submit`, target `submit`) and Google Play production (*Release Android* track `production`) refuse to go on
  unless a run of the platform's workflow passed **on that very commit** with its long jobs run: the night's, or a run
  by hand (`ci_scope.py production`). Otherwise the run stops at once with an error that says to wait for the night or
  start the workflow by hand on the commit, then run the release again. When the runs cannot be read, it refuses too.
  For a tag pushed during the day: `gh workflow run ci.yml --ref v1.2.0` (and `android.yml` for production), then
  *Re-run all jobs* on the tag's release once it is green. Target `submit`, which submits builds already uploaded by
  number, runs only from a version tag, with the build numbers of that tag's own release run (App Store Connect does not
  say which commit a build came from). **Only refs created after this change carry these gates**: a run by hand uses
  the workflow file of the ref it starts from, so an older tag or branch has none; submit and publish production only
  from a tag cut after this change.
- The two kinds of runs never cancel each other: a push's run and a night's or by-hand run are in different concurrency
  groups (`…-short`, `…-long`), and *Deliver* keeps one queue per source and event.

## The rule: when unsure, run everything

A job is skipped only when it is certain that it would test nothing new. Any doubt runs it: an unknown path, a
failed lookup, a branch with no difference from `main`, no `main` to compare with. The leak scan, the neutrality and
App Store checks (`security.yml`) run on every push to `main` and every pull request. The Linux job of CI (`core-linux`:
SwiftLint, `check_app_store.py` and the other configuration checks, the Swift core tests) runs in every *CI* run, never
skipped by *What to run*; but a push or a pull request that *CI*'s path filter leaves out starts no *CI* run at all, so
`core-linux` does not run for it (`security.yml` still runs `check_app_store.py`).

## Workflows

| Workflow | When | Jobs (runner) | What it checks or does |
|---|---|---|---|
| *CI* (`ci.yml`) | push to `main` and pull requests through its path filter (short jobs); every night on `main` and by hand (long jobs too) | *What to run* (Linux), *Lint + IPTVCore tests* (Linux), *Build iOS + macOS (Debug, App Store edition)* (macOS, long), *App tests — unit + UI* (macOS, long), *Publish screenshots* (Linux, the night and runs by hand on `main`) | SwiftLint, configuration checks, Swift core tests on Linux and (long) macOS, the macOS debug build and its launch, the Release builds of the App Store edition (iOS and macOS) and, except at night, of the TestFlight edition (iOS), the app's unit and UI tests on an iPhone simulator |
| *Android* (`android.yml`) | push to `main` and pull requests through its path filter (short jobs); every night on `main` and by hand, *record_screenshots* (long job too) | *What to run*, *Core (Kotlin) tests*, *App — lint, debug APK, release bundle*, *Google Play listing limits*, *App — unit, Compose and screenshot tests* (long), publishing jobs (all Linux) | Kotlin core tests, lint, debug APK and release bundle, listing lengths; (long) Robolectric and Compose tests, Roborazzi screenshots compared with the references |
| *Security* (`security.yml`) | every push to `main`, every pull request, by hand | two Linux jobs | leak guard and gitleaks on every commit of every branch; neutrality, App Store edition and review playlist checks |
| *Deliver* (`deliver.yml`) | after a green *CI* or *Android* run of a push to `main` (short jobs) | Linux | starts *Release* (TestFlight: the TestFlight edition for internal testers, the App Store edition for external testers) or *Release Android* (internal testing) when what the app ships changed |
| *Ship* (`ship.yml`) | every day at 13:17 and 19:17 UTC, after the night; by hand (dry run by default) | Linux, its own queue | when the gates hold (below), tags the commit the night tested with the next version and starts *Release* and *Release Android* from the tag, as a tag push would |
| *Release* (`release.yml`) | `v*` tag; by hand | *Long tests passed on this commit* (Linux: a submission for App Review needs *CI*'s long jobs passed on the commit), *App Store screenshots* (called, macOS), *TestFlight / App Store Connect* (macOS), *TestFlight edition again* (Linux), *External testers* (Linux: starts *TestFlight external*) | see RELEASE.md |
| *TestFlight external* (`testflight-external.yml`) | started by *Release* only, on `main` | Linux, its own queue | waits for App Store Connect's processing and sends the App Store edition builds to the external testers through Beta App Review |
| *TestFlight status* (`testflight-status.yml`) | by hand | Linux | read only: TestFlight groups, tester counts and iOS builds on Apple silicon Macs (on or off), whether a macOS version is on the Mac App Store, Test Information per language, Beta App Review contact (set or missing), latest builds with their edition, states and What to Test |
| *App Store screenshots* (`appstore-screenshots.yml`) | called by *Release*; by hand | one macOS job per device (iPhone, iPad, Mac), *Publish* (Linux) | captures the listing screenshots in English and French and publishes them on the `appstore-screenshots` branch; the iPhone job also captures the review screenshot of the tips (artifact `iap-review-screenshot`, kept off the branch) |
| *Release Android*, *Promote Android*, *Play catch-up*, *Google Play status* | tag, *Deliver*, hourly or by hand | Linux | see RELEASE-ANDROID.md |
| *Website* (`pages.yml`) | push to `main` touching `site/`; weekly; by hand | Linux | GitHub Pages |
| *Playback lab*, *TV release check*, *Mutation testing*, *Awards data*, *App Store header and search results*, *Support pages* | their own paths, schedules or by hand | Linux | see each workflow's header |

Every macOS job uses the same Xcode (`DEVELOPER_DIR`, raised in every macOS job and in RELEASE.md at once).

## Which jobs run

### Path filters

*CI* and *Android* filter pushes and pull requests with an ordered list of patterns, GitHub's syntax: `**` is any
path, `*` any name inside one folder, `!` excludes, and the last pattern that matches wins. The lists live in
`.github/scripts/ci_scope.py` (`PATHS`) and are copied byte for byte into the workflows; `test_ci_scope.py` fails when
they differ.

- *CI* skips: `android/**`, `fastlane/metadata-android/**`, `site/**`, `docs/**` and the Markdown files at the top of
  the repository.
- *Android* skips: `App/**`, `AppTests/**`, `UITests/**`, `MacUITests/**`, `Packages/**`, `project.yml`,
  `fastlane/metadata/**`, `site/**`, `docs/**` and the Markdown files at the top of the repository.
- Everything else runs both. Changes to workflows or scripts are not skipped. GitHub documents `**` as matching any
  character including `/` but says nothing of names that start with a dot, so both lists also include `.*` and `.*/**`
  explicitly (`.github/`, `.claude/`, `.githooks/`, `.swiftlint.yml`).

**Shared paths: they always run both platforms.** No list may exclude them:

- `fixtures/**` (`sync`, `film-versions`, `showcase`): read by both cores' tests and both apps' screenshots;
- `docs/SYNC*` and `docs/PROFILES*`: the specifications both cores follow;
- `.github/**` (workflows and scripts), `.claude/**`;
- `fastlane/Fastfile`, `fastlane/Appfile`, `fastlane/creative/**`, `Gemfile`, `Gemfile.lock`;
- `funding/**`, `tools/**`, `LICENSE`, and any folder not named in the lists above.

**When a new folder that both apps read appears, check that neither list excludes it** and add a path from it to
`test_shared_folders_always_run_both` in `test_ci_scope.py`.

### A run by hand on a working branch

GitHub applies no path filter to a run started by hand (`gh workflow run ci.yml --ref <branch>`). The first job,
*What to run*, applies the same list to every file the branch changed since its merge-base with `main` (`git diff
--no-renames`, so a file moved out of `App/` counts too); merges of `main` into the branch do not count. When no
changed file is in the list, the long jobs (macOS for *CI*, the app tests for *Android*) are skipped. A run that
records the Android reference screenshots always runs.

### The night, when `main` already passed

*What to run* looks for an earlier successful run of the same workflow (a night or a run by hand, or a push, whose long
jobs are always skipped and so never count; never a pull request, which tests a merge commit) on the same commit (not
another commit with the same tree: production needs a pass on its exact commit), whose long jobs all ran and passed
and, for *Android*, which did not record the reference screenshots. When there is one, `main` has not changed and the long jobs are skipped; otherwise, or when the runs cannot
be read, they run. (A push to `main` already tested on its branch used to skip its heavy jobs this way: a push no
longer runs them at all.)

### What a skipped job means

A job skipped by its `if:` shows as *skipped*; GitHub counts it as passing for required checks and the run concludes
*success*, so *Deliver* still starts after a push's run, whose long jobs are skipped: that is the rule (the testers'
builds wait for the short jobs). A night's run or a run by hand never reaches *Deliver* (it reacts to pushes to `main`
only). A skipped long job never proves a commit: *Release* skips its core tests for a TestFlight build only when a run
of *CI* on that commit or tree had its macOS jobs run and passed (`ci_scope.py passed ci`), which a push's run never
has, so a push's TestFlight build runs them on its macOS runner; and the production gates (`ci_scope.py production`)
count only a run whose long jobs passed on the very commit. If *What to run* itself fails, the long jobs are skipped and
the run is red, never green.

### Dry run

The only change on a working branch, and what a run by hand starts (`test_ci_scope.py` runs these cases):

| Change | *CI* macOS jobs | *Android* app tests (long job) |
|---|---|---|
| `android/…` only | skipped | run |
| `App/…` or `Packages/…` only | run | skipped |
| `fixtures/…` only | run | run |
| `docs/RELEASE.md` only | skipped | skipped |
| `.github/workflows/…` only | run | run |
| `docs/SYNC.md` only | run | run |

## Ship: the next version after a green night

The night's long tests are what production waits for, so the night also decides what goes to review: *Ship*
(`ship.yml`, `.github/scripts/ship.py`, tested by `test_ship.py`) takes the commit that the latest scheduled runs of
*CI* and *Android* both passed on, never a newer `main`, and checks it with `ci_scope.py production` as *Release*
does. It ships that commit as `vX.Y.Z` only when the apps' version there (equal in both) is above the last version
tag and that tag does not exist yet, the release notes changed in every App Store and Google Play language,
something the apps ship changed (*Deliver*'s paths), and no release queue is busy. Apple also needs App Store Connect
clear (nothing in review, waiting, approved and unreleased or rejected); Google Play goes without it. Any doubt (a
night still running, an unreadable answer) ships nothing at that time; the second daily run, then the next day, try again. Its schedule leaves
some hours after 01:47, since GitHub often starts the night's runs late. A run by hand is a dry run unless
`dry_run` is turned off, and only `main` ships. Details and recovery: [RELEASE.md](RELEASE.md#d-publish), step 2.
Android goes through internal testing with the listing and `KEEP_PLAY_REVIEW`, so a Google Play review in progress
is never cancelled, and waits while closed testing is behind internal testing. When a platform did not get a tagged
version, a later run starts its release on that tag (catch-up, no new tag): Apple only if no *Release* ran on the tag,
Google Play when it holds no bundle of the tag's run, once more at most; a failed release on the tag turns Ship red
for a human. By hand, `baseline_tag` and `baseline_sha` create the first version tag only.
Ship never raises the version or writes the notes: the owner does both when landing the work.

## App Store screenshots

*App Store screenshots* no longer runs on pushes: nothing reads its output between releases. *Release* calls it for the
runs that upload the listing (a tag, targets `release`, `all`, `metadata*`), and the upload waits for it. One job per
device builds once (`build-for-testing`) and captures English and French from that build (`test-without-building`):
three macOS jobs instead of six. When an iPhone or iPad capture fails, nothing is published; when only the Mac capture
fails, the Mac screenshots already on the branch are kept beside the new ones.

**The listing must match the build** (CLAUDE.md): when any capture fails, a tag and the targets `release` and `all` stop
at their first step (*App Store screenshots captured*), before anything is built, uploaded or submitted, with an error
that says to re-run the screenshots and then the release (*Re-run all jobs*). Only the `metadata*` targets, which
submit nothing, go on with the screenshots already on the `appstore-screenshots` branch (a warning says so). Run it by
hand to look at the screenshots before a release.

What is lost: a capture test broken at run time shows at release time instead of on the push (the test is compiled by
*CI* on every run, not run); the branch no longer shows the interface of every push (`ci-screenshots` shows `main`'s
after each night, from the app's UI tests).

## macOS runners

The account can run a limited number of macOS jobs at once (5 on GitHub's Free plan: check the account's plan). A *CI*
run with its long jobs takes two macOS jobs (three before this change), *App Store screenshots* three, *Release* one; a
push's *CI* run takes none. Hence the working rule: **at most two branches run *CI* by hand at a time**, which leaves one
macOS runner for *Release* or the screenshots. GitHub itself starts a job within seconds while a runner is free; the
long waits come from that rule.

## Measurements (4 November 2026, before this change)

The last 40 runs of each workflow:

| | *CI* | *Android* |
|---|---|---|
| Time covered | 17 h | 3 h |
| How started | 28 by hand, 9 pushes to `main`, 3 pull requests | 37 by hand, 3 pushes to `main` |
| Cancelled | 17 of 38 finished (45 %); 12 replaced by a newer run of the same branch | 5 |
| Wait for a runner | median 0 min, at most 21 min | 0 min |
| Green run, wall clock | median 20 min | median 19 min |
| Jobs, median | `apple` 5.1 min, `release-build` 14.2, `ui-tests` 18.9, `core-linux` 2.0 | `app` 18.4, `core` 2.1 |
| Slowest steps | `xcodebuild test` 14–20 min, picking and booting the simulator 2–6, each Release build 4–6 | Robolectric and Roborazzi tests 12.0, bundle 2.8, lint 1.9 |
| macOS job-minutes | 1,741, of which 653 (37 %) in cancelled runs | none |

*App Store screenshots*: 30 runs in 21 days, six macOS jobs (about 55 job-minutes) each; its own jobs waited up to
50 min for runners, and *CI* runs waited behind it. Of 8 pushes to `main` in the sample, 3 were commits that had already
passed on their branch. The Gradle caches hit (about 1.3 GB restored per run).

**To measure again** (the repository is public, the API needs no token for reading):

```bash
repo=markedoe/ondo-iptv
gh api "repos/$repo/actions/workflows/ci.yml/runs?per_page=40" \
  --jq '.workflow_runs[] | [.id, .event, .conclusion, .head_branch, .created_at, .updated_at] | @tsv'
gh api "repos/$repo/actions/runs/<run id>/jobs" \
  --jq '.jobs[] | [.name, .conclusion, .created_at, .started_at, .completed_at] | @tsv'
```

Queue time is `started_at − created_at` of a job, duration `completed_at − started_at`; each job's `steps` give the
same for its steps.

## What this change did, and why

| Change | Why | Expected saving |
|---|---|---|
| *What to run* applies the path filter to runs by hand on branches | most branch runs are started by hand, which ignores `paths` | about 43 macOS job-minutes per skipped run |
| *Android*'s filter is the inverse list (`fixtures/**`, scripts and workflows now run it) | a fixtures-only push skipped the Android tests that read them | none: a gap closed |
| A push to `main` skips its heavy jobs when its commit or tree already passed with them run | 3 of 8 pushes to `main` were tested twice | about 43 macOS job-minutes per such push |
| *App Store screenshots* at release time, one job per device | about 75 macOS job-minutes a day, and runners taken from *CI* | most of it; about 40 job-minutes per release |
| `apple` merged into the build job | a *CI* run takes two macOS runners instead of three | about 2 min of setup per run; two branches fit beside a release |
| Long jobs (*CI*'s macOS jobs, *Android*'s app tests) at night and by hand only; production gated on them | the testers' builds waited about 20 min for them on every push | about 38 macOS job-minutes per push; the deliveries leave after about 6 min |

## Not done yet, ready to pick up

| Proposal | Expected saving | Risk | How |
|---|---|---|---|
| Boot the simulator during the build (`ui-tests`) | 2–6 min of wall clock per run | low: `bootstatus` waits anyway | start `xcrun simctl boot` before the build, run `xcodebuild build-for-testing`, then `bootstatus -b` and `test-without-building` |
| Start a run by hand once per batch of commits, or use a pull request | most of the 37 % of macOS minutes spent in cancelled runs | none | push the commits, start *CI* once after the last one; or open a draft pull request: path filters and cancellation per branch apply by themselves (its runs do not count for the duplicate skip) |
| Split the Android app tests over 2–3 Linux jobs | wall clock 19 → about 10 min; Linux minutes are free here | a test class left out by the filters | split `:app:verifyRoborazziDebug` by test filters, lint and bundle in a job of their own; a check that the shards' test counts add up to the whole |
| Gradle `cache-encryption-key` | 10–20 s per Android run (configuration cache reused) | it is a repository secret: a job that reads it cannot run on the self-hosted runners (docs/RUNNERS.md on the runners branch) | `openssl rand -base64 16` into a secret, passed to `setup-gradle` as `cache-encryption-key` |

## Rejected

- **Caching DerivedData**: the checkout gives every file a new date, so Xcode builds again anyway; the Swift package
  checkouts are already cached (restored in 0.3–0.4 min).
- **Building the App Store edition only on `main` and tags**: it is the build App Review sees (CLAUDE.md). Since the long
  jobs moved to the night, a break only that edition shows reaches `main` and shows in the push's *Release* run (it
  builds the App Store edition for the external testers) and in the night's run, which then blocks the submission.
- **Changing the Gradle caches**: they already hit; the build cache serves what did not change.
- **Self-hosted Linux runners as a priority**: Linux minutes are free for a public repository and Linux is not the
  bottleneck; their gain is a warm Gradle cache. When they come, only our own launches use them and no publishing
  secret reaches them.
