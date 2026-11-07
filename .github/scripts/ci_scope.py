#!/usr/bin/env python3
"""Which long jobs a CI run needs (docs/CI.md).

    ci_scope.py scope ci|android       the first job of ci.yml / android.yml: writes run=true|false to $GITHUB_OUTPUT
    ci_scope.py passed ci|android SHA  exit 0 when a run of that workflow fully passed on SHA or on the same tree
    ci_scope.py production ci|android SHA
                                       exit 0 only when a run of that workflow passed with its long jobs on exactly
                                       SHA (before App Review or Google Play production); any doubt refuses

Each workflow has short jobs, which run in every run (ci.yml: lint, configuration checks and the Swift core tests on
Linux; android.yml: the Kotlin core tests, lint, the debug APK and the release bundle, the listing limits), and long
jobs (HEAVY_JOBS: ci.yml's macOS jobs, android.yml's unit, Compose and screenshot tests), which run at night and by hand
only. A push or a pull request never runs the long jobs: the testers' builds leave after the short jobs.

The rule is "when unsure, run everything": any error, a missing main, an empty difference or an unknown path runs
the long jobs of a night or of a run by hand. They are skipped there in two cases only:

- the night's run (schedule, on main), when main's very commit already passed the same workflow with its long jobs
  run (an earlier night or a run by hand; never a pull request, which tests a merge commit): main did not change
  since. The commit, not the tree: production needs a pass on its exact commit (`production`);
- a run started by hand on a working branch whose changes since its merge-base with main touch none of the paths
  the workflow reads (PATHS below, the same ordered patterns as the workflow's push and pull_request filters).
"""
import json
import os
import re
import subprocess
import sys

# GitHub's path filter syntax, in order, the last matching pattern wins: "**" any path, "*" any name within one
# folder, "!" excludes. Each list is copied byte for byte into the workflow's `paths:` (test_ci_scope.py checks it).
# A new folder read by both apps (fixtures, a shared spec) must not be excluded from either list.
SHARED_SPECS = ["docs/SYNC*", "docs/PROFILES*"]
# GitHub documents "**" as matching any character including "/", but says nothing of names that start with a dot:
# ".*" and ".*/**" include the dot files and folders (.github, .claude, .githooks, .swiftlint.yml) whatever it does.
DOT_PATHS = [".*", ".*/**"]
PATHS = {
    "ci": ["**", *DOT_PATHS, "!android/**", "!fastlane/metadata-android/**", "!site/**", "!docs/**", *SHARED_SPECS, "!*.md"],
    "android": ["**", *DOT_PATHS, "!App/**", "!AppTests/**", "!UITests/**", "!MacUITests/**", "!Packages/**", "!project.yml",
                "!fastlane/metadata/**", "!site/**", "!docs/**", *SHARED_SPECS, "!*.md"],
}
WORKFLOW_FILES = {"ci": "ci.yml", "android": "android.yml"}
# The long jobs, which run at night and by hand only (job "What to run"): all of them must have run and passed for a run
# to prove a commit.
HEAVY_JOBS = {
    "ci": ["Build iOS + macOS (Debug, App Store edition)", "App tests — unit + UI (iPhone simulator)"],
    "android": ["App — unit, Compose and screenshot tests"],
}
# Jobs that mark a run that did not compare what it should (a run that recorded the reference screenshots).
NOT_RUN_JOBS = {"ci": [], "android": ["Commit the reference screenshots to the branch"]}
# The runs whose long jobs prove a commit: a pull request tests a merge commit, not the commit it names. A push's run
# skips its long jobs, so it proves nothing (the jobs are checked either way).
PROVING_EVENTS = ("push", "workflow_dispatch", "schedule")
# Without [exact], the newest runs of the same tree looked at; with it, every run of the commit, page after page (a night
# that skipped its long jobs also ends green on the commit, and many may come before the run that passed them).
CANDIDATES = 10
PAGE = 100
MAX_PAGES = 50


