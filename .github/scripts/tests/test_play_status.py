"""Google Play status (play_status.py): the token's signature checks out with openssl, the edit is always deleted and
never committed, and nothing private reaches the public log.

Run: python3 -m unittest discover -s .github/scripts/tests
"""
import base64
import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "play_status.py"
spec = importlib.util.spec_from_file_location("play_status", SCRIPT)
play_status = importlib.util.module_from_spec(spec)
spec.loader.exec_module(play_status)


def decode(part: str) -> dict:
    return json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))


class TokenTest(unittest.TestCase):
    def test_the_assertion_is_signed_with_the_account_key(self):
        with tempfile.TemporaryDirectory() as folder:
            key, public = Path(folder) / "key.pem", Path(folder) / "public.pem"
            subprocess.run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", str(key)],
                           check=True, capture_output=True)
            subprocess.run(["openssl", "pkey", "-in", str(key), "-pubout", "-out", str(public)], check=True, capture_output=True)
            account = {"client_email": "reader@example.iam.gserviceaccount.com", "private_key": key.read_text()}  # leak-guard: allow
            jwt = play_status.assertion(account, play_status.SCOPES, 1_700_000_000, play_status.openssl_signer(account["private_key"]))
            header, claims, signature = jwt.split(".")
            self.assertEqual(decode(header), {"alg": "RS256", "typ": "JWT"})
            self.assertEqual(decode(claims)["aud"], play_status.TOKEN_URI)
            self.assertEqual(decode(claims)["exp"] - decode(claims)["iat"], 3600)
            self.assertIn("androidpublisher", decode(claims)["scope"])
            (Path(folder) / "input").write_bytes(f"{header}.{claims}".encode())
            (Path(folder) / "signature").write_bytes(base64.urlsafe_b64decode(signature + "=" * (-len(signature) % 4)))
            verified = subprocess.run(
                ["openssl", "dgst", "-sha256", "-verify", str(public), "-signature", str(Path(folder) / "signature"),
                 str(Path(folder) / "input")], capture_output=True, text=True,
            )
            self.assertIn("Verified OK", verified.stdout)
            # The key file lived only for the signature.
            self.assertEqual(sorted(path.name for path in Path(folder).iterdir()), ["input", "key.pem", "public.pem", "signature"])


class SafetyTest(unittest.TestCase):
    def test_the_log_shows_no_private_data(self):
        line = play_status.safe(
            "account reader@project.iam.gserviceaccount.com, project 123456789012, "  # leak-guard: allow
            "http://user:secret@panel.example.com:8080/live/user/secret/1.ts, 192.0.2.10, 2001:db8::1:2, "  # leak-guard: allow
            "get.php?username=alice&password=wonderland",  # leak-guard: allow
        )
        for private in ("reader@", "123456789012", "panel.example.com", "secret", "192.0.2.10", "2001:db8", "alice", "wonderland"):
            self.assertNotIn(private, line)

    def test_ordinary_details_stay(self):
        self.assertEqual(play_status.safe("internal: versionCodes 1004, completed, 2026-10-23T07:26:13Z"),
                         "internal: versionCodes 1004, completed, 2026-10-23T07:26:13Z")

    def test_crash_reports_keep_frames_not_messages(self):
        lines = play_status.stack_lines(
            "java.io.FileNotFoundException: http://user:pw@panel.example.com/get.php?username=u&password=p\n"  # leak-guard: allow
            "\tat com.markedoe.ondo.data.Loader.load(Loader.kt:42)\n"
            "Caused by: java.net.UnknownHostException: panel.example.com\n"
            "\tat okhttp3.Dns.lookup(Dns.kt:10)\n",
        )
        self.assertEqual(lines, [
            "  java.io.FileNotFoundException",
            "    at com.markedoe.ondo.data.Loader.load(Loader.kt:42)",
            "  Caused by: java.net.UnknownHostException",
            "    at okhttp3.Dns.lookup(Dns.kt:10)",
        ])

    def test_errors_show_their_reasons_not_google_messages(self):
        error = play_status.ApiError.from_body(403, json.dumps({"error": {
            "code": 403, "status": "PERMISSION_DENIED",
            "message": "API has not been used in project 123456789012; reader@project.iam.gserviceaccount.com",  # leak-guard: allow
            "details": [{"@type": "type.googleapis.com/google.rpc.ErrorInfo", "reason": "SERVICE_DISABLED"}],
        }}).encode())
        self.assertEqual(str(error), "HTTP 403 PERMISSION_DENIED SERVICE_DISABLED")
        self.assertEqual(str(play_status.ApiError.from_body(400, b'{"error": "invalid_grant"}')), "HTTP 400 invalid_grant")


class FakePlay:
    """Answers the reads; records every call."""

    def __init__(self, fail=None):
        self.calls, self.fail = [], fail

    def call(self, method, url, params=None, body=None):
        self.calls.append((method, url))
        path = url.split("/applications/com.markedoe.ondo", 1)[-1]
        if self.fail and self.fail in path:
            raise play_status.ApiError(403, "PERMISSION_DENIED", ["forbidden"])
        if method == "POST" and path == "/edits":
            return {"id": "e1"}
        answers = {
            "/edits/e1/details": {"defaultLanguage": "en-US", "contactEmail": "someone@example.com"},
            "/edits/e1/tracks": {"tracks": [{"track": "internal", "releases": [
                {"name": "1004 (1.1.0)", "versionCodes": ["1004"], "status": "completed",
                 "releaseNotes": [{"language": "fr-FR"}, {"language": "en-US"}]}]}]},
            "/edits/e1/bundles": {"bundles": [{"versionCode": 1003}, {"versionCode": 1004}]},
            "/edits/e1/listings": {"listings": [{"language": "en-US", "title": "Ondo", "shortDescription": "x", "fullDescription": "y"}]},
            "/edits/e1/listings/en-US/tvScreenshots": {"images": [{"id": "1"}, {"id": "2"}]},
            "/edits/e1/testers/internal": {"googleGroups": ["testers@googlegroups.com"]},  # leak-guard: allow
            "/edits/e1/countryAvailability/internal": {"countries": [{"countryCode": "FR"}], "restOfWorld": True},
        }
        return answers.get(path, {})


class PublisherTest(unittest.TestCase):
    def test_reads_then_deletes_the_edit_and_never_commits(self):
        play, report = FakePlay(), play_status.Report()
        codes = play_status.publisher(play, report)
        self.assertEqual(codes, [1004])
        self.assertEqual(play.calls[-1], ("DELETE", play_status.PUBLISHER + "com.markedoe.ondo/edits/e1"))
        self.assertFalse(any("commit" in url or "validate" in url for _, url in play.calls))
        text = "\n".join(report.lines)
        self.assertIn("1004 (1.1.0): versionCodes 1004, completed, notes en-US/fr-FR", text)
        self.assertIn("Bundles held by Play: 1003, 1004", text)
        self.assertIn("tvScreenshots 2", text)
        self.assertIn("Testers of internal: 1 Google Group(s)", text)
        self.assertIn("Countries of internal: 1, rest of the world", text)
        self.assertIn("contact e-mail set", text)
        self.assertNotIn("@", text)

    def test_a_refused_read_is_reported_and_the_edit_still_deleted(self):
        play, report = FakePlay(fail="/tracks"), play_status.Report()
        play_status.publisher(play, report)
        self.assertEqual(play.calls[-1][0], "DELETE")
        self.assertIn("Tracks: unavailable (HTTP 403 PERMISSION_DENIED forbidden)", report.lines)


if __name__ == "__main__":
    unittest.main()
