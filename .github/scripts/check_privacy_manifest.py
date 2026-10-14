#!/usr/bin/env python3
"""The privacy manifest must give a reason for every "required reason" API the shipped code uses.

App Store Connect rejects an upload that uses one of these APIs without declaring it in
App/Resources/PrivacyInfo.xcprivacy (ITMS-91053). The app's own code (App/ and the IPTVCore package) is
scanned without its `#if DEBUG` blocks; VLCKit and the Google Cast SDK ship their own manifests.

Usage: check_privacy_manifest.py [repository root]. Exit code 1 lists the undeclared categories.
"""
import plistlib
import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "App/Resources/PrivacyInfo.xcprivacy"
SOURCES = [ROOT / "App", ROOT / "Packages/IPTVCore/Sources"]

# https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api
CATEGORIES = {
    "NSPrivacyAccessedAPICategoryUserDefaults": r"\bUserDefaults\b|@AppStorage\b|NSUbiquitousKeyValueStore",
    "NSPrivacyAccessedAPICategoryFileTimestamp":
        r"attributesOfItem|\.(?:creation|modification|contentModification|contentAccess)Date\b|"
        r"(?:creation|contentModification|contentAccess|attributeModification)DateKey|\bstat\(|\bfstat\(|\bgetattrlist\(",
    "NSPrivacyAccessedAPICategorySystemBootTime": r"systemUptime|mach_absolute_time|CLOCK_UPTIME",
    "NSPrivacyAccessedAPICategoryDiskSpace":
        r"volume(?:Available|Total)Capacity|systemFreeSize|systemSize\b|\bstatfs\(|\bstatvfs\(",
    "NSPrivacyAccessedAPICategoryActiveKeyboards": r"activeInputModes",
}


def without_debug_blocks(text: str) -> str:
    """Drops `#if DEBUG … #endif` (nested conditions included, `#else` branches kept)."""
    kept, stack = [], []  # stack: is each open #if a DEBUG block currently being skipped
    for line in text.splitlines():
        directive = line.strip()
        if directive.startswith("#if"):
            stack.append(directive.split()[1:2] == ["DEBUG"])
            continue
        if directive.startswith("#else") and stack and stack[-1]:
            stack[-1] = False
            continue
        if directive.startswith("#endif") and stack:
            stack.pop()
            continue
        if not any(stack):
            kept.append(line)
    return "\n".join(kept)


def main() -> int:
    manifest = plistlib.loads(MANIFEST.read_bytes())
    declared = {entry["NSPrivacyAccessedAPIType"]: entry.get("NSPrivacyAccessedAPITypeReasons", [])
                for entry in manifest.get("NSPrivacyAccessedAPITypes", [])}
    problems = [f"{category} is declared without a reason" for category, reasons in declared.items() if not reasons]

    for category, pattern in CATEGORIES.items():
        uses = []
        for folder in SOURCES:
            for path in sorted(folder.rglob("*.swift")):
                if re.search(pattern, without_debug_blocks(path.read_text(encoding="utf-8"))):
                    uses.append(str(path.relative_to(ROOT)))
        if uses and category not in declared:
            problems.append(f"{category} is used by {', '.join(uses)} but not declared in PrivacyInfo.xcprivacy")

    for problem in problems:
        print(f"::error::{problem}")
    print(f"{len(declared)} declared categories, {len(problems)} problem(s).")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