def pattern_regex(pattern: str) -> re.Pattern:
    regex = ""
    i = 0
    while i < len(pattern):
        if pattern.startswith("**", i):
            regex += ".*"
            i += 2
        elif pattern[i] == "*":
            regex += "[^/]*"
            i += 1
        else:
            regex += re.escape(pattern[i])
            i += 1
    return re.compile(regex)


def relevant(path: str, patterns: list) -> bool:
    """Whether GitHub's path filter [patterns] selects [path]."""
    selected = False
    for pattern in patterns:
        negative = pattern.startswith("!")
        if pattern_regex(pattern.lstrip("!")).fullmatch(path):
            selected = not negative
    return selected


def git(*args: str) -> str:
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout.strip()


def changed_since_main(main: str = "origin/main") -> list:
    """The files a working branch changed since it left main (both names of a renamed file)."""
    base = git("merge-base", main, "HEAD")
    return [line for line in git("diff", "--no-renames", "--name-only", base, "HEAD").splitlines() if line]


def gh_api(path: str) -> dict:
    return json.loads(subprocess.run(["gh", "api", path], check=True, capture_output=True, text=True).stdout)


def full_pass(workflow: str, sha: str, tree: str, repository: str, api=gh_api, exclude_run: int = 0,
              exact: bool = False):
    """The id of a run of [workflow] that passed on [sha] (or on [tree], unless [exact]) with its heavy jobs run, or
    None."""
    runs = f"repos/{repository}/actions/workflows/{WORKFLOW_FILES[workflow]}/runs?status=success"
    if exact:
        # The exact commit is asked of GitHub by name (a night's run may be older than the last 100 green pushes), and
        # every page of it is read.
        listed = []
        for page in range(1, MAX_PAGES + 1):
            batch = api(f"{runs}&head_sha={sha}&per_page={PAGE}" + (f"&page={page}" if page > 1 else "")).get("workflow_runs", [])
            listed += batch
            if len(batch) < PAGE:
                break
        else:
            raise RuntimeError(f"more than {MAX_PAGES * PAGE} runs on {sha}")
    else:
        listed = api(f"{runs}&per_page={PAGE}").get("workflow_runs", [])
    candidates = [run for run in listed
                  if run.get("id") != exclude_run and run.get("conclusion") == "success"
                  and run.get("event") in PROVING_EVENTS
                  and (run.get("head_sha") == sha or
                       (not exact and tree and (run.get("head_commit") or {}).get("tree_id") == tree))]
    for run in candidates if exact else candidates[:CANDIDATES]:
        jobs = {job.get("name"): job.get("conclusion")
                for job in api(f"repos/{repository}/actions/runs/{run['id']}/jobs?filter=latest&per_page=100").get("jobs", [])}
        if all(jobs.get(name) == "success" for name in HEAVY_JOBS[workflow]) and \
                all(jobs.get(name) in (None, "skipped") for name in NOT_RUN_JOBS[workflow]):
            return run["id"]
    return None


def decide(workflow: str, event: str, ref: str, sha: str, changed=changed_since_main, tree=None, api=gh_api,
           repository: str = "", run_id: int = 0, record: bool = False) -> tuple:
    """(run the long jobs?, why)."""
    if event == "workflow_dispatch" and ref.startswith("refs/heads/") and ref != "refs/heads/main":
        if record:
            return True, "the reference screenshots are recorded"
        try:
            files = changed()
        except Exception as error:  # noqa: BLE001 - when unsure, run
            return True, f"the changes since main are unknown ({error})"
        if not files:
            return True, "no change since main: everything runs"
        read = [path for path in files if relevant(path, PATHS[workflow])]
        if read:
            return True, f"{len(read)} changed file(s) this workflow reads, such as {read[0]}"
        return False, f"none of the {len(files)} changed file(s) is read by this workflow, such as {files[0]}"
    if event in ("push", "pull_request"):
        return False, f"{event}: the short jobs only; the long ones run at night on main, or by hand (docs/CI.md)"
    if event == "schedule" and ref == "refs/heads/main":
        # The exact commit, as production asks: a commit whose tree passed under another commit is tested again.
        try:
            passed = full_pass(workflow, sha, None, repository, api, run_id, exact=True)
        except Exception as error:  # noqa: BLE001 - when unsure, run
            return True, f"earlier runs could not be read ({error})"
        if passed:
            return False, f"main did not change: run {passed} already passed on this commit, long jobs run"
        return True, "the night's run: no earlier run passed on this commit with its long jobs"
    return True, f"{event} on {ref}: everything runs"


