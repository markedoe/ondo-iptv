"""ship.py sends the next version to review after a green night (ship.yml): every gate, and nothing when unsure.

Run: python3 -m unittest discover -s .github/scripts/tests
"""
import importlib.util
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

SCRIPTS = Path(__file__).resolve().parents[1]
WORKFLOWS = SCRIPTS.parent / "workflows"
spec = importlib.util.spec_from_file_location("ship", SCRIPTS / "ship.py")
ship = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ship)

GIT_ENVIRONMENT = {"GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull, "GIT_AUTHOR_NAME": "ci",
                   "GIT_AUTHOR_EMAIL": "ci@users.noreply.github.com", "GIT_COMMITTER_NAME": "ci",
                   "GIT_COMMITTER_EMAIL": "ci@users.noreply.github.com"}
APPLE_LOCALES = ["en-US", "fr-FR"]
PLAY_LOCALES = ["en-US", "fr-FR"]


def night(sha: str, status: str = "completed", conclusion: str = "success", event: str = "schedule", ident: int = 1) -> dict:
    return {"id": ident, "head_sha": sha, "status": status, "conclusion": conclusion, "event": event, "head_branch": "main"}


def version(platform: str, string: str, store: str, app_version: str = None) -> dict:
    return {"attributes": {"platform": platform, "versionString": string, "appStoreState": store,
                           "appVersionState": app_version or store}}


SETTLED = [version("IOS", "2.0.0", "READY_FOR_SALE", "READY_FOR_DISTRIBUTION"),
           version("MAC_OS", "2.0.0", "READY_FOR_SALE", "READY_FOR_DISTRIBUTION")]


class VersionTests(unittest.TestCase):
    def test_versions_compare_as_numbers(self):
        self.assertTrue(ship.newer("2.0.10", "v2.0.9"))
        self.assertFalse(ship.newer("2.0.9", "v2.0.10"))
        self.assertTrue(ship.newer("2.1", "v2.0.9"))
        self.assertTrue(ship.newer("10.0.0", "v9.9.9"))
        self.assertFalse(ship.newer("2.0.0", "v2.0.0"))
        self.assertFalse(ship.newer("2.0", "v2.0.0"))

    def test_only_versions_the_fastfile_accepts(self):
        for text in ("2", "2.0.0.1", "v2.0.0", "2.0.0-beta", "", "2.0.x"):
            self.assertIsNone(ship.parse_version(text), text)
        self.assertEqual(ship.parse_version("1.2"), (1, 2, 0))

    def test_the_highest_tag_by_version_not_by_name(self):
        self.assertEqual(ship.latest_tag(["v2.0.9", "v2.0.10", "v1.9.0", "other"]), ("v2.0.10", None))

    def test_no_tag_ships_nothing(self):
        tag, why = ship.latest_tag(["other"])
        self.assertIsNone(tag)
        self.assertIn("first release is a human's", why)

    def test_a_v_tag_that_is_not_a_version_ships_nothing(self):
        tag, why = ship.latest_tag(["v2.0.0", "vnext"])
        self.assertIsNone(tag)
        self.assertIn("vnext", why)

    def test_both_apps_carry_the_same_version(self):
        project = 'settings:\n  base:\n    MARKETING_VERSION: "2.0.1"\n'
        gradle = 'versionName = System.getenv("ANDROID_VERSION_NAME") ?: "2.0.1"\n'
        self.assertEqual(ship.apps_version(project, gradle), ("2.0.1", None))
        version_, why = ship.apps_version(project, gradle.replace("2.0.1", "2.0.2"))
        self.assertIsNone(version_)
        self.assertIn("differ", why)
        self.assertIsNone(ship.apps_version("", gradle)[0])
        self.assertIsNone(ship.apps_version(project.replace("2.0.1", "two"), gradle.replace("2.0.1", "two"))[0])

    def test_the_versions_are_read_where_the_release_workflows_read_them(self):
        root = SCRIPTS.parents[1]
        self.assertEqual(ship.apps_version((root / "project.yml").read_text(encoding="utf-8"),
                                           (root / "android/app/build.gradle.kts").read_text(encoding="utf-8"))[1], None)
        for workflow in ("release.yml", "release-android.yml"):
            text = (WORKFLOWS / workflow).read_text(encoding="utf-8")
            self.assertIn("""sed -n 's/^ *MARKETING_VERSION: *"\\([^"]*\\)".*/\\1/p' project.yml""", text)
            self.assertIn("""sed -n 's/.*ANDROID_VERSION_NAME") ?: "\\([^"]*\\)".*/\\1/p' android/app/build.gradle.kts""", text)

    def test_a_version_bump_alone_ships_nothing(self):
        bump = '--- a/project.yml\n+++ b/project.yml\n@@ -18 +18 @@\n-    MARKETING_VERSION: "2.0.0"\n+    MARKETING_VERSION: "2.0.1"\n'
        self.assertTrue(ship.version_only(bump))
        android = '-        versionName = System.getenv("ANDROID_VERSION_NAME") ?: "2.0.0"\n+        versionName = System.getenv("ANDROID_VERSION_NAME") ?: "2.0.1"\n'
        self.assertTrue(ship.version_only(android))
        self.assertFalse(ship.version_only(bump + "+    SWIFT_VERSION: 6\n"))
        self.assertFalse(ship.version_only(""))


