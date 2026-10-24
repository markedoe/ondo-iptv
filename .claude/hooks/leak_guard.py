#!/usr/bin/env python3
"""Keeps secrets and personal data out of this public repository.

Modes:
  --hook     Claude Code PreToolUse hook (.claude/settings.json). Reads the tool call on stdin and checks
             what Claude writes into the repository (Write / Edit / MultiEdit / NotebookEdit), every shell
             command, and before `git commit` / `git push` the changes or commits about to leave the machine.
             Exit code 2 blocks the call; the reason is printed for Claude.
  --staged   Git pre-commit hook (.githooks/pre-commit): staged changes.
  --tree     Every tracked file (CI).
  --history  Every commit of every branch: added lines, file names, messages and identities (CI).

Findings can be allowed with a regex in `.leakguard-allow` (one per line) or with `leak-guard: allow`
on the line itself. Only standard library: it runs on macOS, Linux and in CI as is.
Limit: binary files (images, archives) are only checked by name, not by content or metadata.
"""
import base64
import json
import os
import re
import subprocess
import sys
from pathlib import Path

# What stands for a secret without being one: templates (`$X`, `${{ secrets.X }}`, `{value}`, `\\(pass)`, `<key>`,
# `%s`) and placeholder words (`PASSWORD`, `pass`, `xxx`, `YOUR_API_KEY`, `key-example-0001`). Given to a secret
# name, a value with a secret word in it is a name too (`"tmdb_token_v4"`, a storage key).
TEMPLATE = re.compile(r"[$<>{}]|\\\(|%[(s]|^[*.…]+$")
PLACEHOLDER = re.compile(
    r"(?i)^(?:x{3,}|(?:your|my|the|some|a)?[_-]?(?:pass(?:word|wd)?|secret|token|(?:api[_-]?)?key|user(?:name)?|login|"
    r"value|changeme)s?[_-]?\d{0,2})$|example|placeholder|dummy|redacted|not[_-]?(?:a[_-]?)?(?:real|secret)")
SECRET_WORD = re.compile(r"(?i)pass(?:word|wd|phrase)|secret|token|api[_-]?key|private[_-]?key")
# Hosts of no real service: the reserved example names, this machine, a bare name (`h`, `host:port`, `server`).
SAFE_HOST = re.compile(
    r"(?i)^(?:[^@/]*@)?(?:(?:[\w-]+\.)*(?:example(?:\.(?:com|org|net))?|test|invalid|localhost)|127(?:\.\d+){3}|[^.:\[]+)"
    r"(?::[^/]*)?$")


def placeholder(value):
    return bool(TEMPLATE.search(value) or PLACEHOLDER.search(value))


def mixed(value):
    """Letters and digits: what a generated password or key looks like, unlike a word or a type name."""
    return bool(re.search(r"[A-Za-z]", value) and re.search(r"\d", value))


def url_query_secret(match):
    """`?username=jdoe42&password=…`: any value at a real host; without an address, a plain value of 6 characters
    or more (the core tests' URL-encoding fixtures, `se/cr%2Bet`, are not secrets)."""
    host, value = match.group("host"), match.group("value")
    if placeholder(value):
        return False
    if host:
        return not SAFE_HOST.match(host) and len(value) >= 3
    return bool(re.fullmatch(r"[A-Za-z0-9._~!*+-]{6,}", value))


def url_path_secret(match):
    """`http://panel/live/jdoe42/<password>/1234.ts`: the Xtream stream address carries the account."""
    return not SAFE_HOST.match(match.group("host")) and len(match.group("value")) >= 3 and \
        not placeholder(match.group("value"))


def assigned_secret(match):
    """A value given to a name that ends with a secret word (`storePassword`, `ANDROID_KEYSTORE_PASSWORD`,
    `tmdbToken`, `password:` in YAML): quoted, 16 characters or more, or 8 with letters and digits; bare (YAML,
    shell, properties), 8 characters or more with letters and digits."""
    quoted, bare = match.group("quoted"), match.group("bare")
    value = quoted or bare
    if placeholder(value) or SECRET_WORD.search(value) or "://" in value:
        return False
    if quoted:
        return len(value) >= 16 or (len(value) >= 8 and mixed(value))
    return len(value) >= 8 and mixed(value)


