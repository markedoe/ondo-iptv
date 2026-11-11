#!/usr/bin/env python3
"""Ship: the next version goes to review by itself after a green night (ship.yml, the owner's standing go, CLAUDE.md).

    ship.py     decides, prints why, and (DRY_RUN=false on main only) creates the tag vX.Y.Z on the night's commit and
                starts, from the tag, Release (target release, submit: App Review) and Release Android (internal testing
                with the listing, then closed testing for review, never cancelling a review in progress: below).
                With BASELINE_TAG and BASELINE_SHA (a run by hand): only creates that tag on that commit, the first
                version tag Ship compares with, after checking it; starts nothing.

The commit S shipped is the one the night tested, never main's newer commits. It ships only when every gate holds:

  a. the latest scheduled runs of ci.yml and android.yml on main both passed, on the same commit S, S is on main, and
     `ci_scope.py production ci|android S` both accept S (the long tests ran and passed on exactly S);
  b. MARKETING_VERSION (project.yml) and versionName (android/app/build.gradle.kts) at S are equal and strictly greater
     than the highest vX.Y.Z tag; no v tag at all, or one that is not a version, stops here (the first tag is a
     human's);
  c. every App Store locale's release_notes.txt and every Google Play locale's changelogs/default.txt changed since
     that tag and is not empty at S;
  d. something the apps ship changed since that tag (deliver.yml's own paths, DELIVER_PATHS), the version line of
     project.yml and build.gradle.kts left out;
  f. the tag vX.Y.Z does not exist yet;
  -  no run waits or runs in the release queues (release-apple, release-android): GitHub keeps one waiting run per
     concurrency group and a new one replaces it, so nothing waiting is ever pushed out and the runs started here
     start at once.

  e. Apple only: App Store Connect (read only) shows no version of iOS or macOS in review, waiting, approved but not
     released, rejected or in any state not known to be settled, no review submission under way, and no version equal
     to or above this one already past preparation. Any read error blocks Apple. Apple blocked, Android still goes when
     its gates hold.

Google Play: Release Android runs on internal testing with the listing, as a push's delivery does with KEEP_PLAY_REVIEW
(Fastfile): every commit asks ERROR_IF_IN_REVIEW, so a review in progress is never cancelled or restarted; its Closed
testing step then sends the build to closed testing for review, or Play catch-up does once the review is over. No API
shows a review in progress (play_status.py): the sign Ship reads, as play_catchup.py does, is closed testing behind
internal testing (a promotion waiting for a review to end); then, or when Google Play cannot be read, Android waits.

Catch-up, when the version at S is exactly the highest tag (tagged, but a platform did not get it): the tag is on S's
history and its commit carries that version, the tag passed gates c and d against the tag before it (a baseline tag,
with none before it, never does), the platform's long tests passed on the tag's commit and the queues are idle. Then,
on that existing tag, no new tag:
  - Apple: Release, only when no Release run ever ran on the tag (one running: wait; one failed: a human, the Ship run
    turns red; one passed: nothing), Apple's gates hold and App Store Connect has no version of that number past
    preparation (in review, approved, live, rejected or withdrawn: a human);
  - Android: Release Android (internal, listing), when Google Play holds no bundle of the version code a run on the tag
    built (1000 + its run number, release-android.yml), once more at most after a green run that delivered nothing
    (KEEP_PLAY_REVIEW swallows a refusal during a review), never after a failed run (a human, red), and only when
    Google Play shows no review waiting (above).

Never bumps a version, never commits, never touches Google Play production. Prints no secret.
"""
import importlib.util
import json
import os
import re
import subprocess
import sys
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent


def _load(name: str):
    # By path: the workflow runs this with python3 -I, which leaves the script's folder off sys.path.
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


ci_scope = _load("ci_scope")
asset_library = _load("asset_library")
play_catchup = _load("play_catchup")
play_status = play_catchup.play_status

