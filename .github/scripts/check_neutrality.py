#!/usr/bin/env python3
"""The repository stays neutral (CLAUDE.md): no other IPTV app is named, IPTV is never called free, and
tests and comments use fictional channels, not real ones.

Usage: check_neutrality.py [repository root]. Exit code 1 lists each place found.
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[2]
# The rules themselves, and the tests that show they work, contain the words.
EXCLUDED = {".github/scripts/check_neutrality.py", ".github/scripts/tests/test_checks.py"}
BINARY = {".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".icns", ".gz", ".zip", ".jar", ".aab", ".apk",
          ".pdf", ".ttf", ".otf", ".car", ".p8", ".jks", ".keystore", ".pyc", ".mp4", ".mov"}

RULES = [
    ("names another IPTV app", re.compile(
        r"(?i)\b(?:tivimate|iptv\s*smarters|smarters\s*pro|gse\s*smart|perfect\s*player|ott\s*navigator|siptv|"
        r"smart\s*iptv|iptv\s*simple|iptvnator|purple\s*player|xciptv|implayer|flex\s*iptv)\b|\bkodi's\b")),
    # In every language of the apps and their listings. No `\b` next to Chinese, Arabic or Hindi words:
    # Chinese has no spaces ("最佳免费IPTV"), and a letter followed by "IPTV" is no word boundary.
    ("calls IPTV free", re.compile(
        r"(?i)\bfree[\s-]*iptv\b|\biptv[\s-]*(?:for\s+)?free\b"
        # French, Spanish, Portuguese: gratuit(e)(s), gratuito/a(s), gratis, grátis, de graça.
        r"|\biptv\s+(?:gratuit[eoa]?s?|gr[aá]tis|de\s+gra[çc]a)\b|\b(?:gratuit[eoa]?s?|gr[aá]tis)\s+iptv\b"
        # Chinese (simplified and traditional): 免费IPTV, 免费的IPTV, IPTV免费.
        r"|免[费費]\s*的?\s*iptv|iptv\s*免[费費]"
        # Arabic: IPTV مجاني / مجانية / مجانا, and the reverse order.
        r"|iptv\s*مجان[؀-ۿ]*|مجان[؀-ۿ]*\s*iptv"
        # Hindi: मुफ़्त / मुफ्त (with or without the nukta, precomposed or not) IPTV, and the reverse order.
        r"|मु(?:फ़?|फ़)्त\s*iptv|iptv\s*मु(?:फ़?|फ़)्त")),
    ("uses a real channel name (tests and comments use fictional ones)", re.compile(
        r"\b(?:TF1|BFMTV|CNews|beIN|France [2-5]|M6|Arte)\b|Canal\+")),
]


def files(root: Path):
    try:
        listed = subprocess.run(["git", "-C", str(root), "ls-files"], capture_output=True, text=True, check=True).stdout.split("\n")
        paths = [root / name for name in listed if name]
    except (OSError, subprocess.CalledProcessError):
        paths = [path for path in root.rglob("*") if ".git" not in path.parts]
    for path in sorted(paths):
        relative = path.relative_to(root).as_posix()
        if path.is_file() and path.suffix.lower() not in BINARY and relative not in EXCLUDED:
            yield path, relative


def main() -> int:
    problems = []
    for path, relative in files(ROOT):
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        for number, line in enumerate(text.split("\n"), start=1):
            for reason, pattern in RULES:
                match = pattern.search(line)
                if match:
                    problems.append(f"{relative}:{number}: {reason}: {match.group(0)!r}")
    for problem in problems:
        print(f"::error::{problem}")
    print(f"{len(problems)} problem(s).")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
