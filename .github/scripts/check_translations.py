#!/usr/bin/env python3
"""Every interface text of the Apple app must be translated into every language of the app.

1. Every key of App/Resources/Localizable.xcstrings (and InfoPlist.xcstrings, the permission prompts) has a
   translation marked "translated" in each language of LANGUAGES (plural variations included). Stale keys
   (no longer in the code) are ignored.
2. Every literal shown by a localizing SwiftUI API in App/ (Text("…"), Label("…", …), Button("…"),
   .navigationTitle("…"), String(localized: "…"), Shelf(title: "…"), …) exists in the catalogue:
   a string added in the code but not in the catalogue appears in English in the other languages.
   Literals with interpolation are listed but not checked (their catalogue key uses %@ / %lld).

Usage: check_translations.py [repository root]. Exit code 1 lists what is missing.
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[2]
CATALOGUE = ROOT / "App/Resources/Localizable.xcstrings"
# Texts of Info.plist (the system's permission prompts), translated like the app's.
INFO_PLIST_CATALOGUE = ROOT / "App/Resources/InfoPlist.xcstrings"
SOURCES = ROOT / "App"

STRING = r'"((?:[^"\\\n]|\\.)*)"'
# The first argument of these initialisers and modifiers is a LocalizedStringKey when it is a literal.
PATTERNS = [
    re.compile(r'\b(?:Text|Label|Button|Toggle|Section|Picker|TextField|SecureField|Menu|LabeledContent|'
               r'NavigationLink|Link|Stepper|ContentUnavailableView|ProgressView|GroupBox|DisclosureGroup)'
               r'\(\s*' + STRING),
    re.compile(r'\.(?:navigationTitle|help|alert|confirmationDialog|accessibilityLabel|accessibilityHint)\(\s*' + STRING),
    re.compile(r'\bString\(localized:\s*' + STRING),
    re.compile(r'\bLocalizedStringKey\(\s*' + STRING),
    re.compile(r'\bShelf\(\s*title:\s*' + STRING),
]

# The app's languages besides English (its development language): a new text is translated into all of them.
LANGUAGES = {"fr": "French", "es": "Spanish", "pt": "Portuguese", "zh-Hans": "Chinese (Simplified)",
             "ar": "Arabic", "hi": "Hindi"}

# Shown as is in every language: names and units.
UNTRANSLATED = {"Ondo", "VLC", "+0.5 s", "\u22120.5 s"}


def translated(unit: dict) -> bool:
    if "stringUnit" in unit:
        return unit["stringUnit"].get("state") == "translated" and bool(unit["stringUnit"].get("value"))
    variations = unit.get("variations", {})
    cases = [case for kind in variations.values() for case in kind.values()]
    return bool(cases) and all(translated(case) for case in cases)


def main() -> int:
    strings = json.loads(CATALOGUE.read_text(encoding="utf-8"))["strings"]
    problems = []

    catalogues = [(CATALOGUE.name, strings)]
    if INFO_PLIST_CATALOGUE.exists():
        catalogues.append((INFO_PLIST_CATALOGUE.name, json.loads(INFO_PLIST_CATALOGUE.read_text(encoding="utf-8"))["strings"]))
    for file_name, entries in catalogues:
        for key, entry in entries.items():
            if entry.get("shouldTranslate") is False or entry.get("extractionState") == "stale":
                continue
            for code, name in LANGUAGES.items():
                unit = entry.get("localizations", {}).get(code)
                if unit is None or not translated(unit):
                    problems.append(f"no {name} translation in {file_name}: {key!r}")

    interpolated = set()
    for path in sorted(SOURCES.rglob("*.swift")):
        text = path.read_text(encoding="utf-8")
        for pattern in PATTERNS:
            for match in pattern.finditer(text):
                literal = match.group(1)
                if "\\(" in literal:
                    interpolated.add(literal)
                    continue
                key = literal.replace('\\"', '"').replace("\\n", "\n")
                if not re.search(r"[A-Za-z]", key) or "://" in key or key in UNTRANSLATED:
                    continue  # symbols and numbers, example addresses, names
                if key not in strings:
                    line = text.count("\n", 0, match.start()) + 1
                    problems.append(f"{path.relative_to(ROOT)}:{line}: {key!r} is not in Localizable.xcstrings")

    for problem in problems:
        print(f"::error::{problem}")
    print(f"{len(strings)} catalogue keys checked, {len(interpolated)} interpolated literals not checked, "
          f"{len(problems)} problem(s).")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