# What the apps ship, as deliver.yml's step "Send the push's builds" gives it to git diff (tests left out). Copied byte
# for byte from deliver.yml: test_ship.py checks it, so the two never drift apart.
DELIVER_PATHS = {
    "apple": "App Packages :(exclude)Packages/*/Tests/* project.yml fastlane/Fastfile Gemfile Gemfile.lock",
    "android": "android :(exclude)android/*/src/test/*",
}
APPLE_METADATA = "fastlane/metadata"
PLAY_METADATA = "fastlane/metadata-android"
APPLE_NOTES = APPLE_METADATA + "/{locale}/release_notes.txt"
PLAY_NOTES = PLAY_METADATA + "/{locale}/changelogs/default.txt"
# Folders of fastlane/metadata that are not listing languages (check_app_store.py and the Fastfile skip it too).
NOT_A_LOCALE = {"review_information"}
PROJECT_YML = "project.yml"
GRADLE = "android/app/build.gradle.kts"
# The same expressions as release.yml's step "The tag is the version of the apps" (sed, first match).
APPLE_VERSION = re.compile(r'^ *MARKETING_VERSION: *"([^"]*)"', re.MULTILINE)
ANDROID_VERSION = re.compile(r'ANDROID_VERSION_NAME"\) \?: "([^"]*)"')
# The versions the Fastfile accepts in a tag (marketing_version): v1.2 or v1.2.3.
VERSION = re.compile(r"\d+(\.\d+){1,2}")

# The release queues (concurrency groups) and every workflow that enters them. A run waiting or running in one of
# them means no ship today: the runs started here would wait, and a later one could replace them.
QUEUES = {
    "apple": ["release.yml"],
    "android": ["release-android.yml", "promote-android.yml", "play-status.yml", "play-images.yml", "play-tips.yml",
                "play-catchup.yml"],
}
BUSY = {"queued", "pending", "waiting", "requested", "in_progress"}

# App Store version states known to be settled: nothing is with Apple, nothing waits on the owner. Any other state
# (in review, waiting, approved and not yet released, rejected, an unknown new state) blocks Apple.
SETTLED = {"READY_FOR_SALE", "READY_FOR_DISTRIBUTION", "PREPARE_FOR_SUBMISSION", "DEVELOPER_REJECTED",
           "REPLACED_WITH_NEW_VERSION", "REMOVED_FROM_SALE", "DEVELOPER_REMOVED_FROM_SALE"}
# A version the release lane can still fill: the one this ship's version may already have on App Store Connect.
OPEN = {"PREPARE_FOR_SUBMISSION", "DEVELOPER_REJECTED"}
APPLE_PLATFORMS = ("IOS", "MAC_OS")
# Review submissions under way (UNRESOLVED_ISSUES: Apple rejected it, a human answers).
SUBMISSION_UNDER_WAY = asset_library.SUBMISSION_IN_REVIEW

# What a tag push starts, started from the tag (a tag made with GITHUB_TOKEN fires no push event). Android goes through
# internal testing as a push's delivery does (KEEP_PLAY_REVIEW, release-android.yml), with the listing as a tag sends
# it: unlike a tag push's direct closed testing upload, it never cancels or restarts a Google Play review.
RELEASE_APPLE = ["-f", "target=release", "-f", "submit=true"]
RELEASE_ANDROID = ["-f", "track=internal", "-f", "status=completed", "-f", "listing=upload"]
# release-android.yml: ANDROID_VERSION_CODE=$((1000 + GITHUB_RUN_NUMBER)) (test_ship.py checks it).
VERSION_CODE_BASE = 1000
# Runs of Release Android on one tag that ended green without Google Play holding their bundle: after this many, a human.
ANDROID_ATTEMPTS = 2
RELEASE_WORKFLOWS = {"apple": "release.yml", "android": "release-android.yml"}


# --- Pure decisions -------------------------------------------------------------------------------------------------

def parse_version(text: str):
    """1.2 or 1.2.3 → (1, 2, 0) or (1, 2, 3); anything else → None."""
    text = (text or "").strip()
    if not VERSION.fullmatch(text):
        return None
    parts = [int(part) for part in text.split(".")]
    return tuple(parts + [0] * (3 - len(parts)))


def latest_tag(tags: list):
    """(the highest vX.Y.Z tag, None) or (None, why not)."""
    versions = [tag for tag in tags if tag.startswith("v")]
    if not versions:
        return None, ("no vX.Y.Z tag yet: create the first one on the last released commit (Ship by hand with "
                      "baseline_tag and baseline_sha, or a tag pushed by hand, docs/RELEASE.md); Ship compares with it")
    wrong = [tag for tag in versions if parse_version(tag[1:]) is None]
    if wrong:
        return None, f"tag(s) {', '.join(sorted(wrong))} start with v but are not a version: a human sorts them out"
    return max(versions, key=lambda tag: parse_version(tag[1:])), None