class NightTests(unittest.TestCase):
    def test_both_nights_passed_on_the_same_commit(self):
        self.assertEqual(ship.night_sha([night("a" * 40)], [night("a" * 40)]), ("a" * 40, None))

    def test_nights_on_different_commits_ship_nothing(self):
        sha, why = ship.night_sha([night("a" * 40)], [night("b" * 40)])
        self.assertIsNone(sha)
        self.assertIn("different commits", why)

    def test_a_night_running_or_red_ships_nothing(self):
        for run in (night("a" * 40, status="in_progress", conclusion=None), night("a" * 40, conclusion="failure"),
                    night("a" * 40, conclusion="cancelled")):
            with self.subTest(run=run):
                self.assertIsNone(ship.night_sha([run, night("a" * 40, ident=0)], [night("a" * 40)])[0])
                self.assertIsNone(ship.night_sha([night("a" * 40)], [run])[0])

    def test_only_the_latest_scheduled_run_on_main_counts(self):
        # A push's run is not a night; an older green night does not stand for a red newer one.
        self.assertEqual(ship.night_sha([night("c" * 40, event="push"), night("a" * 40)], [night("a" * 40)])[0], "a" * 40)
        self.assertIsNone(ship.night_sha([night("a" * 40, conclusion="failure"), night("b" * 40)], [night("b" * 40)])[0])
        self.assertIsNone(ship.night_sha([], [night("a" * 40)])[0])


class NotesTests(unittest.TestCase):
    def paths(self):
        return ([ship.APPLE_NOTES.format(locale=locale) for locale in APPLE_LOCALES] +
                [ship.PLAY_NOTES.format(locale=locale) for locale in PLAY_LOCALES])

    def test_every_language_rewritten(self):
        texts = {path: "New." for path in self.paths()}
        self.assertEqual(ship.missing_notes(self.paths(), APPLE_LOCALES, PLAY_LOCALES, texts), [])

    def test_one_language_left_out_ships_nothing(self):
        for left_out in self.paths():
            with self.subTest(left_out):
                texts = {path: "New." for path in self.paths()}
                changed = [path for path in self.paths() if path != left_out]
                self.assertEqual(ship.missing_notes(changed, APPLE_LOCALES, PLAY_LOCALES, texts), [left_out])

    def test_emptied_or_absent_notes_ship_nothing(self):
        texts = {path: "New." for path in self.paths()}
        texts[self.paths()[0]] = "  \n"
        texts[self.paths()[-1]] = None
        self.assertEqual(ship.missing_notes(self.paths(), APPLE_LOCALES, PLAY_LOCALES, texts),
                         [self.paths()[0], self.paths()[-1]])

    def test_no_language_ships_nothing(self):
        self.assertTrue(ship.missing_notes([], [], PLAY_LOCALES, {}))