# What a refusal says to do: the long jobs of the workflow on this commit, then the publishing run again.
PRODUCTION = {
    "ci": ("its macOS jobs", "submitted for App Review", "ci.yml"),
    "android": ("its unit, Compose and screenshot tests", "published on Google Play production", "android.yml"),
}


def production(workflow: str, sha: str, repository: str, api=gh_api) -> int:
    """0 when [workflow] passed with its long jobs on exactly [sha]; 1 otherwise, an unreadable answer included."""
    jobs, publishing, file = PRODUCTION[workflow]
    try:
        passed = full_pass(workflow, sha, None, repository, api, exact=True)
    except Exception as error:  # noqa: BLE001 - when unsure, nothing is published
        print(f"::error::The {file} runs of {sha} could not be read ({error}): nothing is {publishing}. "
              "Run this release again in a few minutes.")
        return 1
    if passed:
        print(f"Run {passed} of {file} passed on {sha} with {jobs}: it can be {publishing}.")
        return 0
    print(f"::error::No run of {file} passed on {sha} with {jobs} (they run at night on main, or by hand): nothing is "
          f"{publishing}. Wait for the night's run on main, or start {file} by hand on this commit (Actions → Run "
          f"workflow, or gh workflow run {file} --ref <tag or branch>), then run this release again (Re-run all jobs).")
    return 1


def main(argv: list) -> int:
    if len(argv) >= 2 and argv[0] == "scope" and argv[1] in PATHS:
        workflow = argv[1]
        try:
            run, why = decide(workflow, os.environ.get("GITHUB_EVENT_NAME", ""), os.environ.get("GITHUB_REF", ""),
                              os.environ.get("GITHUB_SHA", ""), tree=lambda: git("rev-parse", "HEAD^{tree}"),
                              repository=os.environ.get("GITHUB_REPOSITORY", ""),
                              run_id=int(os.environ.get("GITHUB_RUN_ID", "0") or 0),
                              record=os.environ.get("RECORD", "") == "true")
        except Exception as error:  # noqa: BLE001 - when unsure, run
            run, why = True, f"the decision failed ({error})"
        line = f"{'Long jobs run' if run else 'Long jobs skipped'}: {why}"
        print(line)
        for name, text in (("GITHUB_OUTPUT", f"run={'true' if run else 'false'}\n"), ("GITHUB_STEP_SUMMARY", f"### {line}\n")):
            if os.environ.get(name):
                with open(os.environ[name], "a", encoding="utf-8") as output:
                    output.write(text)
        return 0
    if len(argv) == 3 and argv[0] == "passed" and argv[1] in PATHS:
        try:
            tree = git("rev-parse", f"{argv[2]}^{{tree}}")
        except Exception:  # noqa: BLE001 - the commit alone then
            tree = None
        try:
            passed = full_pass(argv[1], argv[2], tree, os.environ.get("GITHUB_REPOSITORY", ""))
        except Exception as error:  # noqa: BLE001
            print(f"Earlier runs could not be read ({error})")
            return 1
        print(f"Run {passed} passed on {argv[2]} or the same tree." if passed else f"No full pass on {argv[2]}.")
        return 0 if passed else 1
    if len(argv) == 3 and argv[0] == "production" and argv[1] in PATHS:
        return production(argv[1], argv[2], os.environ.get("GITHUB_REPOSITORY", ""))
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