def apps_version(project_yml: str, gradle: str):
    """(the version both apps carry, None) or (None, why not)."""
    apple = APPLE_VERSION.search(project_yml or "")
    android = ANDROID_VERSION.search(gradle or "")
    apple, android = apple.group(1) if apple else None, android.group(1) if android else None
    if not apple or not android:
        return None, f"the version could not be read (project.yml {apple}, Android {android})"
    if apple != android:
        return None, f"MARKETING_VERSION {apple} (project.yml) and versionName {android} (Android) differ"
    if parse_version(apple) is None:
        return None, f"version {apple} is not X.Y or X.Y.Z"
    return apple, None


def newer(version: str, tag: str) -> bool:
    """Whether [version] comes strictly after the tag [tag] (vX.Y.Z), numerically: 2.0.10 > 2.0.9."""
    return parse_version(version) > parse_version(tag[1:])


def night_sha(ci_runs: list, android_runs: list):
    """(S, None): the commit of the latest scheduled run of ci.yml and of android.yml on main, both passed on it; or
    (None, why not). Each list is GitHub's, newest first."""
    heads = []
    for name, runs in (("ci.yml", ci_runs), ("android.yml", android_runs)):
        runs = [run for run in runs or [] if run.get("event") == "schedule" and run.get("head_branch") == "main"]
        if not runs:
            return None, f"no scheduled run of {name} on main"
        run = runs[0]
        if run.get("status") != "completed":
            return None, f"the night's {name} run {run.get('id')} is {run.get('status')}: the next Ship decides"
        if run.get("conclusion") != "success":
            return None, f"the night's {name} run {run.get('id')} ended {run.get('conclusion')}"
        if not run.get("head_sha"):
            return None, f"the night's {name} run {run.get('id')} names no commit"
        heads.append(run["head_sha"])
    if heads[0] != heads[1]:
        return None, f"the nights tested different commits (ci.yml {heads[0]}, android.yml {heads[1]})"
    return heads[0], None


def version_only(diff: str) -> bool:
    """Whether a `git diff -U0` of project.yml or build.gradle.kts changes the version line and nothing else: a version
    bump alone ships nothing new (gate b asks for it anyway)."""
    changes = [line for line in diff.splitlines()
               if line[:1] in "+-" and not line.startswith(("+++", "---"))]
    return bool(changes) and all(APPLE_VERSION.search(line[1:]) or ANDROID_VERSION.search(line[1:]) for line in changes)


def missing_notes(changed: list, apple_locales: list, play_locales: list, texts: dict) -> list:
    """The release notes files not rewritten since the last tag, or empty at S ([texts]: path → content at S, None
    when absent). No locale at all on one side counts as missing too."""
    missing = []
    for pattern, locales, folder in ((APPLE_NOTES, apple_locales, APPLE_METADATA), (PLAY_NOTES, play_locales, PLAY_METADATA)):
        if not locales:
            missing.append(f"{folder} (no language)")
        for locale in sorted(locales):
            path = pattern.format(locale=locale)
            if path not in changed or not (texts.get(path) or "").strip():
                missing.append(path)
    return missing


def apple_blockers(versions, submissions, version: str) -> list:
    """Why Apple cannot take [version] now, from App Store Connect's versions and review submissions (None: unread)."""
    if versions is None or submissions is None:
        return ["App Store Connect could not be read"]
    blockers = []
    wanted = parse_version(version)
    seen = set()
    for item in versions:
        attributes = item.get("attributes") or {}
        platform = attributes.get("platform")
        if platform not in APPLE_PLATFORMS:
            continue
        seen.add(platform)
        name = f"{platform} {attributes.get('versionString')}"
        states = {state for state in (attributes.get("appStoreState"), attributes.get("appVersionState")) if state}
        if not states:
            blockers.append(f"{name}: no state")
            continue
        unsettled = sorted(states - SETTLED)
        if unsettled:
            blockers.append(f"{name}: {', '.join(unsettled)}")
        existing = parse_version(attributes.get("versionString") or "")
        if existing is None:
            blockers.append(f"{name}: not a version")
        elif existing > wanted or (existing == wanted and not states <= OPEN):
            blockers.append(f"{name} ({', '.join(sorted(states))}) is already at or past {version}")
    for platform in APPLE_PLATFORMS:
        if platform not in seen:
            blockers.append(f"{platform}: no App Store version read")
    for item in submissions:
        attributes = item.get("attributes") or {}
        if attributes.get("state") in SUBMISSION_UNDER_WAY or not attributes.get("state"):
            blockers.append(f"review submission {attributes.get('platform')} {attributes.get('state')}")
    return blockers