class AppleTests(unittest.TestCase):
    def test_settled_versions_let_apple_go(self):
        self.assertEqual(ship.apple_blockers(SETTLED, [], "2.0.1"), [])
        prepared = SETTLED + [version("IOS", "2.0.1", "PREPARE_FOR_SUBMISSION")]
        self.assertEqual(ship.apple_blockers(prepared, [], "2.0.1"), [])

    def test_a_version_in_flight_or_rejected_blocks_apple(self):
        for state in ("WAITING_FOR_REVIEW", "IN_REVIEW", "PENDING_DEVELOPER_RELEASE", "PROCESSING_FOR_APP_STORE",
                      "REJECTED", "METADATA_REJECTED", "PENDING_APPLE_RELEASE", "WAITING_FOR_EXPORT_COMPLIANCE",
                      "INVALID_BINARY", "ACCEPTED", "READY_FOR_REVIEW", "SOME_NEW_STATE"):
            for platform in ("IOS", "MAC_OS"):
                for field in ("store", "app_version"):
                    with self.subTest(state=state, platform=platform, field=field):
                        flying = version(platform, "1.9.0", state if field == "store" else "READY_FOR_SALE",
                                         state if field == "app_version" else "READY_FOR_DISTRIBUTION")
                        blockers = ship.apple_blockers(SETTLED + [flying], [], "2.0.1")
                        self.assertTrue(any(state in line for line in blockers), blockers)

    def test_a_review_submission_under_way_blocks_apple(self):
        for state in ("WAITING_FOR_REVIEW", "IN_REVIEW", "UNRESOLVED_ISSUES", "CANCELING", "COMPLETING"):
            with self.subTest(state):
                submission = {"attributes": {"platform": "MAC_OS", "state": state}}
                self.assertTrue(ship.apple_blockers(SETTLED, [submission], "2.0.1"))
        done = {"attributes": {"platform": "IOS", "state": "COMPLETE"}}
        self.assertEqual(ship.apple_blockers(SETTLED, [done], "2.0.1"), [])

    def test_this_version_already_released_or_a_newer_one_blocks_apple(self):
        self.assertTrue(ship.apple_blockers(SETTLED, [], "2.0.0"))
        self.assertTrue(ship.apple_blockers(SETTLED + [version("MAC_OS", "2.1.0", "READY_FOR_SALE")], [], "2.0.1"))

    def test_an_unread_or_missing_platform_blocks_apple(self):
        self.assertEqual(ship.apple_blockers(None, [], "2.0.1"), ["App Store Connect could not be read"])
        self.assertEqual(ship.apple_blockers(SETTLED, None, "2.0.1"), ["App Store Connect could not be read"])
        self.assertTrue(ship.apple_blockers(SETTLED[:1], [], "2.0.1"))

    def test_an_api_error_blocks_apple(self):
        # ApiError is a SystemExit: an `except Exception` alone would let it end the run instead of blocking Apple.
        class Refusing:
            def __init__(self, *args):
                pass

            def call(self, method, path, body=None, query=None):
                raise ship.asset_library.ApiError(401, "GET /v1/apps: HTTP 401 NOT_AUTHORIZED")

            def all(self, path, query=None):
                return self.call("GET", path)

            def close(self):
                pass

        environment = {"ASC_KEY_ID": "id", "ASC_ISSUER_ID": "issuer", "ASC_KEY_P8": "-----BEGIN PRIVATE KEY-----\nx"}
        with mock.patch.dict(os.environ, environment), mock.patch.object(ship.asset_library, "Client", Refusing):
            self.assertEqual(ship.read_apple(), (None, None))
        with mock.patch.dict(os.environ, {"ASC_KEY_ID": ""}):
            self.assertEqual(ship.read_apple(), (None, None))

    def test_the_apple_reads_are_get_only(self):
        calls = []

        class Recording:
            def __init__(self, *args):
                pass

            def call(self, method, path, body=None, query=None):
                calls.append(method)
                return {"data": [{"id": "1"}]} if path == "/v1/apps" else {"data": []}

            def all(self, path, query=None):
                calls.append("GET")
                return []

            def close(self):
                pass

        environment = {"ASC_KEY_ID": "id", "ASC_ISSUER_ID": "issuer", "ASC_KEY_P8": "-----BEGIN PRIVATE KEY-----\nx"}
        with mock.patch.dict(os.environ, environment), mock.patch.object(ship.asset_library, "Client", Recording):
            self.assertEqual(ship.read_apple(), ([], []))
        self.assertEqual(set(calls), {"GET"})


