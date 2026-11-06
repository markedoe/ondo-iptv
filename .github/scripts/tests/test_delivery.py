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
if args[:1] == ["api"]:
    with open(os.environ["FAKE_GH_API"]) as source:
        responses = json.load(source)
    if args[1] not in responses:
        sys.exit(f"fake gh: unknown api {args[1]}")
    print(json.dumps(responses[args[1]]))
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
    if option("--commit"):
        runs = [run for run in runs if run["headSha"] == option("--commit")]
    runs = runs[:int(option("--limit") or 20)]
    jq = option("--jq")
    previous = re.fullmatch(r'\[\.\[\] \| \.headSha \| select\(\. != "(\w+)"\)\]\[0\] // empty', jq)
    if previous:
        print(next((run["headSha"] for run in runs if run["headSha"] != previous.group(1)), ""))
        sys.exit(0)
    if jq == "length":
        print(len(runs))
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
                            "FAKE_GH_API": str(self.root / "api.json"),
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
            file.write_text(f"{name} {len(self.git('log', '--oneline', '--all').splitlines())}\n", encoding="utf-8")
            self.git("add", path)
        self.git("commit", "-q", "-m", name)
        self.commits[name] = self.git("rev-parse", "HEAD")
        return self.commits[name]

    def run_script(self, script: str, runs: dict, **environment: str) -> tuple[str, list]:
        """Runs [script] as a `run:` step at main's last commit; the output and the runs it started."""
        (self.root / "runs.json").write_text(json.dumps(runs), encoding="utf-8")
        (self.root / "step.sh").write_text(script, encoding="utf-8")
        log = self.root / "dispatched.txt"
        log.unlink(missing_ok=True)
        result = subprocess.run(["bash", "-e", str(self.root / "step.sh")], cwd=self.repository,
                                env={**self.environment, **environment}, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        dispatched = log.read_text(encoding="utf-8").splitlines() if log.exists() else []
        return result.stdout + result.stderr, dispatched


def green(sha: str) -> dict:
    return {"headSha": sha, "status": "completed", "conclusion": "success", "event": "push", "branch": "main"}


def run_in(status: str, sha: str = "0" * 40, event: str = "workflow_dispatch") -> dict:
    return {"headSha": sha, "status": status, "conclusion": "", "event": event, "branch": "main"}


TESTFLIGHT = "release.yml --repo owner/ondo-iptv --ref main -f target=builds -f edition=testflight -f external=true"
# After a tag's release: the TestFlight edition again, no external testers (the tag submitted the App Store edition).
TESTFLIGHT_AGAIN = "release.yml --repo owner/ondo-iptv --ref v1.2.0 -f target=builds -f edition=testflight"
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

    def test_a_tag_on_a_newer_commit_that_ships_the_same_app_is_left_to_its_release(self):
        self.commit("first", "App/OndoApp.swift")
        self.commit("change", "App/Model/AppModel.swift")
        self.commit("notes", "docs/RELEASE.md")
        self.git("tag", "v1.2.0")
        output, dispatched = self.deliver("CI", "change", {"ci.yml": [green(self.commits["change"]), green(self.commits["first"])]})
        self.assertEqual(dispatched, [], output)
        self.assertIn("carries a version tag", output)

    def test_a_newer_push_that_changes_the_app_sends_it(self):
        self.commit("first", "App/OndoApp.swift")
        self.commit("change", "App/Model/AppModel.swift")
        self.commit("newer", "App/Model/AppModel.swift")
        output, dispatched = self.deliver("CI", "change", {"ci.yml": [green(self.commits["change"]), green(self.commits["first"])]})
        self.assertEqual(dispatched, [], output)
        self.assertIn("own delivery sends it", output)

    def test_a_newer_push_that_ships_the_same_app_does_not_hold_it_back(self):
        # The workflow skips the newer push (documentation, the other platform) or it changed tests only: no delivery
        # of its own would send this commit's change.
        for source, changed, newer in (("CI", "App/Model/AppModel.swift", "docs/RELEASE.md"),
                                       ("CI", "App/Model/AppModel.swift", "android/app/src/main/Main.kt"),
                                       ("CI", "App/Model/AppModel.swift", "Packages/IPTVCore/Tests/IPTVCoreTests/ParserTests.swift"),
                                       ("Android", "android/core/src/main/Core.kt", "App/Model/AppModel.swift"),
                                       ("Android", "android/core/src/main/Core.kt", "README.md")):
            with self.subTest(source=source, newer=newer):
                self.commit("first", "App/OndoApp.swift", "android/app/src/main/Main.kt")
                self.commit("change", changed)
                self.commit("newer", newer)
                workflow = "ci.yml" if source == "CI" else "android.yml"
                output, dispatched = self.deliver(source, "change", {workflow: [green(self.commits["change"]), green(self.commits["first"])]})
                self.assertEqual(dispatched, [TESTFLIGHT if source == "CI" else INTERNAL_TESTING], output)

    def test_a_waiting_release_is_never_replaced(self):
        self.commit("first", "App/OndoApp.swift")
        self.commit("change", "App/Model/AppModel.swift")
        runs = {"ci.yml": [green(self.commits["change"]), green(self.commits["first"])],
                "release.yml": [run_in("pending", event="push"), run_in("in_progress")]}
        output, dispatched = self.deliver("CI", "change", runs)
        self.assertEqual(dispatched, [], output)
        self.assertIn("already waiting", output)

    def test_a_change_to_android_goes_to_internal_testing(self):
        self.commit("first", "android/app/src/main/Main.kt")
        self.commit("change", "android/core/src/main/Core.kt")
        runs = {"android.yml": [green(self.commits["change"]), green(self.commits["first"])],
                "release-android.yml": [run_in("in_progress")]}
        output, dispatched = self.deliver("Android", "change", runs)
        self.assertEqual(dispatched, [INTERNAL_TESTING], output)

    def test_a_waiting_promotion_or_play_status_is_never_replaced(self):
        # They share the release-android group with Release Android: a new run there would cancel them.
        self.commit("first", "android/app/src/main/Main.kt")
        self.commit("change", "android/core/src/main/Core.kt")
        for workflow in ("release-android.yml", "promote-android.yml", "play-status.yml"):
            with self.subTest(waiting=workflow):
                runs = {"android.yml": [green(self.commits["change"]), green(self.commits["first"])],
                        "release-android.yml": [run_in("in_progress")]}
                runs.setdefault(workflow, []).insert(0, run_in("pending"))
                output, dispatched = self.deliver("Android", "change", runs)
                self.assertEqual(dispatched, [], output)
                self.assertIn("already waiting", output)


class PlayCatchUpQueueTests(WorkflowScriptTestCase):
    """play-catchup.yml: the hourly catch-up never pushes out a waiting run of the release-android group."""

    def queue(self, runs: dict) -> str:
        output_file = self.root / "output.txt"
        output_file.write_text("", encoding="utf-8")
        self.run_script(step_script("play-catchup.yml", "Nothing waiting in the release-android group"), runs,
                        GITHUB_OUTPUT=str(output_file))
        return output_file.read_text(encoding="utf-8")

    def test_a_running_release_leaves_the_queue_free(self):
        # Its own run waits behind it, which pushes out nothing.
        self.assertEqual(self.queue({"release-android.yml": [run_in("in_progress")]}), "free=true\n")

    def test_a_waiting_run_is_never_replaced(self):
        for workflow in ("release-android.yml", "promote-android.yml", "play-status.yml"):
            with self.subTest(waiting=workflow):
                self.assertEqual(self.queue({workflow: [run_in("pending")]}), "free=false\n")


class TestFlightEditionAgainTests(WorkflowScriptTestCase):
    """release.yml: after a tag's release, the TestFlight edition of the tag is built again for the testers."""

    def start(self, runs: dict) -> tuple[str, list]:
        self.commit("version", "App/OndoApp.swift")
        return self.run_script(step_script("release.yml", "Start the TestFlight edition build"), runs,
                               GITHUB_REF_NAME="v1.2.0")

    def test_the_tags_testflight_edition_is_built_again(self):
        output, dispatched = self.start({"release.yml": [run_in("in_progress", event="push")]})
        self.assertEqual(dispatched, [TESTFLIGHT_AGAIN], output)

    def test_a_waiting_release_is_never_replaced(self):
        # A newer tag's release waits for this one: GitHub would cancel it for the run started here.
        output, dispatched = self.start({"release.yml": [run_in("pending", event="push"), run_in("in_progress", event="push")]})
        self.assertEqual(dispatched, [], output)
        self.assertIn("already waiting", output)


def job_block(workflow: str, job: str) -> list:
    """The lines of [job] in .github/workflows/[workflow]."""
    lines = (WORKFLOWS / workflow).read_text(encoding="utf-8").splitlines()
    start = lines.index(f"  {job}:")
    end = next((i for i in range(start + 1, len(lines)) if lines[i].startswith("  ") and not lines[i].startswith("   ")
                and lines[i].rstrip().endswith(":")), len(lines))
    return lines[start:end]


class ReleaseScreenshotsTests(WorkflowScriptTestCase):
    """release.yml: the listing matches the build: no release goes on without the screenshots it captured."""

    def test_a_release_without_its_screenshots_stops_before_anything_is_built(self):
        script = step_script("release.yml", "App Store screenshots captured")
        for result, stops in (("success", False), ("failure", True), ("cancelled", True), ("skipped", True), ("", True)):
            with self.subTest(result=result):
                outcome = subprocess.run(["bash", "-e", "-c", script], env={**self.environment, "CAPTURE": result},
                                         capture_output=True, text=True)
                self.assertEqual(outcome.returncode != 0, stops, outcome.stdout + outcome.stderr)
                if stops:
                    self.assertIn("Re-run the App Store screenshots, then this release", outcome.stdout)

    def test_the_check_covers_every_target_that_uploads_a_build_and_comes_first(self):
        steps = [line.strip()[len("- name: "):] for line in job_block("release.yml", "deliver") if line.strip().startswith("- name: ")]
        check = steps.index("App Store screenshots captured")
        for later in ("Xcode", "iOS → TestFlight", "macOS → TestFlight", "App Store listing"):
            self.assertLess(check, steps.index(later), later)
        block = job_block("release.yml", "deliver")
        condition = block[block.index("      - name: App Store screenshots captured") + 3].strip()
        self.assertEqual(condition, "if: env.TARGET == 'release' || env.TARGET == 'all'")

    def test_the_testflight_edition_follows_the_deliver_jobs_own_result(self):
        # needs: deliver alone would also require the screenshots job (an implicit success() covers every needed job).
        conditions = [line.strip() for line in job_block("release.yml", "testflight") if line.strip().startswith("if:")]
        self.assertIn("needs.deliver.result == 'success'", conditions[0])
        self.assertIn("github.ref_type == 'tag'", conditions[0])
        self.assertIn("!cancelled()", conditions[0])



class ExternalTestersTests(WorkflowScriptTestCase):
    """release.yml: a push's App Store edition goes to the external testers without holding back the TestFlight edition."""

    def steps(self, job: str = "deliver") -> list:
        return [line.strip()[len("- name: "):] for line in job_block("release.yml", job) if line.strip().startswith("- name: ")]

    def test_the_app_store_edition_comes_first_so_internal_testers_keep_the_testflight_edition(self):
        steps = self.steps()
        checks = steps.index("App Store edition checks (external testers)")
        for app_store, testflight in (("iOS App Store edition → TestFlight (external testers)", "iOS → TestFlight"),
                                      ("macOS App Store edition → TestFlight (external testers)", "macOS → TestFlight")):
            self.assertLess(checks, steps.index(app_store))
            self.assertLess(steps.index(app_store), steps.index(testflight))
        block = "\n".join(job_block("release.yml", "deliver"))
        # The TestFlight edition's number comes after the App Store edition's, which App Store Connect may not list yet.
        self.assertIn("AFTER_BUILD_NUMBER: ${{ steps.ios_app_store.outputs.build }}", block)
        self.assertIn("AFTER_BUILD_NUMBER: ${{ steps.mac_app_store.outputs.build }}", block)

    def test_a_failed_external_step_never_stops_the_testflight_edition(self):
        block = job_block("release.yml", "deliver")
        for step in ("App Store edition checks (external testers)", "iOS App Store edition → TestFlight (external testers)",
                     "macOS App Store edition → TestFlight (external testers)"):
            start = block.index(f"      - name: {step}")
            end = next(i for i in range(start + 1, len(block)) if block[i].startswith("      - "))
            self.assertIn("continue-on-error: true", [line.strip() for line in block[start:end]], step)

    def test_a_failed_external_step_turns_the_run_red(self):
        script = step_script("release.yml", "External testers builds")
        for checks, ios, mac, fails in (("success", "success", "success", False), ("failure", "skipped", "skipped", True),
                                        ("success", "failure", "success", True), ("success", "success", "failure", True)):
            with self.subTest(checks=checks, ios=ios, mac=mac):
                outcome = subprocess.run(["bash", "-e", "-c", script], capture_output=True, text=True,
                                         env={**self.environment, "CHECKS": checks, "IOS": ios, "MAC": mac})
                self.assertEqual(outcome.returncode != 0, fails, outcome.stdout + outcome.stderr)

    def test_a_build_without_what_to_test_turns_the_run_red(self):
        script = step_script("release.yml", "What to Test")
        for missing, fails in (("   ", False), (" missing  ", True), ("   missing", True)):
            with self.subTest(missing=missing):
                outcome = subprocess.run(["bash", "-e", "-c", script], capture_output=True, text=True,
                                         env={**self.environment, "MISSING": missing})
                self.assertEqual(outcome.returncode != 0, fails, outcome.stdout + outcome.stderr)
        block = "\n".join(job_block("release.yml", "deliver"))
        for step in ("ios", "mac", "ios_app_store", "mac_app_store"):
            self.assertIn(f"steps.{step}.outputs.what_to_test", block)

    def test_only_a_push_delivery_goes_to_external_testers(self):
        block = "\n".join(job_block("release.yml", "deliver"))
        self.assertIn("EXTERNAL: ${{ github.event.inputs.external == 'true' && github.event.inputs.target == 'builds' && "
                      "github.event.inputs.edition == 'testflight' }}", block)
        # A tag's TestFlight edition again (the tag submitted its App Store edition for App Review) has no external testers.
        self.assertNotIn("external=true", "\n".join(job_block("release.yml", "testflight")))

    def test_the_external_job_runs_on_linux_after_the_builds(self):
        block = "\n".join(job_block("release.yml", "external"))
        self.assertIn("runs-on: ubuntu-24.04", block)
        self.assertIn("needs: deliver", block)
        self.assertEqual(self.steps("external")[-2:], ["iOS → external testers", "macOS → external testers"])

class CorePassedTests(WorkflowScriptTestCase):
    """release.yml: a TestFlight edition run skips the core tests only when CI passed, its macOS jobs run, on the very
    commit it builds or the same tree."""

    def test_only_a_full_pass_on_this_commit_or_tree_counts(self):
        scripts = self.repository / ".github/scripts"
        scripts.mkdir(parents=True)
        shutil.copy(WORKFLOWS.parent / "scripts/ci_scope.py", scripts / "ci_scope.py")
        sha = self.commit("change", "App/OndoApp.swift")
        tree = self.git("rev-parse", "HEAD^{tree}")
        output_file = self.root / "output.txt"
        script = step_script("release.yml", "CI passed on this commit")
        runs_path = "repos/owner/ondo-iptv/actions/workflows/ci.yml/runs?status=success&per_page=100"
        heavy = ["Build iOS + macOS (Debug, App Store edition)", "App tests — unit + UI (iPhone simulator)"]

        def ci_run(head: str, run_tree: str = "0" * 40, event: str = "push") -> dict:
            return {"id": 7, "head_sha": head, "head_commit": {"tree_id": run_tree}, "event": event, "conclusion": "success"}

        def jobs(conclusion: str) -> dict:
            return {"jobs": [{"name": name, "conclusion": conclusion} for name in heavy]}

        jobs_path = "repos/owner/ondo-iptv/actions/runs/7/jobs?filter=latest&per_page=100"
        cases = {"green here": ([ci_run(sha)], jobs("success"), "passed=true\n"),
                 "green on the same tree": ([ci_run("1" * 40, tree, "workflow_dispatch")], jobs("success"), "passed=true\n"),
                 "green on the parent only": ([ci_run("1" * 40)], jobs("success"), ""),
                 "green here with the macOS jobs skipped": ([ci_run(sha)], jobs("skipped"), ""),
                 "a pull request here": ([ci_run(sha, event="pull_request")], jobs("success"), ""),
                 "no run": ([], jobs("success"), "")}
        for name, (runs, job_list, expected) in cases.items():
            with self.subTest(name):
                (self.root / "api.json").write_text(json.dumps({runs_path: {"workflow_runs": runs}, jobs_path: job_list}),
                                                    encoding="utf-8")
                output_file.write_text("", encoding="utf-8")
                self.run_script(script, {}, GITHUB_SHA=sha, GITHUB_OUTPUT=str(output_file))
                self.assertEqual(output_file.read_text(encoding="utf-8"), expected)

    def test_unreadable_runs_run_the_core_tests(self):
        scripts = self.repository / ".github/scripts"
        scripts.mkdir(parents=True)
        shutil.copy(WORKFLOWS.parent / "scripts/ci_scope.py", scripts / "ci_scope.py")
        sha = self.commit("change", "App/OndoApp.swift")
        (self.root / "api.json").write_text("{}", encoding="utf-8")
        output_file = self.root / "output.txt"
        output_file.write_text("", encoding="utf-8")
        self.run_script(step_script("release.yml", "CI passed on this commit"), {}, GITHUB_SHA=sha,
                        GITHUB_OUTPUT=str(output_file))
        self.assertEqual(output_file.read_text(encoding="utf-8"), "")


if __name__ == "__main__":
    unittest.main()