def apple_catchup_blockers(versions, submissions, version: str) -> list:
    """Why Apple cannot catch up with the tagged [version] now: Apple's gates, and any version of that very number on
    App Store Connect past preparation (waiting, in review, approved, live, rejected, or withdrawn by a human)."""
    blockers = apple_blockers(versions, submissions, version)
    for item in versions or []:
        attributes = item.get("attributes") or {}
        if attributes.get("platform") not in APPLE_PLATFORMS:
            continue
        states = {state for state in (attributes.get("appStoreState"), attributes.get("appVersionState")) if state}
        if parse_version(attributes.get("versionString") or "") == parse_version(version) and \
                states - {"PREPARE_FOR_SUBMISSION"}:
            blockers.append(f"{attributes.get('platform')} {version} was submitted already "
                            f"({', '.join(sorted(states))}): a human decides")
    return blockers


def baseline_problems(tag: str, version, on_main: bool, exists: bool) -> list:
    """Why the baseline tag [tag] cannot be created on a commit ([version]: the apps' version there, or why not)."""
    problems = []
    if not tag.startswith("v") or parse_version(tag[1:]) is None:
        problems.append(f"{tag} is not vX.Y or vX.Y.Z")
    if not on_main:
        problems.append("the commit is not on main")
    if version is None:
        problems.append("the apps' version could not be read there")
    elif tag.startswith("v") and parse_version(tag[1:]) is not None and version != tag[1:]:
        problems.append(f"the apps' version there is {version}, not {tag[1:]}")
    if exists:
        problems.append(f"tag {tag} already exists")
    return problems


def play_blockers(tracks) -> list:
    """Why Google Play should not get a new release now ([tracks]: the tracks, None when unread). No API shows a
    review; closed testing behind internal testing is the sign one is going on (play_catchup.py waits for it)."""
    if tracks is None:
        return ["Google Play could not be read"]
    waiting = play_catchup.decide(tracks)
    if waiting is not None:
        return [f"closed testing is behind internal testing (version code {waiting} waits): a Google Play review is "
                "most likely in progress, and Play catch-up follows it"]
    return []


def tag_runs(runs, tag: str):
    """(state, run numbers of the passed runs) of a release workflow's runs on [tag]: none, running, failed, passed."""
    mine = [run for run in runs or [] if run.get("head_branch") == tag]
    if any(run.get("status") != "completed" for run in mine):
        return "running", []
    if any(run.get("conclusion") != "success" for run in mine):
        return "failed", []
    return ("passed" if mine else "none"), sorted(run.get("run_number") for run in mine)


def apple_catchup(state: str, blockers: list, tag: str) -> tuple:
    """(go, lines, needs a human) for Release on the existing [tag]."""
    if state == "running":
        return False, [f"Apple catch-up: a Release run on {tag} is under way"], False
    if state == "failed":
        return False, [f"Apple catch-up: a Release run on {tag} failed: a human looks, Ship does not start it again"], True
    if state == "passed":
        return False, [f"Apple catch-up: Release already ran on {tag}: nothing to catch up"], False
    if blockers:
        return False, [f"Apple catch-up blocked: {reason}" for reason in blockers], False
    return True, [f"Apple catch-up: Release starts on {tag}"], False


def android_catchup(state: str, numbers: list, bundles, tracks, tag: str) -> tuple:
    """(go, lines, needs a human) for Release Android on the existing [tag] ([bundles]: the version codes Google Play
    holds, None when unread)."""
    if bundles is None or tracks is None:
        return False, ["Android catch-up: Google Play could not be read"], False
    if state == "running":
        return False, [f"Android catch-up: a Release Android run on {tag} is under way"], False
    if state == "failed":
        return False, [f"Android catch-up: a Release Android run on {tag} failed: a human looks, Ship does not start it "
                       "again"], True
    codes = {VERSION_CODE_BASE + number for number in numbers if isinstance(number, int)}
    if codes & set(bundles):
        return False, [f"Android catch-up: Google Play holds {tag}'s bundle ({', '.join(map(str, sorted(codes & set(bundles))))})"], False
    if len(numbers) >= ANDROID_ATTEMPTS:
        return False, [f"Android catch-up: {len(numbers)} green Release Android runs on {tag} delivered nothing to Google "
                       "Play: a human looks"], True
    blockers = play_blockers(tracks)
    if blockers:
        return False, [f"Android catch-up waits: {reason}" for reason in blockers], False
    return True, [f"Android catch-up: Release Android starts on {tag} (internal testing, listing)"], False