class PlatformsTests(unittest.TestCase):
    def test_a_shared_gate_stops_both(self):
        self.assertEqual(ship.platforms_to_ship(["no tag"], [], [])[0], [])

    def test_apple_blocked_android_still_goes(self):
        platforms, lines = ship.platforms_to_ship([], ["IOS 2.0.0: IN_REVIEW"], [])
        self.assertEqual(platforms, ["android"])
        self.assertIn("Apple blocked: IOS 2.0.0: IN_REVIEW", lines)

    def test_both_go(self):
        self.assertEqual(ship.platforms_to_ship([], [], [])[0], ["apple", "android"])
        self.assertEqual(ship.platforms_to_ship([], [], ["x"])[0], ["apple"])

    def test_a_busy_release_queue_holds_everything(self):
        idle = {workflow: [{"status": "completed"}] for names in ship.QUEUES.values() for workflow in names}
        self.assertEqual(ship.busy_queues(idle), [])
        for workflow in idle:
            for status in ("queued", "pending", "waiting", "requested", "in_progress"):
                with self.subTest(workflow=workflow, status=status):
                    self.assertEqual(ship.busy_queues({**idle, workflow: [{"status": status}]}), [workflow])
            self.assertTrue(ship.busy_queues({**idle, workflow: None}))


class PlanTests(unittest.TestCase):
    SHA = "a" * 40

    def test_a_dry_run_does_nothing(self):
        for dry_run in ("true", "", "False", "no", "0"):
            with self.subTest(dry_run=dry_run):
                self.assertEqual(ship.plan(self.SHA, "v2.0.1", ["apple", "android"], "o/r", dry_run, "refs/heads/main"), [])

    def test_only_main_ships(self):
        self.assertEqual(ship.plan(self.SHA, "v2.0.1", ["apple", "android"], "o/r", "false", "refs/heads/work"), [])

    def test_the_tag_first_then_what_a_tag_push_starts(self):
        commands = ship.plan(self.SHA, "v2.0.1", ["apple", "android"], "o/r", "false", "refs/heads/main")
        self.assertEqual([" ".join(command) for command in commands], [
            f"gh api -X POST repos/o/r/git/refs -f ref=refs/tags/v2.0.1 -f sha={self.SHA}",
            "gh workflow run release.yml --repo o/r --ref v2.0.1 -f target=release -f submit=true",
            "gh workflow run release-android.yml --repo o/r --ref v2.0.1 -f track=alpha -f status=completed -f listing=upload",
        ])

    def test_android_only(self):
        commands = ship.plan(self.SHA, "v2.0.1", ["android"], "o/r", "false", "refs/heads/main")
        self.assertEqual([command[3] for command in commands], ["POST", "release-android.yml"])
        self.assertEqual(ship.plan(self.SHA, "v2.0.1", [], "o/r", "false", "refs/heads/main"), [])


