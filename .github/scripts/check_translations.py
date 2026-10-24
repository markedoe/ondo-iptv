#!/usr/bin/env python3
"""Every interface text of the Apple app must be translated into every language of the app.

1. Every key of App/Resources/Localizable.xcstrings (and InfoPlist.xcstrings, the permission prompts, which has every
   NS…UsageDescription of project.yml) has a translation marked "translated" in each language of LANGUAGES (plural
   variations included). Stale keys (no longer in the code) are ignored.
2. Every literal shown by a localizing SwiftUI API in App/ (Text("…"), Label("…", …), Button("…"),
   .navigationTitle("…"), String(localized: "…"), Shelf(title: "…"), …) exists in the catalogue:
   a string added in the code but not in the catalogue appears in English in the other languages.
   Literals with interpolation are listed but not checked (their catalogue key uses %@ / %lld).
3. Every translation keeps the placeholders of its key (%@, %lld, %1$@…: same positions and types, no stray %,
   positional and plain ones never mixed): a %lld read as %@ crashes in that language only. A plural form may
   leave the number out (Arabic "one category") but adds none, and each language has exactly its plural forms
   (CLDR): Arabic zero, one, two, few, many and other; French, Spanish and Portuguese one, many and other.

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
# Info.plist is generated from project.yml (XcodeGen).
PROJECT = ROOT / "project.yml"

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

# The plural forms of each language for whole numbers (CLDR plural rules, which Foundation follows).
PLURAL_FORMS = {"en": {"one", "other"}, "fr": {"one", "many", "other"}, "es": {"one", "many", "other"},
                "pt": {"one", "many", "other"}, "zh-Hans": {"other"},
                "ar": {"zero", "one", "two", "few", "many", "other"}, "hi": {"one", "other"}}

# A format specifier of String(format:): %@, %lld, %1$@, %.1f\u2026 and %% (a percent sign). ASCII digits only: an
# Arabic or Hindi digit typed in %1$@ is no position for Foundation.
SPECIFIER = re.compile(r"%(?:([0-9]+)\$)?[-+ #0']*(?:[0-9]+|\*)?(?:\.(?:[0-9]+|\*))?(hh|h|ll|l|q|L|z|t|j)?([@dDiuUxXoOfFeEgGaAcCsSp%])")


def placeholders(text: str) -> tuple[list[tuple[int, str]], bool, bool]:
    """The (position, type) of each argument, whether a % is left over and whether positions are mixed."""
    found, positional, plain = [], False, False
    for match in SPECIFIER.finditer(text):
        if match.group(3) == "%":
            continue
        if match.group(1):
            positional = True
            found.append((int(match.group(1)), (match.group(2) or "") + match.group(3)))
        else:
            plain = True
            found.append((len(found) + 1, (match.group(2) or "") + match.group(3)))
    return sorted(found), "%" in SPECIFIER.sub("", text), positional and plain


def units(unit: dict, path: str = ""):
    """Each text of a localization with where it is: "" or "plural.one", "device.mac"\u2026"""
    if "stringUnit" in unit:
        yield path, unit["stringUnit"].get("value", "")
    for kind, cases in unit.get("variations", {}).items():
        for case, sub in cases.items():
            yield from units(sub, f"{path}{kind}.{case} ")


def format_problems(file_name: str, key: str, entry: dict) -> list[str]:
    localizations = entry.get("localizations", {})
    english = localizations.get("en", {}).get("stringUnit", {}).get("value", key)
    expected, english_stray, _ = placeholders(english)
    problems = []
    for code, unit in localizations.items():
        name = LANGUAGES.get(code, code)
        plural = unit.get("variations", {}).get("plural")
        forms = PLURAL_FORMS.get(code)
        if plural is not None and forms is not None and set(plural) != forms:
            problems.append(f"{name} plural forms in {file_name} are {sorted(plural)}, not {sorted(forms)}: {key!r}")
        for path, value in units(unit):
            found, stray, mixed = placeholders(value)
            fits = set(found) <= set(expected) and len(found) == len(set(found)) if "plural." in path else found == expected
            if not fits or (stray and not english_stray) or mixed:
                problems.append(f"{name} {path}text in {file_name} does not keep the placeholders of {key!r}: {value!r}")
    return problems


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
    prompts = {}
    if INFO_PLIST_CATALOGUE.exists():
        prompts = json.loads(INFO_PLIST_CATALOGUE.read_text(encoding="utf-8"))["strings"]
        catalogues.append((INFO_PLIST_CATALOGUE.name, prompts))
    # Each permission prompt of Info.plist (project.yml) has its entry: without one, it shows in English everywhere.
    if PROJECT.exists():
        for key in sorted(set(re.findall(r"^\s*(NS\w+UsageDescription):", PROJECT.read_text(encoding="utf-8"), re.MULTILINE))):
            if key not in prompts:
                problems.append(f"{key} (project.yml) is not in {INFO_PLIST_CATALOGUE.name}: it shows in English in every language")
    for file_name, entries in catalogues:
        for key, entry in entries.items():
            if entry.get("shouldTranslate") is False or entry.get("extractionState") == "stale":
                continue
            problems.extend(format_problems(file_name, key, entry))
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