def busy_queues(runs_by_workflow: dict) -> list:
    """The workflows of the release queues with a run waiting or running ([runs_by_workflow]: file → runs or None)."""
    busy = []
    for workflow in [name for names in QUEUES.values() for name in names]:
        runs = runs_by_workflow.get(workflow)
        if runs is None:
            busy.append(f"{workflow} (runs unreadable)")
        elif any(run.get("status") in BUSY for run in runs):
            busy.append(workflow)
    return busy


def platforms_to_ship(common: list, apple: list, android: list) -> tuple:
    """(platforms, lines): Apple and Android ship only when the gates they share hold; then each its own."""
    if common:
        return [], [f"No ship: {reason}" for reason in common]
    platforms, lines = [], []
    if apple:
        lines += [f"Apple blocked: {reason}" for reason in apple]
    else:
        platforms.append("apple")
    if android:
        lines += [f"Android blocked: {reason}" for reason in android]
    else:
        platforms.append("android")
    return platforms, lines


def plan(mode: str, sha: str, tag: str, platforms: list, repository: str, dry_run: str, ref: str) -> list:
    """The commands to run, in order: none unless DRY_RUN is the literal "false" on main and something ships.
    ship: the tag, then each platform's release; catchup: Apple's release on the existing tag, no tag; baseline: the
    tag alone."""
    if dry_run != "false" or ref != "refs/heads/main" or not sha or not tag:
        return []
    create = ["gh", "api", "-X", "POST", f"repos/{repository}/git/refs", "-f", f"ref=refs/tags/{tag}", "-f", f"sha={sha}"]
    if mode == "baseline":
        return [create]
    if mode == "catchup":
        commands = []
        if "apple" in platforms:
            commands.append(["gh", "workflow", "run", "release.yml", "--repo", repository, "--ref", tag, *RELEASE_APPLE])
        if "android" in platforms:
            commands.append(["gh", "workflow", "run", "release-android.yml", "--repo", repository, "--ref", tag,
                             *RELEASE_ANDROID])
        return commands
    if mode != "ship" or not platforms:
        return []
    commands = [create]
    if "apple" in platforms:
        commands.append(["gh", "workflow", "run", "release.yml", "--repo", repository, "--ref", tag, *RELEASE_APPLE])
    if "android" in platforms:
        commands.append(["gh", "workflow", "run", "release-android.yml", "--repo", repository, "--ref", tag, *RELEASE_ANDROID])
    return commands


def recovery(tag: str, repository: str, platforms: list) -> str:
    """What a human runs when the tag exists but a release did not start."""
    parts = []
    if "apple" in platforms:
        parts.append(f"gh workflow run release.yml --repo {repository} --ref {tag} {' '.join(RELEASE_APPLE)}")
    if "android" in platforms:
        parts.append(f"gh workflow run release-android.yml --repo {repository} --ref {tag} {' '.join(RELEASE_ANDROID)}")
    return "; ".join(parts)


# --- Facts (git, GitHub, App Store Connect) -------------------------------------------------------------------------

def git(*args: str, cwd=None) -> str:
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True, cwd=cwd).stdout


def gh_api(path: str):
    return json.loads(subprocess.run(["gh", "api", path], check=True, capture_output=True, text=True).stdout)


def show(run_git, sha: str, path: str):
    """The content of [path] at [sha], or None when it is not there."""
    try:
        return run_git("show", f"{sha}:{path}")
    except subprocess.CalledProcessError:
        return None


def locales(run_git, sha: str, folder: str) -> list:
    listed = run_git("ls-tree", "-d", "--name-only", f"{sha}:{folder}").split()
    return sorted(name for name in listed if name not in NOT_A_LOCALE)


def scheduled_runs(api, repository: str, workflow: str) -> list:
    return api(f"repos/{repository}/actions/workflows/{workflow}/runs?event=schedule&branch=main&per_page=5").get("workflow_runs", [])


def queue_runs(api, repository: str) -> dict:
    runs = {}
    for workflow in [name for names in QUEUES.values() for name in names]:
        try:
            runs[workflow] = api(f"repos/{repository}/actions/workflows/{workflow}/runs?per_page=30").get("workflow_runs", [])
        except Exception:  # noqa: BLE001 - unreadable counts as busy
            runs[workflow] = None
    return runs