SECRET_NAME = (r"(?:api[_-]?key|access[_-]?key|secret[_-]?key|private[_-]?key|auth[_-]?key|client[_-]?secret|secret|"
               r"token|password|passwd|passphrase)s?(?![A-Za-z0-9_])")

# The PEM body, also as a JSON string (a service account key: `\n` between the lines).
PEM_PRIVATE_KEY = re.compile(r"-----BEGIN (?:[A-Z]+ )*PRIVATE KEY-----(?:[A-Za-z0-9+/=\s]|\\[nr]){40,}")


def base64_private_key(match):
    """A PEM file in base64 (`base64 -i AuthKey_….p8`, which the ASC_KEY_P8 secret also takes), whole or as the first
    line of `base64` (76 characters): a private key once decoded."""
    run = match.group(0)
    try:
        decoded = base64.b64decode(run[:len(run) // 4 * 4]).decode("utf-8", "replace")
    except ValueError:
        return False
    return "PRIVATE KEY-----" in decoded


def apple_id(match):
    """The App Store Connect Key ID and the Team ID (10 capitals and digits) or the Issuer ID (a UUID), which CLAUDE.md
    keeps in GitHub secrets: given to their names (`ASC_KEY_ID=`, `issuer_id:`, `DEVELOPMENT_TEAM =`, `team_id("…")`)."""
    value = match.group("value")
    return not placeholder(value) and bool(re.search(r"\d", value))

# Secrets: specific formats first, then credentials in addresses and values given to secret names. An optional third
# item decides on each match (placeholders, example hosts).
SECRET_RULES = [
    ("private key", PEM_PRIVATE_KEY),
    # The key without its PEM lines: App Store Connect's .p8 (EC P-256), an RSA key (PKCS #8).
    ("private key", re.compile(
        r"\b(?:MIG[HT]AgEAMBMGByqGSM49AgEGCCqGSM49AwEHB|MII[A-Za-z0-9+/]{2}QIBADANBgkqhkiG9w0BAQEFAASC)[A-Za-z0-9+/]{40,}")),
    # A PEM file in base64: "-----BEGIN" is `LS0tLS1CRUdJTi`.
    ("private key", re.compile(r"(?<![A-Za-z0-9+/])LS0tLS1CRUdJTi[A-Za-z0-9+/]{22,}={0,2}"), base64_private_key),
    # A keystore in base64 (`base64 -w0 ondo-upload.jks`, docs/RELEASE-ANDROID.md): JKS starts with FEEDFEED, PKCS #12
    # (what keytool makes by default) with a SEQUENCE then version 3.
    ("keystore", re.compile(r"(?<![A-Za-z0-9+/])(?:/u3\+7QAAAA|MII[A-Za-z0-9+/]{2}[AQgw]IBAzCC)[A-Za-z0-9+/]{60,}")),
    ("AWS access key", re.compile(r"\b(?:AKIA|ASIA)[0-9A-Z]{16}\b")),
    ("GitHub token", re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{22,})")),
    ("Slack token", re.compile(r"\bxox[abposr]-[A-Za-z0-9-]{10,}")),
    ("Google API key", re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b")),
    ("Stripe key", re.compile(r"\b(?:sk|rk)_live_[0-9A-Za-z]{20,}")),
    ("Anthropic / OpenAI key", re.compile(r"\bsk-(?:ant-|proj-)?[A-Za-z0-9_-]{32,}")),
    ("JSON Web Token", re.compile(r"\beyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}")),
    ("App Store Connect key file", re.compile(r"\bAuthKey_[A-Z0-9]{10}\.p8\b")),
    ("App Store Connect key ID, issuer ID or team ID", re.compile(
        r"(?i)(?:key[_-]?id|issuer[_-]?id|team[_-]?id(?:entifier)?|development[_-]?team)"
        r"(?:[\"'`]?[ \t]*(?:=>|:=|=|:|\()[ \t]*[\"'`]?|</key>\s*<string>)"
        r"(?P<value>(?-i:[A-Z0-9]{10})|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})(?![\w-])"), apple_id),
    ("credentials in a URL", re.compile(r"\b[a-z][a-z0-9+.-]*://[^/\s:@'\"]+:[^/\s:@'\"$]{6,}@[^\s'\"]+")),
    ("credentials in a URL", re.compile(
        r"(?i)(?:\b[a-z][a-z0-9+.-]*://(?P<host>[^/\s?#'\"<>]+)[^\s?#'\"<>]*\?(?:[^\s#'\"<>]*?&)?|[?&])"
        r"(?:password|passwd|pass|pwd)=(?P<value>[^&#\s'\"<>]+)"), url_query_secret),
    ("credentials in a URL", re.compile(
        r"(?i)\b[a-z][a-z0-9+.-]*://(?P<host>[^/\s?#'\"<>]+)/(?:live|movie|series|timeshift)/[^/\s?#'\"<>]+/"
        r"(?P<value>[^/\s?#'\"<>]+)/[^\s'\"<>]*\d"), url_path_secret),
    # The short form of a live stream in panels' M3U exports: `http://panel/jdoe42/<password>/1234`. Not an API's
    # numbered version (`/3/tv/1399`).
    ("credentials in a URL", re.compile(
        r"(?i)\b[a-z][a-z0-9+.-]*://(?P<host>[^/\s?#'\"<>]+)/(?P<user>[^/\s?#'\"<>]+)/(?P<value>[^/\s?#'\"<>]+)/\d+"
        r"(?:\.[a-z0-9]{2,4})?(?![^\s'\"<>?#])"),
     lambda match: url_path_secret(match) and not match.group("user").isdigit() and not match.group("value").isdigit()),
    # A TMDb API key (32 hexadecimal digits) in an address.
    ("credentials in a URL", re.compile(r"(?i)[?&]api_?key=[0-9a-f]{32}(?![0-9a-z])")),
    ("hard-coded secret", re.compile(
        rf"(?i)(?<![?&]){SECRET_NAME}[\"'`]?(?:\s*:\s*[A-Za-z_][\w.<>?\[\]]*\s*(?==))?\s*(?:=>|:=|\?=|=|:)[ \t]*"
        r"(?:(?P<quote>[\"'`])(?P<quoted>[^\"'`\s]{8,})(?P=quote)|(?P<bare>[^\s\"'`.$<>{}()\[\]\\,;#&?]{8,})(?=[\s,;)\]}]|$))"),
     assigned_secret),
]

# Personal data: addresses, home folders and private networks.
EMAIL = re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b")
ALLOWED_EMAIL = re.compile(
    r"(?i)(?:@users\.noreply\.github\.com|^noreply@(?:anthropic|github)\.com|@example\.(?:com|org|net)|@\d+x\.)")
PERSONAL_RULES = [
    ("home folder", re.compile(r"(?:/Users/(?!runner\b|Shared\b|\$|<)[A-Za-z0-9._-]+|/home/(?!runner\b|user\b|\$|<)[A-Za-z0-9._-]+"
                               r"|/root/\.claude\b[^\s'\"]*|[A-Z]:\\Users\\[A-Za-z0-9._-]+)")),
    ("private network address", re.compile(r"\b(?:192\.168|10\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01]))\.\d{1,3}\.\d{1,3}\b")),
]

FORBIDDEN_FILES = re.compile(
    r"(?i)(?:^|/)(?:secrets/|\.env(?:\.[^/]*)?$|id_(?:rsa|ed25519|ecdsa)[^/]*$|[^/]*\.(?:p8|p12|pem|key|cer|mobileprovision|"
    r"provisionprofile|keystore|jks|pyc)$|__pycache__/)")

ALLOW_MARKER = "leak-guard: allow"


def repo_root(start=None):
    try:
        out = subprocess.run(["git", "rev-parse", "--show-toplevel"], cwd=start or os.getcwd(),
                             capture_output=True, text=True, check=True)
        return Path(out.stdout.strip())
    except (subprocess.CalledProcessError, FileNotFoundError, NotADirectoryError):
        return None


def load_allowlist(root):
    path = root / ".leakguard-allow" if root else None
    if not path or not path.exists():
        return []
    return [re.compile(line.strip()) for line in path.read_text().splitlines() if line.strip() and not line.startswith("#")]


def redact(text):
    text = text.strip().replace("\n", " ")
    return text[:6] + "…" if len(text) > 6 else "…"


def scan_text(text, where, allow):
    """Findings (where, rule, redacted match) in a block of text."""
    findings = []
    lines = text.splitlines()

    def allowed(match_text, line_text):
        return ALLOW_MARKER in line_text or any(rule.search(match_text) or rule.search(line_text) for rule in allow)

    def line_of(position):
        number = text.count("\n", 0, position)
        return number + 1, lines[number] if number < len(lines) else ""

    for name, rule, *decide in SECRET_RULES:
        for match in rule.finditer(text):
            if decide and not decide[0](match):
                continue
            number, line = line_of(match.start())
            if not allowed(match.group(0), line):
                findings.append((f"{where}:{number}", name, redact(match.group(0))))
    for match in EMAIL.finditer(text):
        number, line = line_of(match.start())
        if not ALLOWED_EMAIL.search(match.group(0)) and not allowed(match.group(0), line):
            findings.append((f"{where}:{number}", "email address", redact(match.group(0))))
    for name, rule in PERSONAL_RULES:
        for match in rule.finditer(text):
            number, line = line_of(match.start())
            if not allowed(match.group(0), line):
                findings.append((f"{where}:{number}", name, match.group(0)))
    return findings


def scan_names(names, allow):
    return [(name, "file that must not be committed", name) for name in names
            if FORBIDDEN_FILES.search(name) and not any(rule.search(name) for rule in allow)]


def git(root, *args):
    return subprocess.run(["git", *args], cwd=root, capture_output=True, text=True, errors="replace").stdout


def added_lines_by_file(diff):
    """Added lines of a unified diff, grouped by file."""
    files, current = {}, None
    for line in diff.splitlines():
        if line.startswith("+++ "):
            current = line[6:] if line.startswith("+++ b/") else None
            if current:
                files.setdefault(current, [])
        elif current and line.startswith("+") and not line.startswith("+++"):
            files[current].append(line[1:])
    return {name: "\n".join(lines) for name, lines in files.items()}


def scan_diff(diff, allow, label=""):
    findings = []
    for name, text in added_lines_by_file(diff).items():
        findings += scan_text(text, f"{label}{name}", allow)
    return findings


def scan_working_changes(root, allow):
    """Everything a `git add -A && git commit` would record."""
    findings = scan_diff(git(root, "diff", "HEAD", "--no-color", "--no-ext-diff"), allow)
    findings += scan_diff(git(root, "diff", "--cached", "--no-color", "--no-ext-diff"), allow)
    changed = git(root, "diff", "HEAD", "--name-only", "--diff-filter=AR").split()
    untracked = git(root, "ls-files", "--others", "--exclude-standard").split()
    findings += scan_names(changed + untracked, allow)
    for name in untracked:
        path = root / name
        if path.is_file() and path.stat().st_size < 2_000_000:
            findings += scan_text(path.read_text(errors="replace"), name, allow)
    return findings


def scan_commits(root, revisions, allow):
    findings = []
    for commit in revisions:
        meta = git(root, "show", "-s", "--format=%an <%ae>%n%cn <%ce>%n%B", commit)
        findings += scan_text(meta, f"commit {commit[:7]} message/identity", allow)
        findings += scan_diff(git(root, "show", "--no-color", "--format=", commit), allow, f"{commit[:7]}:")
        findings += scan_names(git(root, "show", "--name-only", "--diff-filter=AR", "--format=", commit).split(), allow)
    return findings


def scan_identity(root, allow):
    """The address a new commit would carry (public on GitHub)."""
    email = git(root, "config", "user.email").strip()
    if email and not ALLOWED_EMAIL.search(email) and not any(rule.search(email) for rule in allow):
        return [("git config user.email", "commit identity is not a noreply address", redact(email))]
    return []


def scan_unpushed(root, allow):
    """The commits of every local branch and tag that no remote branch has: `git push origin <branch>`, `--all` and
    `--tags` can send more than the current branch."""
    revisions = git(root, "rev-list", "--branches", "--tags", "--not", "--remotes").split()
    return scan_commits(root, revisions, allow)


def scan_tree(root, allow):
    names = git(root, "ls-files").splitlines()
    findings = scan_names(names, allow)
    for name in names:
        path = root / name
        if path.is_file() and path.stat().st_size < 2_000_000:
            data = path.read_bytes()
            if b"\0" not in data[:8000]:
                findings += scan_text(data.decode("utf-8", "replace"), name, allow)
    return findings


def hook(event):
    tool, data = event.get("tool_name", ""), event.get("tool_input", {}) or {}
    root = repo_root(event.get("cwd"))
    allow = load_allowlist(root)
    findings = []
    if tool == "Bash":
        command = data.get("command", "")
        findings += scan_text(command, "command", allow)
        if root and re.search(r"\bgit\b[^|;&]*\bcommit\b", command):
            findings += scan_working_changes(root, allow) + scan_identity(root, allow)
        if root and re.search(r"\bgit\b[^|;&]*\bpush\b", command):
            findings += scan_working_changes(root, allow) if "commit" in command else []
            findings += scan_unpushed(root, allow)
    elif tool in ("Write", "Edit", "MultiEdit", "NotebookEdit"):
        path = Path(data.get("file_path") or data.get("notebook_path") or "")
        file_root = repo_root(path.parent if path.parent.exists() else None) if path.is_absolute() else root
        if not file_root or not str(path.resolve()).startswith(str(file_root.resolve())):
            return []  # outside a repository: nothing is published from there
        relative = str(path.resolve().relative_to(file_root.resolve()))
        if subprocess.run(["git", "check-ignore", "-q", relative], cwd=file_root).returncode == 0:
            return []  # ignored files are never committed
        texts = [data.get("content", ""), data.get("new_string", ""), data.get("new_source", "")]
        texts += [edit.get("new_string", "") for edit in data.get("edits", []) or []]
        findings += scan_names([relative], load_allowlist(file_root))
        for text in texts:
            if text:
                findings += scan_text(text, relative, load_allowlist(file_root))
    return findings


def report(findings, blocking_exit):
    unique = list(dict.fromkeys(findings))
    if not unique:
        return 0
    print("Leak guard: possible secret or personal data, nothing was sent:", file=sys.stderr)
    for where, rule, sample in unique[:30]:
        print(f"  {where}: {rule} ({sample})", file=sys.stderr)
    print("Remove it (GitHub secrets for keys), or if it is a false positive add a regex to .leakguard-allow "
          "or `leak-guard: allow` on the line.", file=sys.stderr)
    return blocking_exit


def main(argv):
    if "--hook" in argv:
        try:
            event = json.load(sys.stdin)
        except json.JSONDecodeError:
            return 0
        return report(hook(event), 2)
    root = repo_root()
    if not root:
        print("leak_guard: not in a git repository", file=sys.stderr)
        return 1
    allow = load_allowlist(root)
    findings = []
    if "--staged" in argv:
        findings += scan_identity(root, allow)
        findings += scan_diff(git(root, "diff", "--cached", "--no-color", "--no-ext-diff"), allow)
        findings += scan_names(git(root, "diff", "--cached", "--name-only", "--diff-filter=AR").split(), allow)
    if "--tree" in argv:
        findings += scan_tree(root, allow)
    if "--history" in argv:
        findings += scan_commits(root, git(root, "rev-list", "--all").split(), allow)
    code = report(findings, 1)
    if code == 0:
        print("leak_guard: nothing found")
    return code


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