class WorkflowTests(unittest.TestCase):
    def test_the_shipped_paths_are_delivers_own(self):
        deliver = (WORKFLOWS / "deliver.yml").read_text(encoding="utf-8")
        for paths in ship.DELIVER_PATHS.values():
            self.assertIn(f'paths="{paths}"', deliver)

    def test_the_dispatched_inputs_are_what_a_tag_push_does(self):
        release = (WORKFLOWS / "release.yml").read_text(encoding="utf-8")
        # A tag push: TARGET release, the App Store edition, SUBMIT true; by hand the same with these inputs.
        self.assertIn("TARGET: ${{ github.event.inputs.target || 'release' }}", release)
        self.assertIn("EDITION: ${{ github.event.inputs.target == 'release' && 'app-store'", release)
        self.assertIn("SUBMIT: ${{ github.event_name == 'push' || github.event.inputs.submit == 'true' }}", release)
        self.assertIn("(github.event.inputs.target == 'release' && github.event.inputs.submit == 'true')", release)
        # The tag's checks run on a tag ref, whatever the event.
        self.assertIn("if: github.ref_type == 'tag'", release)
        testflight = [line.strip() for line in release.splitlines()
                      if "needs.deliver.result == 'success' && github.ref_type == 'tag'" in line]
        self.assertEqual(len(testflight), 1)
        self.assertIn("(github.event.inputs.target == 'release' && github.event.inputs.submit == 'true')", testflight[0])
        android = (WORKFLOWS / "release-android.yml").read_text(encoding="utf-8")
        self.assertIn("TRACK: ${{ github.event.inputs.track || 'alpha' }}", android)
        self.assertIn("RELEASE_STATUS: ${{ github.event.inputs.status || 'completed' }}", android)
        self.assertIn("LISTING: ${{ github.event.inputs.listing || 'upload' }}", android)

    def test_ship_runs_in_a_queue_of_its_own_and_never_on_production(self):
        text = (WORKFLOWS / "ship.yml").read_text(encoding="utf-8")
        self.assertIn("group: ship\n", text)
        self.assertIn("cancel-in-progress: false", text)
        self.assertNotIn("group: release-apple", text)
        self.assertNotIn("group: release-android", text)
        self.assertIn("default: true", text)
        self.assertIn("run: python3 -I .github/scripts/ship.py", text)
        script = (SCRIPTS / "ship.py").read_text(encoding="utf-8")
        self.assertNotIn("production\"", script.replace("ci_scope.production", ""))
        self.assertNotIn("track=production", script)
        # Nothing here submits by itself: release.yml stays the only workflow with the release lanes.
        self.assertNotIn("fastlane", text)

    def test_only_the_literal_false_on_main_is_real(self):
        text = (WORKFLOWS / "ship.yml").read_text(encoding="utf-8")
        self.assertIn("DRY_RUN: ${{ (github.ref == 'refs/heads/main' && (github.event_name == 'schedule' || "
                      "(github.event_name == 'workflow_dispatch' && !inputs.dry_run))) && 'false' || 'true' }}", text)