def read_apple():
    """(versions, submissions) from App Store Connect, GET only; (None, None) on any failure (a refused request is an
    ApiError, which is a SystemExit)."""
    names = ("ASC_KEY_ID", "ASC_ISSUER_ID", "ASC_KEY_P8")
    if not all(os.environ.get(name) for name in names):
        print("App Store Connect: the API key secrets are not set")
        return None, None
    client = None
    try:
        client = asset_library.Client(os.environ["ASC_KEY_ID"], os.environ["ASC_ISSUER_ID"],
                                      asset_library.key_pem(os.environ["ASC_KEY_P8"]))
        reader = asset_library.Reader(client)
        app = asset_library.found_app(reader.get("/v1/apps", query=asset_library.APP_QUERY))
        versions = reader.all(f"/v1/apps/{app}/appStoreVersions", query={"limit": "200"})
        submissions = []
        for platform in APPLE_PLATFORMS:
            submissions += reader.all("/v1/reviewSubmissions", query={
                "filter[app]": app, "filter[platform]": platform, "limit": "200", "fields[reviewSubmissions]": "state,platform"})
        return versions, submissions
    except (Exception, SystemExit) as error:  # noqa: BLE001 - any doubt blocks Apple
        print(f"App Store Connect could not be read: {error}")
        return None, None
    finally:
        if client:
            client.close()


def read_play():
    """(tracks, version codes of the bundles) from Google Play, inside an edit that is always deleted, never committed
    (as play_catchup.py reads); (None, None) on any failure."""
    raw = os.environ.get("PLAY_SERVICE_ACCOUNT_JSON", "")
    if not raw.strip():
        print("Google Play: the service account secret is not set")
        return None, None
    try:
        client = play_status.Client(play_status.access_token(json.loads(raw)))
        base = f"{play_status.PUBLISHER}{play_status.PACKAGE}/edits"
        edit = client.call("POST", base, body={})
        try:
            tracks = client.call("GET", f"{base}/{edit['id']}/tracks").get("tracks", [])
            bundles = client.call("GET", f"{base}/{edit['id']}/bundles").get("bundles", [])
        finally:
            client.call("DELETE", f"{base}/{edit['id']}")
        return tracks, [int(bundle["versionCode"]) for bundle in bundles if str(bundle.get("versionCode", "")).isdigit()]
    except (Exception, SystemExit) as error:  # noqa: BLE001 - any doubt holds Android back
        print(play_status.safe(f"Google Play could not be read ({type(error).__name__})"))
        return None, None


def runs_on(api, repository: str, workflow: str, sha: str) -> list:
    """Every run of [workflow] on the commit [sha] (a tag's runs among them), or an exception."""
    answer = api(f"repos/{repository}/actions/workflows/{workflow}/runs?head_sha={sha}&per_page=100")
    runs = answer.get("workflow_runs", [])
    if answer.get("total_count", len(runs)) > len(runs):
        raise RuntimeError(f"more than {len(runs)} {workflow} runs on {sha}")
    return runs


def content_gates(run_git, base: str, commit: str, lines: list):
    """Gates c and d between the tag [base] and [commit]: None when they hold, else why not."""
    changed = run_git("diff", "--no-renames", "--name-only", base, commit).split()
    apple_locales, play_locales = locales(run_git, commit, APPLE_METADATA), locales(run_git, commit, PLAY_METADATA)
    texts = {path: show(run_git, commit, path) for path in
             [APPLE_NOTES.format(locale=locale) for locale in apple_locales] +
             [PLAY_NOTES.format(locale=locale) for locale in play_locales]}
    missing = missing_notes(changed, apple_locales, play_locales, texts)
    if missing:
        return f"release notes not rewritten since {base}: {', '.join(missing)}"
    shipped = []
    for paths in DELIVER_PATHS.values():
        shipped += run_git("diff", "--name-only", base, commit, "--", *paths.split()).split()
    shipped = [path for path in shipped
               if not (path in (PROJECT_YML, GRADLE) and version_only(run_git("diff", "-U0", base, commit, "--", path)))]
    if not shipped:
        return f"nothing the apps ship changed since {base} (a version bump alone does not count)"
    lines.append(f"{len(shipped)} shipped file(s) changed since {base}, such as {shipped[0]}.")
    return None


