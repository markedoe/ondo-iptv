"""Play catch-up (play_catchup.py): closed testing gets the newest internal build once, and a review in progress makes
the promotion wait quietly instead of failing or restarting it.

Run: python3 -m unittest discover -s .github/scripts/tests
"""
import importlib.util
import os
import tempfile
import unittest
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "play_catchup.py"
spec = importlib.util.spec_from_file_location("play_catchup", SCRIPT)
play_catchup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(play_catchup)


def tracks(internal=(), alpha=()):
    """Tracks as the API lists them; each release is (version code, status)."""
    def track(name, releases):
        return {"track": name, "releases": [{"versionCodes": [str(code)], "status": status} for code, status in releases]}
    return [track("internal", internal), track("alpha", alpha), track("production", [])]


class FakeClient:
    """Answers the edit calls and records them."""

    def __init__(self, listed):
        self.listed, self.calls = listed, []

    def call(self, method, url, params=None, body=None):
        self.calls.append((method, url.rsplit("/applications/", 1)[1]))
        if method == "POST":
            return {"id": "e1"}
        if url.endswith("/tracks"):
            return {"tracks": self.listed}
        return {}


class DecideTest(unittest.TestCase):
    def test_a_newer_internal_build_is_promoted(self):
        self.assertEqual(play_catchup.decide(tracks([(1060, "completed")], [(1054, "completed")])), 1060)

    def test_the_same_build_is_left_alone(self):
        self.assertIsNone(play_catchup.decide(tracks([(1054, "completed")], [(1054, "completed")])))

    def test_closed_testing_ahead_is_left_alone(self):
        # A tag's build goes straight to closed testing.
        self.assertIsNone(play_catchup.decide(tracks([(1054, "completed")], [(1061, "completed")])))

    def test_a_draft_in_closed_testing_counts_as_taken(self):
        self.assertIsNone(play_catchup.decide(tracks([(1060, "completed")], [(1060, "draft")])))

    def test_an_internal_draft_has_reached_no_one(self):
        self.assertEqual(play_catchup.decide(tracks([(1058, "completed"), (1060, "draft")], [(1054, "completed")])), 1058)

    def test_empty_closed_testing_gets_the_internal_build(self):
        self.assertEqual(play_catchup.decide(tracks([(1060, "completed")])), 1060)

    def test_no_internal_build_sends_nothing(self):
        self.assertIsNone(play_catchup.decide(tracks((), [(1054, "completed")])))


class ReviewTest(unittest.TestCase):
    def outcome(self, log):
        with tempfile.NamedTemporaryFile("w", suffix=".log", delete=False, encoding="utf-8") as file:
            file.write(log)
        self.addCleanup(os.unlink, file.name)
        output = StringIO()
        with redirect_stdout(output):
            status = play_catchup.main_outcome(file.name)
        return status, output.getvalue()

    def test_a_review_in_progress_waits_quietly(self):
        status, output = self.outcome("[!] Google Api Error: Invalid request - Changes are currently in review and "
                                      "changesInReviewBehavior is ERROR_IF_IN_REVIEW.\n")
        self.assertEqual(status, 0)
        self.assertIn("::notice::", output)
        self.assertIn("closed testing will catch up after Google's review", output)

    def test_any_other_refusal_fails(self):
        status, output = self.outcome("[!] Google Api Error: Invalid request - Changes cannot be sent for review "
                                      "automatically. Please set the query parameter changesNotSentForReview to true.\n")
        self.assertEqual(status, 1)
        self.assertIn("::error::", output)

    def test_a_missing_language_fails(self):
        status, _ = self.outcome("[!] Google Api Error: Invalid request - Language hi-IN is not supported by the app.\n")
        self.assertEqual(status, 1)


class ReadTest(unittest.TestCase):
    def test_the_edit_is_deleted_never_committed(self):
        client = FakeClient(tracks([(1054, "completed")], [(1054, "completed")]))
        self.assertEqual(play_catchup.decide(play_catchup.read_tracks(client)), None)
        methods = [method for method, _ in client.calls]
        self.assertEqual(methods, ["POST", "GET", "DELETE"])
        self.assertFalse(any(path.endswith(":commit") for _, path in client.calls))


if __name__ == "__main__":
    unittest.main()
