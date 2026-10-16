#!/usr/bin/env python3
"""The Mac App Store build must keep the sandbox entitlements its features need, and only those.

In the App Store sandbox, a missing entitlement fails silently: without network.client no playlist,
guide or stream loads; without files.user-selected.read-write the file pickers cannot open a playlist,
subtitle or backup file, nor save a backup (read-only would be enough to open, not to save).
An entitlement no feature needs widens the sandbox for nothing (and App Review rejects entitlements an
app does not use), so anything outside REQUIRED fails too. CI builds are not signed, so the check reads the entitlements file
the release build is signed with.

Usage: check_mac_entitlements.py [repository root]. Exit code 1 lists what is missing or not needed.
"""
import plistlib
import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[2]
RELEASE = "App/Resources/Ondo-macOS.entitlements"
REQUIRED = {
    "com.apple.security.app-sandbox": "the Mac App Store requires the sandbox",
    "com.apple.security.network.client": "playlists, guides and streams are downloaded",
    "com.apple.security.network.server":
        "MKV films are remuxed for the system player and served to it on 127.0.0.1 (RemuxServer)",
    "com.apple.security.files.user-selected.read-write":
        "playlist, subtitle and backup files are opened with file pickers, and backups saved",
}
# Debug only: never in the build sent to the App Store.
FORBIDDEN = {
    "com.apple.security.cs.disable-library-validation": "only needed by ad-hoc signed Debug builds",
    "com.apple.security.get-task-allow": "debugging entitlement",
}


def main() -> int:
    problems = []
    project = (ROOT / "project.yml").read_text(encoding="utf-8")
    if not re.search(r'"CODE_SIGN_ENTITLEMENTS\[sdk=macosx\*\]":\s*' + re.escape(RELEASE), project):
        problems.append(f"project.yml does not sign the Mac app with {RELEASE}")
    entitlements = plistlib.loads((ROOT / RELEASE).read_bytes())
    for key, why in REQUIRED.items():
        if entitlements.get(key) is not True:
            problems.append(f"{RELEASE} lacks {key} ({why})")
    for key, why in FORBIDDEN.items():
        if key in entitlements:
            problems.append(f"{RELEASE} has {key} ({why})")
    for key in sorted(set(entitlements) - set(REQUIRED) - set(FORBIDDEN)):
        problems.append(f"{RELEASE} has {key}, which no feature needs (add it to REQUIRED with its reason if one does)")
    for problem in problems:
        print(f"::error::{problem}")
    print(f"{len(REQUIRED)} required and {len(FORBIDDEN)} forbidden entitlements checked (nothing else allowed), "
          f"{len(problems)} problem(s).")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
