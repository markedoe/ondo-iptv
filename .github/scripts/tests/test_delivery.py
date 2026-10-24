"""The delivery scripts of the workflows decide what goes to the testers and what waits: these tests run them as
GitHub does (bash -e), in a temporary repository, with a stand-in for the `gh` command that answers from a list of
workflow runs and records the runs it is asked to start.

Run: python3 -m unittest discover -s .github/scripts/tests
"""
import json
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

WORKFLOWS = Path(__file__).resolve().parents[2] / "workflows"

# `gh run list` filters the runs as GitHub does, then applies the two `--jq` filters the workflows use; `gh workflow
# run` is recorded. Any other call fails, so a new call in a workflow needs its answer here first.
FAKE_GH = r'''#!/usr/bin/env python3
import json, os, re, sys

args = sys.argv[1:]


def option(name):
    return args[args.index(name) + 1] if name in args else None


if args[:2] == ["workflow", "run"]:
    with open(os.environ["FAKE_GH_LOG"], "a") as log:
        log.write(" ".join(args[2:]) + "\n")
    sys.exit(0)
if args[:2] == ["run", "list"]:
    with open(os.environ["FAKE_GH_RUNS"]) as source:
        runs = json.load(source).get(option("--workflow"), [])
    if option("--status"):
        runs = [run for run in runs if option("--status") in (run["status"], run.get("conclusion"))]
    if option("--event"):
        runs = [run for run in runs if run.get("event", "push") == option("--event")]
    if option("--branch"):
        runs = [run for run in runs if run.get("branch", "main") == option("--branch")]
    runs = runs[:int(option("--limit") or 20)]
    jq = option("--jq")
    previous = re.fullmatch(r'\[\.\[\] \| \.headSha \| select\(\. != "(\w+)"\)\]\[0\] // empty', jq)
    if previous:
        print(next((run["headSha"] for run in runs if run["headSha"] != previous.group(1)), ""))
        sys.exit(0)
    waiting = ("queued", "pending", "waiting", "requested")
    if jq == "[.[] | select(" + " or ".join(f'.status == "{s}"' for s in waiting) + ")] | length":
        print(sum(run["status"] in waiting for run in runs))
        sys.exit(0)
    sys.exit(f"fake gh: unknown --jq {jq}")
sys.exit(f"fake gh: unknown command {args}")
'''


def step_script(workflow: str, step: str) -> str:
    """The `run:` script of the step named [step] in .github/workflows/[workflow]."""
    lines = (WORKFLOWS / workflow).read_text(encoding="utf-8").splitlines()
    start = next(i for i, line in enumerate(lines) if line.strip() == f"- name: {step}")
    run = next(i for i in range(start + 1, len(lines)) if lines[i].strip().startswith("run:"))
    if lines[run].strip() != "run: |":
        return lines[run].strip()[len("run:"):].strip() + "\n"
    key_indent = len(lines[run]) - len(lines[run].lstrip())
    block = []
    for line in lines[run + 1:]:
        if line.strip() and len(line) - len(line.lstrip()) <= key_indent:
            break
        block.append(line)
    indent = min(len(line) - len(line.lstrip()) for line in block if line.strip())
    return "\n".join(line[indent:] for line in block) + "\n"


GIT_ENVIRONMENT = {"GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull, "GIT_AUTHOR_NAME": "ci",
                   "GIT_AUTHOR_EMAIL": "ci@users.noreply.github.com", "GIT_COMMITTER_NAME": "ci",
                   "GIT_COMMITTER_EMAIL": "ci@users.noreply.github.com"}