@unittest.skipUnless(shutil.which("git"), "git is needed")
class EvaluateTests(unittest.TestCase):
    """The whole decision on a real repository: the night's commit, the last tag, what changed in between."""

    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root)
        self.environment = {**os.environ, **GIT_ENVIRONMENT}
        self.git("init", "-q", "-b", "main")
        self.write_version("2.0.0", "2.0.0")
        self.write_notes("first")
        self.write("App/OndoApp.swift", "first")
        self.write("android/app/src/main/Main.kt", "first")
        self.write("fastlane/metadata/review_information/notes.txt", "notes")
        self.released = self.commit("2.0.0")
        self.git("tag", "v2.0.0")
        self.write_version("2.0.1", "2.0.1")
        self.write_notes("second")
        self.write("App/OndoApp.swift", "second")
        self.tested = self.commit("work")
        self.apple = lambda: (SETTLED, [])
        self.runs = {"ci.yml": [night(self.tested)], "android.yml": [night(self.tested)]}
        self.production = {"ci": 0, "android": 0}
        self.queue = {}

    def git(self, *args: str) -> str:
        return subprocess.run(["git", *args], cwd=self.root, env=self.environment, check=True, capture_output=True,
                              text=True).stdout

    def write(self, path: str, text: str):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(text + "\n", encoding="utf-8")

    def write_version(self, apple: str, android: str):
        self.write("project.yml", f'settings:\n  base:\n    MARKETING_VERSION: "{apple}"\n    SWIFT_VERSION: "6"')
        self.write("android/app/build.gradle.kts",
                   f'android {{\n    versionName = System.getenv("ANDROID_VERSION_NAME") ?: "{android}"\n}}')

    def write_notes(self, text: str, skip: str = None):
        for locale in APPLE_LOCALES:
            if locale != skip:
                self.write(ship.APPLE_NOTES.format(locale=locale), f"{text} {locale}")
        for locale in PLAY_LOCALES:
            if f"play-{locale}" != skip:
                self.write(ship.PLAY_NOTES.format(locale=locale), f"{text} {locale}")

    def commit(self, message: str) -> str:
        self.git("add", "-A")
        self.git("commit", "-q", "-m", message)
        return self.git("rev-parse", "HEAD").strip()

    def api(self, path: str) -> dict:
        for workflow, runs in self.runs.items():
            if f"/workflows/{workflow}/runs?event=schedule" in path:
                return {"workflow_runs": runs}
        for workflow in [name for names in ship.QUEUES.values() for name in names]:
            if f"/workflows/{workflow}/runs?" in path:
                if self.queue.get(workflow) == "unreadable":
                    raise RuntimeError("HTTP 502")
                return {"workflow_runs": [{"status": self.queue.get(workflow, "completed")}]}
        raise AssertionError(f"unexpected API call {path}")

    def evaluate(self) -> dict:
        return ship.evaluate("o/r", run_git=self.git, api=self.api, apple=lambda: self.apple(),
                             production=lambda workflow, sha: self.production[workflow] if sha == self.tested else 1)

    def assert_no_ship(self, decision: dict, reason: str):
        self.assertEqual(decision["platforms"], [], decision["lines"])
        self.assertTrue(any(reason in line for line in decision["lines"]), decision["lines"])

    def test_ships_both_platforms(self):
        decision = self.evaluate()
        self.assertEqual((decision["sha"], decision["tag"], decision["platforms"]),
                         (self.tested, "v2.0.1", ["apple", "android"]), decision["lines"])

    def test_head_moved_after_the_night_ships_the_nights_commit(self):
        self.write("App/OndoApp.swift", "after the night")
        head = self.commit("later")
        decision = self.evaluate()
        self.assertEqual(decision["sha"], self.tested)
        self.assertNotEqual(decision["sha"], head)
        self.assertEqual(decision["platforms"], ["apple", "android"])
        commands = ship.plan(decision["sha"], decision["tag"], decision["platforms"], "o/r", "false", "refs/heads/main")
        self.assertIn(f"sha={self.tested}", commands[0])

    def test_nights_on_different_commits_ship_nothing(self):
        self.runs["android.yml"] = [night(self.released)]
        self.assert_no_ship(self.evaluate(), "different commits")

    def test_a_commit_not_on_main_ships_nothing(self):
        self.git("checkout", "-q", "-b", "side", self.released)
        self.write("App/OndoApp.swift", "side")
        side = self.commit("side")
        self.git("checkout", "-q", "main")
        self.runs = {"ci.yml": [night(side)], "android.yml": [night(side)]}
        self.assert_no_ship(self.evaluate(), "not on main")

    def test_long_tests_not_passed_on_the_commit_ship_nothing(self):
        for workflow in ("ci", "android"):
            with self.subTest(workflow):
                self.production = {"ci": 0, "android": 0, workflow: 1}
                self.assert_no_ship(self.evaluate(), f"production {workflow} refused")

    def test_no_tag_ships_nothing(self):
        self.git("tag", "-d", "v2.0.0")
        self.assert_no_ship(self.evaluate(), "no vX.Y.Z tag yet")

    def test_mismatched_versions_ship_nothing(self):
        self.write_version("2.0.1", "2.0.2")
        self.tested = self.commit("mismatch")
        self.runs = {"ci.yml": [night(self.tested)], "android.yml": [night(self.tested)]}
        self.assert_no_ship(self.evaluate(), "differ")

    def test_a_version_not_above_the_last_tag_ships_nothing(self):
        self.git("tag", "v2.0.10", self.released)
        self.assert_no_ship(self.evaluate(), "not above v2.0.10")

    def test_an_existing_tag_ships_nothing(self):
        # A tag of this version is also the highest one: gate b refuses first, gate f stays as a second lock.
        self.git("tag", "v2.0.1", self.released)
        self.assert_no_ship(self.evaluate(), "v2.0.1")

    def test_an_existing_tag_of_this_version_ships_nothing_on_its_own(self):
        # Gate f alone: a tag list that hides the new tag from gate b (a tag not fetched) still finds it by name.
        def run_git(*args):
            if args[:3] == ("tag", "--list", "v*"):
                return "v2.0.0\n"
            if args[:3] == ("tag", "--list", "v2.0.1"):
                return "v2.0.1\n"
            return self.git(*args)
        decision = ship.evaluate("o/r", run_git=run_git, api=self.api, apple=self.apple, production=lambda *_: 0)
        self.assert_no_ship(decision, "tag v2.0.1 already exists")

    def test_notes_missing_in_one_language_ship_nothing(self):
        for skip, path in (("fr-FR", ship.APPLE_NOTES.format(locale="fr-FR")),
                           ("play-en-US", ship.PLAY_NOTES.format(locale="en-US"))):
            with self.subTest(skip):
                self.git("reset", "-q", "--hard", self.released)
                self.write_version("2.0.1", "2.0.1")
                self.write_notes("third", skip=skip)
                self.write("App/OndoApp.swift", "third")
                self.tested = self.commit("work")
                self.runs = {"ci.yml": [night(self.tested)], "android.yml": [night(self.tested)]}
                decision = self.evaluate()
                self.assert_no_ship(decision, path)

    def test_nothing_shipped_changed_ships_nothing(self):
        # The version and the notes alone, or with tests and docs: no new app.
        self.git("reset", "-q", "--hard", self.released)
        self.write_version("2.0.1", "2.0.1")
        self.write_notes("third")
        self.write("Packages/IPTVCore/Tests/CoreTests.swift", "test")
        self.write("android/app/src/test/MainTest.kt", "test")
        self.write("docs/RELEASE.md", "doc")
        self.tested = self.commit("work")
        self.runs = {"ci.yml": [night(self.tested)], "android.yml": [night(self.tested)]}
        self.assert_no_ship(self.evaluate(), "nothing the apps ship changed")

    def test_a_busy_or_unreadable_release_queue_ships_nothing(self):
        for workflow, state in (("release.yml", "pending"), ("release-android.yml", "in_progress"),
                                ("play-catchup.yml", "unreadable")):
            with self.subTest(workflow):
                self.queue = {workflow: state}
                self.assert_no_ship(self.evaluate(), "release queue is busy")

    def test_apple_in_review_ships_android_only(self):
        self.apple = lambda: (SETTLED + [version("IOS", "2.0.0", "WAITING_FOR_REVIEW")], [])
        decision = self.evaluate()
        self.assertEqual(decision["platforms"], ["android"])
        self.assertTrue(any(line.startswith("Apple blocked") for line in decision["lines"]))

    def test_app_store_connect_unreadable_ships_android_only(self):
        self.apple = lambda: (None, None)
        self.assertEqual(self.evaluate()["platforms"], ["android"])

    def test_unreadable_night_runs_ship_nothing(self):
        def failing(path):
            raise RuntimeError("HTTP 502")
        decision = ship.evaluate("o/r", run_git=self.git, api=failing, apple=self.apple, production=lambda *_: 0)
        self.assert_no_ship(decision, "could not be read")


