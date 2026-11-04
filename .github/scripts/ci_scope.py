#!/usr/bin/env python3
"""Which heavy jobs a CI run needs (docs/CI.md).

    ci_scope.py scope ci|android      the first job of ci.yml / android.yml: writes run=true|false to $GITHUB_OUTPUT
    ci_scope.py passed ci|android SHA exit 0 when a run of that workflow fully passed on SHA or on the same tree

The rule is "when unsure, run everything": any error, a missing main, an empty difference or an unknown path runs
the heavy jobs. They are skipped in two cases only:

- a run started by hand on a working branch whose changes since its merge-base with main touch none of the paths
  the workflow reads (PATHS below, the same ordered patterns as the workflow's push and pull_request filters);
- a push to main whose commit, or the same tree, already passed the same workflow with its heavy jobs actually run
  (a push to main or a run by hand; never a pull request, which tests a merge commit).

core-linux, the listing checks and security.yml are not decided here: they always run.
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
# The jobs whose success proves the run: all of them must have run and passed.
HEAVY_JOBS = {
    "ci": ["Build iOS + macOS (Debug, App Store edition)", "App tests — unit + UI (iPhone simulator)"],
    "android": ["Core (Kotlin) tests", "App — lint, tests, screenshots, bundle"],
}
# Jobs that mark a run that did not compare what it should (a run that recorded the reference screenshots).
NOT_RUN_JOBS = {"ci": [], "android": ["Commit the reference screenshots to the branch"]}
CANDIDATES = 10


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


def full_pass(workflow: str, sha: str, tree: str, repository: str, api=gh_api, exclude_run: int = 0):
    """The id of a run of [workflow] that passed on [sha] or on [tree] with its heavy jobs run, or None."""
    listing = api(f"repos/{repository}/actions/workflows/{WORKFLOW_FILES[workflow]}/runs?status=success&per_page=100")
    candidates = [run for run in listing.get("workflow_runs", [])
                  if run.get("id") != exclude_run and run.get("conclusion") == "success"
                  and run.get("event") in ("push", "workflow_dispatch")
                  and (run.get("head_sha") == sha or (tree and (run.get("head_commit") or {}).get("tree_id") == tree))]
    for run in candidates[:CANDIDATES]:
        jobs = {job.get("name"): job.get("conclusion")
                for job in api(f"repos/{repository}/actions/runs/{run['id']}/jobs?filter=latest&per_page=100").get("jobs", [])}
        if all(jobs.get(name) == "success" for name in HEAVY_JOBS[workflow]) and \
                all(jobs.get(name) in (None, "skipped") for name in NOT_RUN_JOBS[workflow]):
            return run["id"]
    return None


def decide(workflow: str, event: str, ref: str, sha: str, changed=changed_since_main, tree=None, api=gh_api,
           repository: str = "", run_id: int = 0, record: bool = False) -> tuple:
    """(run the heavy jobs?, why)."""
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
    if event == "push" and ref == "refs/heads/main":
        try:
            tree_id = tree() if callable(tree) else tree
            passed = full_pass(workflow, sha, tree_id, repository, api, run_id)
        except Exception as error:  # noqa: BLE001 - when unsure, run
            return True, f"earlier runs could not be read ({error})"
        if passed:
            return False, f"run {passed} already passed on this commit or the same tree, with every job run"
        return True, "no earlier run passed on this commit or the same tree"
    return True, f"{event} on {ref}: everything runs"


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
        line = f"{'Heavy jobs run' if run else 'Heavy jobs skipped'}: {why}"
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
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
