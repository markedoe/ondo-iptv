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
"""
import json
import os
import re
import subprocess
import sys
from pathlib import Path

# Secrets: specific formats first, then generic assignments of long values.
SECRET_RULES = [
    ("private key", re.compile(r"-----BEGIN (?:[A-Z]+ )*PRIVATE KEY-----\s*[A-Za-z0-9+/=\s]{40,}")),
    ("AWS access key", re.compile(r"\b(?:AKIA|ASIA)[0-9A-Z]{16}\b")),
    ("GitHub token", re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{22,})")),
    ("Slack token", re.compile(r"\bxox[abposr]-[A-Za-z0-9-]{10,}")),
    ("Google API key", re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b")),
    ("Stripe key", re.compile(r"\b(?:sk|rk)_live_[0-9A-Za-z]{20,}")),
    ("Anthropic / OpenAI key", re.compile(r"\bsk-(?:ant-|proj-)?[A-Za-z0-9_-]{32,}")),
    ("JSON Web Token", re.compile(r"\beyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}")),
    ("App Store Connect key file", re.compile(r"\bAuthKey_[A-Z0-9]{10}\.p8\b")),
    ("credentials in a URL", re.compile(r"\b[a-z][a-z0-9+.-]*://[^/\s:@'\"]+:[^/\s:@'\"$]{6,}@[^\s'\"]+")),
    ("hard-coded secret", re.compile(
        r"(?i)\b(?:api[_-]?key|secret|token|password|passwd|private[_-]?key)\b[\"']?\s*[:=]\s*[\"']([^\"'\s$]{16,})[\"']")),
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

    for name, rule in SECRET_RULES:
        for match in rule.finditer(text):
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


def scan_unpushed(root, allow):
    revisions = git(root, "rev-list", "HEAD", "--not", "--remotes").split()
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
            findings += scan_working_changes(root, allow)
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