@unittest.skipUnless(shutil.which("git") and shutil.which("bash"), "git and bash are needed")
class WorkflowScriptTestCase(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root)
        self.repository = self.root / "repository"
        self.repository.mkdir()
        bin_folder = self.root / "bin"
        bin_folder.mkdir()
        (bin_folder / "gh").write_text(FAKE_GH, encoding="utf-8")
        (bin_folder / "gh").chmod(0o755)
        self.environment = {**os.environ, **GIT_ENVIRONMENT, "PATH": f"{bin_folder}{os.pathsep}{os.environ['PATH']}",
                            "FAKE_GH_LOG": str(self.root / "dispatched.txt"), "FAKE_GH_RUNS": str(self.root / "runs.json"),
                            "GH_TOKEN": "none", "REPO": "owner/ondo-iptv", "GITHUB_REPOSITORY": "owner/ondo-iptv"}
        self.git("init", "-q", "-b", "main")
        self.commits = {}

    def git(self, *args: str) -> str:
        return subprocess.run(["git", *args], cwd=self.repository, env=self.environment, check=True,
                              capture_output=True, text=True).stdout.strip()

    def commit(self, name: str, *paths: str) -> str:
        """A commit on main, called [name], that changes each of [paths]."""
        for path in paths:
            file = self.repository / path
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text(f"{name}\n", encoding="utf-8")
            self.git("add", path)
        self.git("commit", "-q", "--allow-empty", "-m", name)
        self.commits[name] = self.git("rev-parse", "HEAD")
        return self.commits[name]

    def run_script(self, script: str, runs: dict, **environment: str) -> tuple[str, list]:
        """Runs [script] as a `run:` step at main's last commit; the output and the runs it started."""
        (self.root / "runs.json").write_text(json.dumps(runs), encoding="utf-8")
        (self.root / "step.sh").write_text(script, encoding="utf-8")
        result = subprocess.run(["bash", "-e", str(self.root / "step.sh")], cwd=self.repository,
                                env={**self.environment, **environment}, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        log = self.root / "dispatched.txt"
        dispatched = log.read_text(encoding="utf-8").splitlines() if log.exists() else []
        return result.stdout + result.stderr, dispatched


def green(sha: str) -> dict:
    return {"headSha": sha, "status": "completed", "conclusion": "success", "event": "push", "branch": "main"}


def run_in(status: str, sha: str = "0" * 40, event: str = "workflow_dispatch") -> dict:
    return {"headSha": sha, "status": status, "conclusion": "", "event": event, "branch": "main"}


TESTFLIGHT = "release.yml --repo owner/ondo-iptv --ref main -f target=builds -f edition=testflight"
INTERNAL_TESTING = "release-android.yml --repo owner/ondo-iptv --ref main -f track=internal -f status=completed -f listing=skip"


class DeliverTests(WorkflowScriptTestCase):
    """deliver.yml: a green push to main goes to TestFlight / internal testing when what the apps ship changed."""

    def deliver(self, source: str, passed: str, runs: dict) -> tuple[str, list]:
        return self.run_script(step_script("deliver.yml", "Send the push's builds"), runs,
                               SOURCE=source, SHA=self.commits[passed])

    def test_a_change_to_the_app_goes_to_testflight(self):
        self.commit("first", "App/OndoApp.swift")
        self.commit("change", "App/Model/AppModel.swift")
        output, dispatched = self.deliver("CI", "change", {"ci.yml": [green(self.commits["change"]), green(self.commits["first"])]})
        self.assertEqual(dispatched, [TESTFLIGHT], output)

    def test_tests_alone_send_nothing(self):
        self.commit("first", "App/OndoApp.swift", "android/app/src/main/Main.kt")
        self.commit("tests", "Packages/IPTVCore/Tests/IPTVCoreTests/ParserTests.swift", "AppTests/SortTests.swift",
                    "android/app/src/test/kotlin/MainTest.kt", "android/core/src/test/kotlin/CoreTest.kt")
        runs = {"ci.yml": [green(self.commits["tests"]), green(self.commits["first"])],
                "android.yml": [green(self.commits["tests"]), green(self.commits["first"])]}
        for source in ("CI", "Android"):
            output, dispatched = self.deliver(source, "tests", runs)
            self.assertEqual(dispatched, [], output)
            self.assertIn("Nothing the apps ship changed", output)

    def test_a_tagged_commit_is_left_to_its_release(self):
        self.commit("first", "App/OndoApp.swift")
        self.commit("version", "App/Model/AppModel.swift")
        self.git("tag", "v1.2.0")
        output, dispatched = self.deliver("CI", "version", {"ci.yml": [green(self.commits["version"]), green(self.commits["first"])]})
        self.assertEqual(dispatched, [], output)
        self.assertIn("carries a version tag", output)

    def test_a_newer_push_that_changes_the_app_sends_it(self):
        self.commit("first", "App/OndoApp.swift")
        self.commit("change", "App/Model/AppModel.swift")
        self.commit("newer", "App/Model/AppModel.swift")
        output, dispatched = self.deliver("CI", "change", {"ci.yml": [green(self.commits["change"]), green(self.commits["first"])]})
        self.assertEqual(dispatched, [], output)
        self.assertIn("own delivery sends it", output)

    def test_a_waiting_release_is_never_replaced(self):
        self.commit("first", "App/OndoApp.swift")
        self.commit("change", "App/Model/AppModel.swift")
        runs = {"ci.yml": [green(self.commits["change"]), green(self.commits["first"])],
                "release.yml": [run_in("pending", event="push"), run_in("in_progress")]}
        output, dispatched = self.deliver("CI", "change", runs)
        self.assertEqual(dispatched, [], output)
        self.assertIn("already waiting", output)


class TestFlightEditionAgainTests(WorkflowScriptTestCase):
    """release.yml: after a tag's release, the TestFlight edition of the tag is built again for the testers."""

    def start(self, runs: dict) -> tuple[str, list]:
        self.commit("version", "App/OndoApp.swift")
        return self.run_script(step_script("release.yml", "Start the TestFlight edition build"), runs,
                               GITHUB_REF_NAME="v1.2.0")

    def test_the_tags_testflight_edition_is_built_again(self):
        output, dispatched = self.start({"release.yml": [run_in("in_progress", event="push")]})
        self.assertEqual(dispatched, [TESTFLIGHT.replace("--ref main", "--ref v1.2.0")], output)

    def test_a_waiting_release_is_never_replaced(self):
        # A newer tag's release waits for this one: GitHub would cancel it for the run started here.
        output, dispatched = self.start({"release.yml": [run_in("pending", event="push"), run_in("in_progress", event="push")]})
        self.assertEqual(dispatched, [], output)
        self.assertIn("already waiting", output)


if __name__ == "__main__":
    unittest.main()
