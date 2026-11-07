"""ci_scope.py decides which heavy jobs of ci.yml and android.yml run (docs/CI.md): when unsure, everything.

Run: python3 -m unittest discover -s .github/scripts/tests
"""
import importlib.util
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]
WORKFLOWS = SCRIPTS.parent / "workflows"
spec = importlib.util.spec_from_file_location("ci_scope", SCRIPTS / "ci_scope.py")
ci_scope = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ci_scope)

# The dry-run table of docs/CI.md: a working branch's only change, and whether each workflow's heavy jobs run.
SAMPLES = {
    "Android only": ("android/app/src/main/kotlin/com/markedoe/ondo/Main.kt", False, True),
    "Apple only": ("App/Model/AppModel.swift", True, False),
    "Swift core only": ("Packages/IPTVCore/Sources/IPTVCore/Parser.swift", True, False),
    "Fixtures only": ("fixtures/film-versions/golden.json", True, True),
    "Showcase fixtures only": ("fixtures/showcase/catalogue.json", True, True),
    "Documentation only": ("docs/RELEASE.md", False, False),
    "README only": ("README.fr.md", False, False),
    "Website only": ("site/index.html", False, False),
    "A workflow only": (".github/workflows/ci.yml", True, True),
    "A script only": (".github/scripts/test_summary.py", True, True),
    "Sync spec only": ("docs/SYNC.md", True, True),
    "Profiles spec only": ("docs/PROFILES.fr.md", True, True),
    "Google Play listing only": ("fastlane/metadata-android/en-US/title.txt", False, True),
    "App Store listing only": ("fastlane/metadata/en-US/description.txt", True, False),
    "Fastfile only": ("fastlane/Fastfile", True, True),
    "A new top-level folder": ("shared/new.json", True, True),
    "A Markdown file inside the app": ("App/Resources/NOTES.md", True, False),
}

GIT_ENVIRONMENT = {"GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull, "GIT_AUTHOR_NAME": "ci",
                   "GIT_AUTHOR_EMAIL": "ci@users.noreply.github.com", "GIT_COMMITTER_NAME": "ci",
                   "GIT_COMMITTER_EMAIL": "ci@users.noreply.github.com"}


def trigger_paths(workflow: str, event: str) -> list:
    """The `paths:` list of [event] in .github/workflows/[workflow], as written."""
    lines = (WORKFLOWS / workflow).read_text(encoding="utf-8").splitlines()
    start = lines.index(f"  {event}:")
    paths = next(i for i in range(start + 1, len(lines)) if lines[i].strip() == "paths:")
    found = []
    for line in lines[paths + 1:]:
        if not line.startswith("      - "):
            break
        found.append(line.strip()[2:].strip("'"))
    return found


class PathFilterTests(unittest.TestCase):
    def test_each_workflow_filters_with_the_scripts_list(self):
        # The push and pull_request filters and the run by hand must agree, or one would skip what another runs.
        for workflow, file in ci_scope.WORKFLOW_FILES.items():
            for event in ("push", "pull_request"):
                with self.subTest(workflow=file, event=event):
                    self.assertEqual(trigger_paths(file, event), ci_scope.PATHS[workflow])

    def test_the_jobs_named_are_the_workflows_jobs(self):
        # A renamed job would silently turn the duplicate skip and Release's core test skip off.
        for workflow, file in ci_scope.WORKFLOW_FILES.items():
            names = {line.strip()[len("name: "):] for line in (WORKFLOWS / file).read_text(encoding="utf-8").splitlines()
                     if line.startswith("    name: ")}
            for job in ci_scope.HEAVY_JOBS[workflow] + ci_scope.NOT_RUN_JOBS[workflow]:
                with self.subTest(workflow=file, job=job):
                    self.assertIn(job, names)

    def test_every_job_gated_on_what_to_run_proves_the_run(self):
        # A heavy job left out of HEAVY_JOBS could fail or be skipped while the duplicate skip still trusts the run.
        for workflow, file in ci_scope.WORKFLOW_FILES.items():
            gated, name = [], None
            for line in (WORKFLOWS / file).read_text(encoding="utf-8").splitlines():
                if line.startswith("  ") and not line.startswith("   ") and line.rstrip().endswith(":"):
                    name = None
                elif line.startswith("    name: "):
                    name = line.strip()[len("name: "):]
                elif line.startswith("    if: ") and "needs.scope.outputs.run" in line:
                    gated.append(name)
            with self.subTest(workflow=file):
                self.assertTrue(gated)
                self.assertEqual(sorted(gated), sorted(ci_scope.HEAVY_JOBS[workflow]))

    def test_the_samples(self):
        for name, (path, apple, android) in SAMPLES.items():
            with self.subTest(name):
                self.assertEqual(ci_scope.relevant(path, ci_scope.PATHS["ci"]), apple, path)
                self.assertEqual(ci_scope.relevant(path, ci_scope.PATHS["android"]), android, path)

    def test_shared_folders_always_run_both(self):
        # Read by both cores or both apps: a new shared folder belongs here and in neither list's exclusions.
        for path in ("fixtures/sync/merge.json", "fixtures/film-versions/golden.json", "fixtures/showcase/art/a.jpg",
                     ".github/scripts/ci_scope.py", ".github/workflows/android.yml", "docs/SYNC.fr.md", "docs/PROFILES.md",
                     "Gemfile", "Gemfile.lock", "fastlane/Fastfile", "funding/stripe.json", "tools/awards/generate_awards.py",
                     ".claude/hooks/leak_guard.py", "LICENSE"):
            with self.subTest(path):
                self.assertTrue(ci_scope.relevant(path, ci_scope.PATHS["ci"]))
                self.assertTrue(ci_scope.relevant(path, ci_scope.PATHS["android"]))

    def test_github_pattern_syntax(self):
        self.assertTrue(ci_scope.relevant("a/b/c.txt", ["**"]))
        self.assertTrue(ci_scope.relevant(".github/x.yml", ["**"]))
        self.assertFalse(ci_scope.relevant("a/README.md", ["!*.md"]))
        self.assertFalse(ci_scope.relevant("README.md", ["**", "!*.md"]))
        self.assertTrue(ci_scope.relevant("a/README.md", ["**", "!*.md"]))
        self.assertTrue(ci_scope.relevant("docs/SYNC.md", ["**", "!docs/**", "docs/SYNC*"]))


