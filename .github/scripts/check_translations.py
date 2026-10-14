#!/usr/bin/env python3
"""Every interface text of the Apple app must be translated into French.

1. Every key of App/Resources/Localizable.xcstrings has a French translation marked "translated"
   (plural variations included). Stale keys (no longer in the code) are ignored.
2. Every literal shown by a localizing SwiftUI API in App/ (Text("…"), Label("…", …), Button("…"),
   .navigationTitle("…"), String(localized: "…"), Shelf(title: "…"), …) exists in the catalogue:
   a string added in the code but not in the catalogue appears in English in the French app.
   Literals with interpolation are listed but not checked (their catalogue key uses %@ / %lld).

Usage: check_translations.py [repository root]. Exit code 1 lists what is missing.
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[2]
CATALOGUE = ROOT / "App/Resources/Localizable.xcstrings"
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

    for key, entry in strings.items():
        if entry.get("shouldTranslate") is False or entry.get("extractionState") == "stale":
            continue
        french = entry.get("localizations", {}).get("fr")
        if french is None or not translated(french):
            problems.append(f"no French translation in Localizable.xcstrings: {key!r}")

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