def catchup(decision, stop, repository, run_git, api, apple, play, production, tags, tag, sha, version) -> dict:
    """Catch-up on the existing [tag], when S carries exactly its version: each platform that did not get it."""
    lines = decision["lines"]
    decision["mode"], decision["tag"] = "catchup", tag
    lines.append(f"Version {version} is already tagged {tag}: catch-up only, no new tag.")
    try:
        tag_sha = run_git("rev-parse", f"{tag}^{{commit}}").strip()
        run_git("merge-base", "--is-ancestor", tag_sha, sha)
    except subprocess.CalledProcessError:
        return stop(f"{tag} is not on the history of {sha}")
    decision["sha"] = tag_sha
    tagged, why = apps_version(show(run_git, tag_sha, PROJECT_YML), show(run_git, tag_sha, GRADLE))
    if why or tagged != version:
        return stop(f"the apps' version at {tag} is not {version} ({why or tagged})")
    previous, why = latest_tag([name for name in tags if name != tag])
    if why:
        return stop(f"no version tag before {tag}: a baseline tag is never released by Ship")
    why = content_gates(run_git, previous, tag_sha, lines)
    if why:
        return stop(f"{tag} would not have passed Ship's gates: {why}")
    busy = busy_queues(queue_runs(api, repository))
    if busy:
        return stop(f"a release queue is busy ({', '.join(busy)}): nothing waiting is pushed out; the next Ship tries again")

    platforms = []
    for platform, workflow in RELEASE_WORKFLOWS.items():
        try:
            state, numbers = tag_runs(runs_on(api, repository, workflow, tag_sha), tag)
        except Exception as error:  # noqa: BLE001 - unreadable: no catch-up
            lines.append(f"{platform.capitalize()} catch-up: the {workflow} runs on {tag} could not be read ({error})")
            continue
        if production("ci" if platform == "apple" else "android", tag_sha) != 0:
            lines.append(f"{platform.capitalize()} catch-up: the long tests did not pass on {tag_sha}")
            continue
        if platform == "apple":
            versions, submissions = apple() if state == "none" else (None, None)
            go, more, human = apple_catchup(state, apple_catchup_blockers(versions, submissions, version), tag)
        else:
            tracks, bundles = play()
            go, more, human = android_catchup(state, numbers, bundles, tracks, tag)
        lines.extend(more)
        decision["human"] = decision.get("human", False) or human
        if go:
            platforms.append(platform)
    decision["platforms"] = platforms
    return decision


def evaluate_baseline(tag: str, sha: str, run_git=git, head: str = "HEAD") -> dict:
    """The first version tag, created by hand through Ship: checked, nothing else started."""
    decision = {"mode": "baseline", "sha": None, "version": None, "tag": tag, "platforms": [], "lines": []}
    try:
        commit = run_git("rev-parse", "--verify", f"{sha}^{{commit}}").strip()
    except subprocess.CalledProcessError:
        decision["lines"].append(f"No baseline: {sha} is not a commit")
        return decision
    try:
        run_git("merge-base", "--is-ancestor", commit, head)
        on_main = True
    except subprocess.CalledProcessError:
        on_main = False
    version, _ = apps_version(show(run_git, commit, PROJECT_YML), show(run_git, commit, GRADLE))
    exists = bool(run_git("tag", "--list", tag).strip())
    problems = baseline_problems(tag, version, on_main, exists)
    decision["sha"], decision["version"] = commit, version
    if problems:
        decision["lines"] += [f"No baseline: {problem}" for problem in problems]
        return decision
    decision["platforms"] = ["baseline"]
    decision["lines"].append(f"Baseline {tag} on {commit} (version {version}): the tag alone, nothing started.")
    return decision