def api_from(responses: dict):
    def api(path):
        if path not in responses:
            raise RuntimeError(f"unexpected call {path}")
        return responses[path]
    return api


def run(run_id: int, sha: str, tree: str = "t0", event: str = "push") -> dict:
    return {"id": run_id, "head_sha": sha, "head_commit": {"tree_id": tree}, "event": event, "conclusion": "success"}


def jobs(*names: str, conclusion: str = "success", **others: str) -> dict:
    return {"jobs": [{"name": name, "conclusion": conclusion} for name in names] +
                    [{"name": name, "conclusion": value} for name, value in others.items()]}


RUNS = "repos/o/r/actions/workflows/{}/runs?status=success&per_page=100"
JOBS = "repos/o/r/actions/runs/{}/jobs?filter=latest&per_page=100"
CI_JOBS = ci_scope.HEAVY_JOBS["ci"]
ANDROID_JOBS = ci_scope.HEAVY_JOBS["android"]


class ShortRunTests(unittest.TestCase):
    """A push or a pull request runs the short jobs only, on both platforms: the testers' builds leave after them."""

    def test_a_push_or_a_pull_request_never_runs_the_long_jobs(self):
        # Even with no earlier run at all: the long jobs are the night's (and a run by hand's).
        for workflow in ci_scope.WORKFLOW_FILES:
            for event, ref in (("push", "refs/heads/main"), ("pull_request", "refs/pull/3/merge"), ("push", "refs/heads/other")):
                with self.subTest(workflow=workflow, event=event, ref=ref):
                    run_long, why = ci_scope.decide(workflow, event, ref, "s1", api=api_from({}))
                    self.assertFalse(run_long, why)
                    self.assertIn("at night", why)

    def test_every_workflow_runs_at_night_and_by_hand(self):
        # A long job with no night would never run, and nothing could reach production.
        for file in ci_scope.WORKFLOW_FILES.values():
            text = (WORKFLOWS / file).read_text(encoding="utf-8")
            with self.subTest(file):
                self.assertRegex(text, r"\n  schedule:\n    - cron: '[1-9]\d? [0-5] \* \* \*'\n")
                self.assertIn("\n  workflow_dispatch:", text)

    def test_the_night_never_cancels_a_push_and_a_push_never_cancels_the_night(self):
        # A cancelled push run would never reach Deliver; a cancelled night would leave the commit unproven.
        for file in ci_scope.WORKFLOW_FILES.values():
            lines = (WORKFLOWS / file).read_text(encoding="utf-8").splitlines()
            group = next(line for line in lines if line.strip().startswith("group:"))
            with self.subTest(file):
                self.assertIn("github.event_name == 'schedule' || github.event_name == 'workflow_dispatch'", group)
                self.assertIn("'long' || 'short'", group)


