"""The leak guard (.claude/hooks/leak_guard.py) must catch the usual shapes of a secret and every commit a push can
send, and leave alone the placeholders the repository's code, tests and docs use.

The made-up secrets are put together at run time, so that neither the guard nor gitleaks finds them in this file.
Run: python3 -m unittest discover -s .github/scripts/tests
"""
import base64
import importlib.util
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPOSITORY = Path(__file__).resolve().parents[3]
_spec = importlib.util.spec_from_file_location("leak_guard", REPOSITORY / ".claude/hooks/leak_guard.py")
leak_guard = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(leak_guard)

RANDOM = "Zq8vLm2pXr" + "T4nB7wQe1s"  # 20 letters and digits, as a generated password looks
SHORT = RANDOM[:10]
PANEL = "panel.provider" + ".tv:8080"  # a real-looking IPTV panel, not a reserved example name
PEM_BEGIN = "-----BEGIN " + "PRIVATE KEY-----"
LOCAL_ADDRESS = "192." + "168.1.9"


def rules(text: str) -> list:
    return [rule for _, rule, _ in leak_guard.scan_text(text, "sample", [])]


class SecretShapesTests(unittest.TestCase):
    def assertFinds(self, text: str, rule: str):
        self.assertIn(rule, rules(text), text)

    def assertClean(self, text: str):
        self.assertEqual(rules(text), [], text)

    def test_a_password_given_to_a_longer_name(self):
        self.assertFinds(f'storePassword = "{RANDOM}"', "hard-coded secret")
        self.assertFinds(f'ANDROID_KEYSTORE_PASSWORD="{RANDOM}"', "hard-coded secret")
        self.assertFinds(f'"client_secret": "{RANDOM}"', "hard-coded secret")

    def test_a_token_in_code(self):
        self.assertFinds(f'let tmdbToken = "{RANDOM}"', "hard-coded secret")
        self.assertFinds(f'let tmdbToken: String = "{RANDOM}"', "hard-coded secret")

    def test_an_unquoted_value(self):
        self.assertFinds(f"password: {RANDOM}", "hard-coded secret")
        self.assertFinds(f"  password: {SHORT}\n", "hard-coded secret")
        self.assertFinds(f"export PLAY_TOKEN={RANDOM}", "hard-coded secret")

    def test_an_xtream_account_in_an_address(self):
        self.assertFinds(f"http://{PANEL}/get.php?username=jdoe42&password={SHORT}&type=m3u_plus", "credentials in a URL")
        self.assertFinds(f"/get.php?username=jdoe42&password={SHORT}&type=m3u_plus", "credentials in a URL")
        self.assertFinds(f"http://{PANEL}/live/jdoe42/{SHORT}/1234.ts", "credentials in a URL")
        self.assertFinds(f"http://{PANEL}/timeshift/jdoe42/{SHORT}/60/2025-10-09:20-00/1234.ts", "credentials in a URL")

    def test_a_private_key_in_a_json_service_account(self):
        body = "MIIEvQIBADANBgkqhkiG9w0BAQEFAASC" * 2
        self.assertFinds('{"type": "service_account", "private_key": "' + PEM_BEGIN + "\\n" + body + '\\n"}', "private key")
        self.assertFinds(PEM_BEGIN + "\n" + body + "\n", "private key")

    def test_a_release_key_or_keystore_in_base64(self):
        # docs/RELEASE.md takes the .p8 or its base64; docs/RELEASE-ANDROID.md puts the .jks in a secret as base64.
        p8_body = "MIGTAgEAMBMG" + "ByqGSM49AgEGCCqGSM49AwEHBHkwdwIBAQQg" + RANDOM * 3
        self.assertFinds(p8_body, "private key")
        pem = PEM_BEGIN + "\n" + p8_body + "\n-----END PRIVATE KEY-----\n"
        self.assertFinds("ASC_KEY_P8=" + base64.b64encode(pem.encode()).decode(), "private key")
        jks = bytes.fromhex("feedfeed00000002") + RANDOM.encode() * 8
        self.assertFinds(base64.b64encode(jks).decode(), "keystore")
        pkcs12 = bytes.fromhex("308209f4020103308209") + RANDOM.encode() * 8  # what keytool makes by default
        self.assertFinds(base64.b64encode(pkcs12).decode(), "keystore")

    def test_placeholders_examples_and_lookups_pass(self):
        for text in (
                "Use username/password, or USERNAME and PASSWORD.",
                "http://host.example.com:8080/live/user/pass/101.ts",
                f"http://p.example.com:8080/get.php?username=u1&password={SHORT}&type=m3u_plus",
                "http://host:port/get.php?username=U&password=P&type=m3u_plus",
                "http://host:port/live/user/pass/123.ts",
                "http://h/live/u/p/1.ts",
                '"username=demo&password=se/cr%2Bet&type=m3u_plus&output=ts"',
                "http://p.example.com/player_api.php?username=user%20name&password=p%26ss=%23?",
                'URL(string: "\\(base)/live/\\(user)/\\(pass)/\\(streamID).ts")',
                "https://api.themoviedb.org/3/movie/550/credits",
                "          ANDROID_KEYSTORE_PASSWORD: ${{ secrets.ANDROID_KEYSTORE_PASSWORD }}",
                'echo "$ANDROID_KEYSTORE_PASSWORD"; password=$ANDROID_KEYSTORE_PASSWORD',
                'storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")',
                'keyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: keystorePassword',
                'value = ENV.fetch("ASC_KEY_P8").strip',
                "let password: String", "PASSWORD_MIN_LENGTH = 8", 'const val PREF_TOKEN = "tmdb_token_v4"',
                '"token": "key-example-0001"', 'apiKey = "YOUR_API_KEY"', 'secret: "xxxxxxxxxxxxxxxx"',
                'password = "{password}"', "token: <your API Read Access Token>", "let token = tokens[0]",
                "The ASC_KEY_P8 secret holds the whole -----BEGIN PRIVATE KEY----- block."):
            with self.subTest(text=text):
                self.assertClean(text)