def evaluate(repository: str, run_git=git, api=gh_api, apple=read_apple, production=None, head: str = "HEAD",
             play=read_play) -> dict:
    """Every gate, in order; the first shared one that fails stops the reading. Returns the decision."""
    production = production or (lambda workflow, sha: ci_scope.production(workflow, sha, repository, api))
    decision = {"mode": "ship", "sha": None, "version": None, "tag": None, "platforms": [], "lines": [], "human": False}
    lines = decision["lines"]

    def stop(reason: str) -> dict:
        decision["platforms"], more = platforms_to_ship([reason], [], [])
        lines.extend(more)
        return decision

    try:
        sha, why = night_sha(scheduled_runs(api, repository, "ci.yml"), scheduled_runs(api, repository, "android.yml"))
    except Exception as error:  # noqa: BLE001
        return stop(f"the night's runs could not be read ({error})")
    if why:
        return stop(why)
    decision["sha"] = sha
    lines.append(f"The night tested {sha}.")
    try:
        run_git("merge-base", "--is-ancestor", sha, head)
    except subprocess.CalledProcessError:
        return stop(f"{sha} is not on main")
    head_sha = run_git("rev-parse", head).strip()
    if head_sha != sha:
        lines.append(f"main is at {head_sha} since: Ship takes the night's commit, {sha}, never a newer one.")
    for workflow in ("ci", "android"):
        if production(workflow, sha) != 0:
            return stop(f"ci_scope.py production {workflow} refused {sha}")

    tags = run_git("tag", "--list", "v*").split()
    tag, why = latest_tag(tags)
    if why:
        return stop(why)
    version, why = apps_version(show(run_git, sha, PROJECT_YML), show(run_git, sha, GRADLE))
    if why:
        return stop(why)
    decision["version"], decision["tag"] = version, f"v{version}"
    lines.append(f"Version {version} at {sha}; last tag {tag}.")
    # The tag names this very version (the same string, as release.yml and release-android.yml compare it).
    if tag == f"v{version}":
        return catchup(decision, stop, repository, run_git, api, apple, play, production, tags, tag, sha, version)
    if not newer(version, tag):
        return stop(f"version {version} is not above {tag}: the owner bumps it with the work (the workflow never does)")
    if run_git("tag", "--list", decision["tag"]).strip():
        return stop(f"tag {decision['tag']} already exists")

    why = content_gates(run_git, tag, sha, lines)
    if why:
        return stop(why)

    busy = busy_queues(queue_runs(api, repository))
    if busy:
        return stop(f"a release queue is busy ({', '.join(busy)}): nothing waiting is pushed out; the next Ship tries again")

    versions, submissions = apple()
    tracks, _ = play()
    platforms, more = platforms_to_ship([], apple_blockers(versions, submissions, version), play_blockers(tracks))
    decision["platforms"] = platforms
    lines.extend(more)
    return decision


def main() -> int:
    repository = os.environ.get("GITHUB_REPOSITORY", "")
    dry_run = os.environ.get("DRY_RUN", "true")
    ref = os.environ.get("GITHUB_REF", "")
    baseline_tag, baseline_sha = os.environ.get("BASELINE_TAG", "").strip(), os.environ.get("BASELINE_SHA", "").strip()
    if baseline_tag or baseline_sha:
        if os.environ.get("GITHUB_EVENT_NAME") != "workflow_dispatch" or not baseline_tag or not baseline_sha:
            print("::error::A baseline needs baseline_tag and baseline_sha, in a run by hand: nothing created.")
            return 1
    try:
        decision = evaluate_baseline(baseline_tag, baseline_sha) if baseline_tag else evaluate(repository)
    except Exception as error:  # noqa: BLE001 - when unsure, nothing ships
        # Red: a gate that does not hold is a quiet day, a decision that cannot be made is a fault to look at.
        print(f"::error::No ship: the decision failed ({error})")
        return 1
    for line in decision["lines"]:
        print(line)
    mode, platforms, tag = decision["mode"], decision["platforms"], decision["tag"]
    if mode == "baseline":
        summary = f"Baseline {tag} on {decision['sha']}" if platforms else "No baseline"
    elif mode == "catchup":
        summary = f"Catch-up of {tag} ({decision['sha']}): {', '.join(platforms)}" if platforms else "No ship today"
    else:
        summary = f"Ship {tag} ({decision['sha']}): {', '.join(platforms)}" if platforms else "No ship today"
    commands = plan(mode, decision["sha"], tag, platforms, repository, dry_run, ref)
    if platforms and not commands:
        summary += f" (dry run: nothing created or started; DRY_RUN={dry_run}, {ref})"
    print(summary)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as output:
            output.write(f"### {summary}\n\n" + "".join(f"- {line}\n" for line in decision["lines"]))
    if mode == "baseline" and not platforms:
        return 1
    for index, command in enumerate(commands):
        result = subprocess.run(command, capture_output=True, text=True)
        if result.returncode != 0:
            if index == 0 and mode != "catchup":
                # A human's tag of the same name, a ruleset, a missing permission: nothing was started.
                print(f"::error::The tag {tag} could not be created ({result.stderr.strip()}): nothing was started.")
            else:
                print(f"::error::{command[3]} could not be started ({result.stderr.strip()}). The tag {tag} exists: start "
                      f"what is left by hand: {recovery(tag, repository, platforms)}")
            return 1
        print(" ".join(command[:4]) + f" {tag}: done")
    if commands and mode == "ship":
        for platform in ("apple", "android"):
            if platform not in platforms:
                print(f"::warning::{tag} did not go to {'Apple' if platform == 'apple' else 'Google Play'}: Ship catches up "
                      f"after a later green night; by hand: {recovery(tag, repository, [platform])}")
    if decision.get("human"):
        print(f"::warning::{tag} needs a human (see above): Ship does not start that release again.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