class NightTests(unittest.TestCase):
    """The night's run on main: its long jobs run unless main's commit or tree already passed with them run."""

    def decide(self, workflow, responses, sha="s1", tree="t1", run_id=99):
        return ci_scope.decide(workflow, "schedule", "refs/heads/main", sha, tree=tree, api=api_from(responses),
                               repository="o/r", run_id=run_id)

    def test_main_unchanged_since_a_green_night_skips(self):
        responses = {RUNS.format("ci.yml"): {"workflow_runs": [run(5, "s1", event="schedule")]},
                     JOBS.format(5): jobs(*CI_JOBS)}
        run_long, why = self.decide("ci", responses)
        self.assertFalse(run_long, why)
        self.assertIn("run 5", why)

    def test_the_same_commit_already_passed_by_hand_skips(self):
        responses = {RUNS.format("ci.yml"): {"workflow_runs": [run(5, "s1", event="workflow_dispatch")]},
                     JOBS.format(5): jobs(*CI_JOBS)}
        self.assertFalse(self.decide("ci", responses)[0])

    def test_the_same_tree_already_passed(self):
        responses = {RUNS.format("android.yml"): {"workflow_runs": [run(6, "other", tree="t1", event="schedule")]},
                     JOBS.format(6): jobs(*ANDROID_JOBS, **{"Commit the reference screenshots to the branch": "skipped"})}
        self.assertFalse(self.decide("android", responses)[0])

    def test_a_green_push_does_not_count(self):
        # A push runs the short jobs only: its long jobs show as skipped.
        for workflow, file, names in (("ci", "ci.yml", CI_JOBS), ("android", "android.yml", ANDROID_JOBS)):
            with self.subTest(workflow):
                responses = {RUNS.format(file): {"workflow_runs": [run(5, "s1")]}, JOBS.format(5): jobs(*names, conclusion="skipped")}
                self.assertTrue(self.decide(workflow, responses)[0])

    def test_a_failed_night_runs_again(self):
        # The listing asks for green runs only; a failed or missing long job does not count either.
        for listed in (jobs(CI_JOBS[0]), jobs(*CI_JOBS, conclusion="failure"), {"jobs": []}):
            with self.subTest(listed=listed):
                responses = {RUNS.format("ci.yml"): {"workflow_runs": [run(5, "s1", event="schedule")]}, JOBS.format(5): listed}
                self.assertTrue(self.decide("ci", responses)[0])

    def test_a_run_that_recorded_the_reference_screenshots_does_not_count(self):
        responses = {RUNS.format("android.yml"): {"workflow_runs": [run(6, "s1", event="workflow_dispatch")]},
                     JOBS.format(6): jobs(*ANDROID_JOBS, **{"Commit the reference screenshots to the branch": "success"})}
        self.assertTrue(self.decide("android", responses)[0])

    def test_a_pull_request_run_does_not_count(self):
        # It tests a merge commit, not the commit it names.
        responses = {RUNS.format("ci.yml"): {"workflow_runs": [run(5, "s1", event="pull_request")]}}
        self.assertTrue(self.decide("ci", responses)[0])

    def test_main_changed_runs(self):
        responses = {RUNS.format("ci.yml"): {"workflow_runs": [run(5, "s0", tree="t0", event="schedule")]}}
        self.assertTrue(self.decide("ci", responses)[0])

    def test_the_run_itself_does_not_count(self):
        responses = {RUNS.format("ci.yml"): {"workflow_runs": [run(99, "s1", event="schedule")]}}
        self.assertTrue(self.decide("ci", responses, run_id=99)[0])

    def test_unreadable_runs_run_everything(self):
        run_long, why = self.decide("ci", {})
        self.assertTrue(run_long)
        self.assertIn("could not be read", why)

    def test_runs_by_hand_on_main_or_a_tag_run_everything(self):
        for ref in ("refs/heads/main", "refs/tags/v1.2.0"):
            with self.subTest(ref=ref):
                self.assertTrue(ci_scope.decide("ci", "workflow_dispatch", ref, "s1", api=api_from({}))[0])


EXACT = "repos/o/r/actions/workflows/{}/runs?status=success&head_sha={}&per_page=100"


class ProductionTests(unittest.TestCase):
    """App Review and Google Play production get only a commit whose long jobs passed on it, at night or by hand."""

    def production(self, workflow, responses, sha="s1"):
        return ci_scope.production(workflow, sha, "o/r", api_from(responses))

    def test_a_night_or_a_run_by_hand_on_this_commit_allows_it(self):
        for workflow, file, names in (("ci", "ci.yml", CI_JOBS), ("android", "android.yml", ANDROID_JOBS)):
            for event in ("schedule", "workflow_dispatch"):
                with self.subTest(workflow=workflow, event=event):
                    responses = {EXACT.format(file, "s1"): {"workflow_runs": [run(5, "s1", event=event)]},
                                 JOBS.format(5): jobs(*names)}
                    self.assertEqual(self.production(workflow, responses), 0)

    def test_anything_else_refuses(self):
        cases = {
            "a push's run (short jobs only)": ([run(5, "s1")], jobs(*CI_JOBS, conclusion="skipped")),
            "long jobs failed": ([run(5, "s1", event="schedule")], jobs(*CI_JOBS, conclusion="failure")),
            "another commit with the same tree": ([run(5, "s0", tree="t1", event="schedule")], jobs(*CI_JOBS)),
            "a pull request": ([run(5, "s1", event="pull_request")], jobs(*CI_JOBS)),
            "no run": ([], jobs(*CI_JOBS)),
        }
        for name, (runs, listed) in cases.items():
            with self.subTest(name):
                responses = {EXACT.format("ci.yml", "s1"): {"workflow_runs": runs}, JOBS.format(5): listed}
                self.assertEqual(self.production("ci", responses), 1)

    def test_unreadable_runs_refuse(self):
        # The opposite of the night's rule: when unsure, nothing is published.
        self.assertEqual(self.production("ci", {}), 1)
        self.assertEqual(self.production("android", {}), 1)