def run_git(cwd: Path, *args: str) -> str:
    identity = ["-c", "user.name=ci", "-c", "user.email=ci@users.noreply.github.com", "-c", "commit.gpgsign=false",
                "-c", "tag.gpgsign=false", "-c", "core.hooksPath=/dev/null", "-c", "init.defaultBranch=main"]
    environment = {**os.environ, "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull}
    return subprocess.run(["git", *identity, *args], cwd=cwd, env=environment, check=True,
                          capture_output=True, text=True).stdout


@unittest.skipUnless(shutil.which("git"), "git is not installed")
class PushTests(unittest.TestCase):
    """`git push origin <branch>`, `--all` and `--tags` send more than the current branch: all of it is checked."""

    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root)
        self.work = self.root / "work"
        run_git(self.root, "init", "-q", "--bare", "remote.git")
        run_git(self.root, "init", "-q", "work")
        run_git(self.work, "remote", "add", "origin", str(self.root / "remote.git"))
        self.commit("README.md", "Ondo\n", "First commit")
        run_git(self.work, "push", "-q", "origin", "main")

    def commit(self, name: str, content: str, message: str) -> None:
        (self.work / name).write_text(content, encoding="utf-8")
        run_git(self.work, "add", name)
        run_git(self.work, "commit", "-q", "-m", message)

    def unpushed(self) -> list:
        return leak_guard.scan_unpushed(self.work, [])

    def test_pushed_commits_are_not_scanned_again(self):
        self.assertEqual(self.unpushed(), [])

    def test_a_commit_of_another_branch_is_checked(self):
        run_git(self.work, "checkout", "-q", "-b", "side")
        self.commit("hosts.txt", f"tv {LOCAL_ADDRESS}\n", "Side work")
        run_git(self.work, "checkout", "-q", "main")
        self.assertIn("private network address", [rule for _, rule, _ in self.unpushed()])

    def test_a_tagged_commit_on_no_branch_is_checked(self):
        run_git(self.work, "checkout", "-q", "--detach")
        self.commit("hosts.txt", f"tv {LOCAL_ADDRESS}\n", "Tagged work")
        run_git(self.work, "tag", "v0.0.1")
        run_git(self.work, "checkout", "-q", "main")
        self.assertIn("private network address", [rule for _, rule, _ in self.unpushed()])


if __name__ == "__main__":
    unittest.main()
