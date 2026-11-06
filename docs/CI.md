# Continuous integration

*[Version française](CI.fr.md)*

How the GitHub Actions workflows are organized, what decides which jobs run, what they cost and what is left to do.
Publishing is described in [RELEASE.md](RELEASE.md) (Apple) and [RELEASE-ANDROID.md](RELEASE-ANDROID.md) (Google Play).

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
| *CI* (`ci.yml`) | push to `main` and pull requests through its path filter; by hand | *What to run* (Linux), *Lint + IPTVCore tests* (Linux), *Build iOS + macOS (Debug, App Store edition)* (macOS), *App tests — unit + UI* (macOS), *Publish screenshots* (Linux, pushes only) | SwiftLint, configuration checks, Swift core tests on Linux and macOS, the macOS debug build and its launch, the Release builds of the App Store edition (iOS and macOS) and, except on pushes, of the TestFlight edition (iOS), the app's unit and UI tests on an iPhone simulator |
| *Android* (`android.yml`) | push to `main` and pull requests through its path filter; by hand (*record_screenshots*) | *What to run*, *Core (Kotlin) tests*, *App — lint, tests, screenshots, bundle*, *Google Play listing limits*, publishing jobs (all Linux) | Kotlin core tests, Robolectric and Compose tests, Roborazzi screenshots compared with the references, lint, debug APK and release bundle, listing lengths |
| *Security* (`security.yml`) | every push to `main`, every pull request, by hand | two Linux jobs | leak guard and gitleaks on every commit of every branch; neutrality, App Store edition and review playlist checks |
| *Deliver* (`deliver.yml`) | after a green *CI* or *Android* run of a push to `main` | Linux | starts *Release* (TestFlight: the TestFlight edition for internal testers, the App Store edition for external testers) or *Release Android* (internal testing) when what the app ships changed |
| *Release* (`release.yml`) | `v*` tag; by hand | *App Store screenshots* (called, macOS), *TestFlight / App Store Connect* (macOS), *TestFlight edition again* (Linux), *External testers (Beta App Review)* (Linux: it waits for App Store Connect's processing without holding a Mac) | see RELEASE.md |
| *App Store screenshots* (`appstore-screenshots.yml`) | called by *Release*; by hand | one macOS job per device (iPhone, iPad, Mac), *Publish* (Linux) | captures the listing screenshots in English and French and publishes them on the `appstore-screenshots` branch |
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
changed file is in the list, the heavy jobs (macOS for *CI*, Kotlin and app for *Android*) are skipped. A run that
records the Android reference screenshots always runs.

### A push to `main` that already passed on its branch

A branch tested by hand and then pushed to `main` used to be tested twice. *What to run* now looks for an earlier
successful run of the same workflow (a push to `main` or a run by hand; never a pull request, which tests a merge commit)
on the same commit or on a commit with the same tree, whose heavy jobs all ran and passed and, for *Android*, which did
not record the reference screenshots. When there is one, the heavy jobs are skipped; otherwise, or when the runs cannot
be read, everything runs.

### What a skipped job means

A job skipped by its `if:` shows as *skipped*; GitHub counts it as passing for required checks and the run concludes
*success*, so *Deliver* still starts. That is right for a push to `main` whose tree already passed. A run by hand on a
branch never reaches *Deliver* (it reacts to pushes to `main` only). *Release* skips its core tests for a TestFlight
build only when a run of *CI* on that commit or tree had its macOS jobs run and passed (`ci_scope.py passed ci`): a run
that skipped them does not count. If *What to run* itself fails, the heavy jobs are skipped and the run is red, never
green.

### Dry run

The only change on a working branch, and what a run by hand starts (`test_ci_scope.py` runs these cases):

| Change | *CI* macOS jobs | *Android* Kotlin and app jobs |
|---|---|---|
| `android/…` only | skipped | run |
| `App/…` or `Packages/…` only | run | skipped |
| `fixtures/…` only | run | run |
| `docs/RELEASE.md` only | skipped | skipped |
| `.github/workflows/…` only | run | run |
| `docs/SYNC.md` only | run | run |

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
*CI* on every run, not run); the branch no longer shows the interface of every push (`ci-screenshots` does, from the
app's UI tests).

## macOS runners

The account can run a limited number of macOS jobs at once (5 on GitHub's Free plan: check the account's plan). A *CI*
run takes two macOS jobs (three before this change), *App Store screenshots* three, *Release* one. Hence the working
rule: **at most two branches run *CI* at a time**, which leaves one macOS runner for *Release* or the screenshots.
GitHub itself starts a job within seconds while a runner is free; the long waits come from that rule.

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
- **Building the App Store edition only on `main` and tags**: a break only that edition shows would reach `main` and
  stop the TestFlight deliveries; it is the build App Review sees (CLAUDE.md).
- **Changing the Gradle caches**: they already hit; the build cache serves what did not change.
- **Self-hosted Linux runners as a priority**: Linux minutes are free for a public repository and Linux is not the
  bottleneck; their gain is a warm Gradle cache. When they come, only our own launches use them and no publishing
  secret reaches them.