@unittest.skipUnless(shutil.which("git"), "git is needed")
class DispatchTests(unittest.TestCase):
    """A run by hand on a working branch, end to end: the script in a temporary repository, as the workflow runs it."""

    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root)
        self.outputs = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.outputs)
        self.environment = {**os.environ, **GIT_ENVIRONMENT}
        self.git("init", "-q", "-b", "main")
        self.write("App/OndoApp.swift", "android/build.gradle.kts", "docs/RELEASE.md")
        self.git("commit", "-q", "-m", "first")
        self.git("update-ref", "refs/remotes/origin/main", "HEAD")
        self.git("checkout", "-q", "-b", "work")

    def git(self, *args: str) -> str:
        return subprocess.run(["git", *args], cwd=self.root, env=self.environment, check=True,
                              capture_output=True, text=True).stdout.strip()

    def write(self, *paths: str) -> None:
        for path in paths:
            file = self.root / path
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text(f"{path} {len(list(self.root.rglob('*')))}\n", encoding="utf-8")
            self.git("add", path)

    def scope(self, workflow: str, **environment: str) -> str:
        output = self.outputs / "output.txt"
        output.write_text("", encoding="utf-8")
        env = {**self.environment, "GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_REF": "refs/heads/work",
               "GITHUB_SHA": self.git("rev-parse", "HEAD"), "GITHUB_OUTPUT": str(output), "GITHUB_STEP_SUMMARY": "",
               **environment}
        result = subprocess.run([sys.executable, "-I", str(SCRIPTS / "ci_scope.py"), "scope", workflow], cwd=self.root,
                                env=env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return output.read_text(encoding="utf-8")

    def test_the_samples_end_to_end(self):
        for name, (path, apple, android) in SAMPLES.items():
            with self.subTest(name):
                self.git("checkout", "-q", "-B", "work", "origin/main")
                self.write(path)
                self.git("commit", "-q", "-m", name)
                self.assertEqual(self.scope("ci"), f"run={'true' if apple else 'false'}\n")
                self.assertEqual(self.scope("android"), f"run={'true' if android else 'false'}\n")

    def test_the_whole_branch_counts_not_its_last_commit(self):
        self.write("App/Model/AppModel.swift")
        self.git("commit", "-q", "-m", "apple")
        self.write("docs/TIPS.md")
        self.git("commit", "-q", "-m", "docs")
        self.assertEqual(self.scope("ci"), "run=true\n")

    def test_main_merged_into_the_branch_is_not_the_branchs_change(self):
        self.git("checkout", "-q", "main")
        self.write("App/Model/AppModel.swift")
        self.git("commit", "-q", "-m", "apple on main")
        self.git("update-ref", "refs/remotes/origin/main", "HEAD")
        self.git("checkout", "-q", "work")
        self.write("android/app/Main.kt")
        self.git("commit", "-q", "-m", "android")
        self.git("merge", "-q", "--no-edit", "main")
        self.assertEqual(self.scope("ci"), "run=false\n")

    def test_a_rename_out_of_the_app_runs_it(self):
        (self.root / "docs").mkdir(exist_ok=True)
        self.git("mv", "App/OndoApp.swift", "docs/OndoApp.swift")
        self.git("commit", "-q", "-m", "moved")
        self.assertEqual(self.scope("ci"), "run=true\n")

    def test_when_unsure_everything_runs(self):
        self.assertEqual(self.scope("ci"), "run=true\n")  # no change since main
        self.git("update-ref", "-d", "refs/remotes/origin/main")
        self.write("android/app/Main.kt")
        self.git("commit", "-q", "-m", "android")
        self.assertEqual(self.scope("ci"), "run=true\n")  # no main to compare with

    def test_recording_the_reference_screenshots_runs_android(self):
        self.write("docs/TIPS.md")
        self.git("commit", "-q", "-m", "docs")
        self.assertEqual(self.scope("android", RECORD="true"), "run=true\n")
        self.assertEqual(self.scope("android", RECORD="false"), "run=false\n")


if __name__ == "__main__":
    unittest.main()