class MainTests(unittest.TestCase):
    """The I/O: a failure after the tag says how to finish by hand; a dry run starts nothing."""

    def decision(self, platforms):
        return {"sha": "a" * 40, "version": "2.0.1", "tag": "v2.0.1", "platforms": platforms, "lines": []}

    def run_main(self, platforms, dry_run, failing_index=None):
        calls = []

        def fake_run(command, capture_output=True, text=True):
            calls.append(command)
            code = 1 if failing_index is not None and len(calls) - 1 == failing_index else 0
            return subprocess.CompletedProcess(command, code, "", "refused" if code else "")

        environment = {"GITHUB_REPOSITORY": "o/r", "DRY_RUN": dry_run, "GITHUB_REF": "refs/heads/main", "GITHUB_STEP_SUMMARY": ""}
        with mock.patch.dict(os.environ, environment), \
                mock.patch.object(ship, "evaluate", lambda repository: self.decision(platforms)), \
                mock.patch.object(ship.subprocess, "run", fake_run), \
                mock.patch("builtins.print") as printed:
            code = ship.main()
        return code, calls, "\n".join(" ".join(str(arg) for arg in call.args) for call in printed.call_args_list)

    def test_a_decision_that_fails_turns_the_run_red_and_starts_nothing(self):
        def failing(repository):
            raise RuntimeError("git missing")
        with mock.patch.dict(os.environ, {"GITHUB_REPOSITORY": "o/r", "DRY_RUN": "false", "GITHUB_REF": "refs/heads/main"}), \
                mock.patch.object(ship, "evaluate", failing), mock.patch.object(ship.subprocess, "run") as run, \
                mock.patch("builtins.print"):
            self.assertEqual(ship.main(), 1)
        run.assert_not_called()

    def test_a_dry_run_starts_nothing(self):
        code, calls, output = self.run_main(["apple", "android"], "true")
        self.assertEqual((code, calls), (0, []))
        self.assertIn("dry run", output)

    def test_a_real_ship_creates_the_tag_then_starts_both(self):
        code, calls, _ = self.run_main(["apple", "android"], "false")
        self.assertEqual(code, 0)
        self.assertEqual([call[:4] for call in calls], [["gh", "api", "-X", "POST"], ["gh", "workflow", "run", "release.yml"],
                                                        ["gh", "workflow", "run", "release-android.yml"]])

    def test_a_tag_that_cannot_be_created_starts_nothing(self):
        code, calls, output = self.run_main(["apple", "android"], "false", failing_index=0)
        self.assertEqual((code, len(calls)), (1, 1))
        self.assertIn("nothing was started", output)

    def test_a_dispatch_that_fails_after_the_tag_says_how_to_finish(self):
        code, calls, output = self.run_main(["apple", "android"], "false", failing_index=1)
        self.assertEqual((code, len(calls)), (1, 2))
        self.assertIn("gh workflow run release-android.yml --repo o/r --ref v2.0.1 -f track=alpha", output)

    def test_android_only_says_who_submits_apple(self):
        code, calls, output = self.run_main(["android"], "false")
        self.assertEqual((code, len(calls)), (0, 2))
        self.assertIn("gh workflow run release.yml --repo o/r --ref v2.0.1 -f target=release -f submit=true", output)


if __name__ == "__main__":
    unittest.main()
