#!/usr/bin/env python3
"""The privacy manifest must give a reason for every "required reason" API the shipped code uses.

App Store Connect rejects an upload that uses one of these APIs without declaring it in
App/Resources/PrivacyInfo.xcprivacy (ITMS-91053). The app's own code (App/ and the IPTVCore package, Swift
and C) is scanned as every Release build compiles it: without the code only Debug builds have (`#if DEBUG`,
`#ifdef DEBUG`), with the TestFlight edition's (its uploads are checked too); VLCKit, Nuke and the Google Cast SDK
ship their own manifests.

Usage: check_privacy_manifest.py [repository root]. Exit code 1 lists the undeclared categories.
"""
import plistlib
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_app_store import edition_source  # noqa: E402  (the same reading of `#if` conditions)

ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "App/Resources/PrivacyInfo.xcprivacy"
SOURCES = [ROOT / "App", ROOT / "Packages/IPTVCore/Sources"]

# https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api
CATEGORIES = {
    "NSPrivacyAccessedAPICategoryUserDefaults": r"\b(?:NS)?UserDefaults\b|@AppStorage\b|NSUbiquitousKeyValueStore",
    # stat, fstat, lstat, fstatat; getattrlist, fgetattrlist, getattrlistbulk, getattrlistat.
    "NSPrivacyAccessedAPICategoryFileTimestamp":
        r"attributesOfItem|\.(?:creation|modification|contentModification|contentAccess)Date\b|fileModificationDate|"
        r"(?:creation|contentModification|contentAccess|attributeModification)DateKey|"
        r"\b[fl]?stat(?:at)?\(|\bf?getattrlist(?:bulk|at)?\(",
    "NSPrivacyAccessedAPICategorySystemBootTime": r"systemUptime|mach_absolute_time|CLOCK_UPTIME",
    # statfs, statvfs, fstatfs, fstatvfs; FileManager's attributesOfFileSystem gives systemFreeSize / systemSize.
    "NSPrivacyAccessedAPICategoryDiskSpace":
        r"volume(?:Available|Total)Capacity|systemFreeSize|systemSize\b|attributesOfFileSystem|\bf?statv?fs\(",
    "NSPrivacyAccessedAPICategoryActiveKeyboards": r"activeInputModes",
}
# Swift and the C / Objective-C sources next to it (IPTVCore's zlib wrapper).
SOURCE_SUFFIXES = {".swift", ".c", ".h", ".m", ".mm"}
C_SUFFIXES = {".c", ".h", ".m", ".mm"}
# The compilation conditions every Release build has, both editions: DEBUG is off; TESTFLIGHT_DOWNLOADS is left
# unknown, so code under it is scanned too (its builds are uploaded to TestFlight).
RELEASE = {"DEBUG": False, "true": True, "false": False, "0": False, "1": True}


def swift_conditions(text: str) -> str:
    """C preprocessor conditions written the Swift way (`#ifdef X` → `#if X`, `#ifndef X` → `#if !(X)`, `#elif` →
    `#elseif`, `defined(X)` → `X`), for edition_source."""
    lines = []
    for line in text.splitlines():
        directive = re.match(r"\s*#\s*(ifdef|ifndef|elif|if|else|endif)\b(.*)", line)
        if directive:
            keyword, rest = directive.group(1), directive.group(2).split("/*", 1)[0].split("//", 1)[0]
            rest = re.sub(r"\bdefined\s*\(\s*(\w+)\s*\)|\bdefined\s+(\w+)", lambda m: m.group(1) or m.group(2), rest).strip()
            line = {"ifdef": f"#if {rest}", "ifndef": f"#if !({rest})", "elif": f"#elseif {rest}"}.get(keyword, f"#{keyword} {rest}")
        lines.append(line)
    return "\n".join(lines)


def release_source(path: Path) -> str:
    """The code of a source file that a Release build compiles."""
    text = path.read_text(encoding="utf-8")
    return edition_source(swift_conditions(text) if path.suffix in C_SUFFIXES else text, RELEASE)


def main() -> int:
    manifest = plistlib.loads(MANIFEST.read_bytes())
    declared = {entry["NSPrivacyAccessedAPIType"]: entry.get("NSPrivacyAccessedAPITypeReasons", [])
                for entry in manifest.get("NSPrivacyAccessedAPITypes", [])}
    problems = [f"{category} is declared without a reason" for category, reasons in declared.items() if not reasons]

    for category, pattern in CATEGORIES.items():
        uses = []
        for folder in SOURCES:
            for path in sorted(p for p in folder.rglob("*") if p.suffix in SOURCE_SUFFIXES and p.is_file()):
                if re.search(pattern, release_source(path)):
                    uses.append(str(path.relative_to(ROOT)))
        if uses and category not in declared:
            problems.append(f"{category} is used by {', '.join(uses)} but not declared in PrivacyInfo.xcprivacy")

    for problem in problems:
        print(f"::error::{problem}")
    print(f"{len(declared)} declared categories, {len(problems)} problem(s).")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
