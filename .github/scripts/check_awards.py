#!/usr/bin/env python3
"""The awards data shipped in both apps: the same file, well formed, and still naming the awards we think.

Series sit in `seriesAwards` and `series`, apart from `awards` and `films`: the apps released before series had awards
read only those two, so a series award must never appear there (it would show a series as a film).

App/Resources/awards.json (Apple) and android/app/src/main/assets/awards.json (Android) are written together by
tools/awards/generate_awards.py; a copy edited or regenerated alone would make the apps disagree.

Usage: check_awards.py [repository root]. Exit code 1 lists the problems.
"""
import json
import sys
from pathlib import Path

ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[2]
COPIES = ["App/Resources/awards.json", "android/app/src/main/assets/awards.json"]
APP_LANGUAGES = {"en", "fr", "es", "pt", "zh-Hans", "ar", "hi"}
# A win that must be found: if not, an award id points at the wrong Wikidata item.
KNOWN_WIN = ("Pulp Fiction", "palme-dor", 1994)
KNOWN_SERIES_WIN = ("Breaking Bad", "emmy-drama-series", 2013)


def awards_problems(awards: list, what: str) -> tuple:
    problems, ids = [], set()
    for award in awards:
        if not award.get("id") or not award.get("group"):
            problems.append(f"{what} without id or group: {award}")
            continue
        ids.add(award["id"])
        names = award.get("names") or {}
        if "en" not in names:
            problems.append(f"{award['id']}: no English name")
        unknown = set(names) - APP_LANGUAGES
        if unknown:
            problems.append(f"{award['id']}: names in languages the apps do not use: {sorted(unknown)}")
    return problems, ids


def entries_problems(titles: list, ids: set) -> list:
    problems = []
    for title in titles:
        if not title.get("titles"):
            problems.append(f"title without a name: {title}")
        for entry in title.get("awards") or []:
            if not (isinstance(entry, list) and len(entry) == 3 and entry[0] in ids and isinstance(entry[1], int) and entry[2] in (0, 1)):
                problems.append(f"{(title.get('titles') or ['?'])[0]}: bad award entry {entry}")
    return problems


def problems_of(document: dict) -> list:
    problems, ids = awards_problems(document.get("awards") or [], "award")
    if not ids:
        problems.append("no awards")
    films = document.get("films") or []
    if not films:
        problems.append("no films")
    # A film entry naming a series award would be one the released apps show as a film's.
    problems += entries_problems(films, ids)
    title, award_id, year = KNOWN_WIN
    if ids and not any(title in film.get("titles", []) and [award_id, year, 1] in film.get("awards", []) for film in films):
        problems.append(f"{title} should have won {award_id} in {year}")

    series_problems, series_ids = awards_problems(document.get("seriesAwards") or [], "series award")
    problems += series_problems
    if ids & series_ids:
        problems.append(f"ids both of a film and of a series award: {sorted(ids & series_ids)}")
    series = document.get("series") or []
    if not series_ids or not series:
        problems.append("no series awards or no series")
    problems += entries_problems(series, series_ids)
    title, award_id, year = KNOWN_SERIES_WIN
    if series_ids and not any(title in show.get("titles", []) and [award_id, year, 1] in show.get("awards", []) for show in series):
        problems.append(f"{title} should have won {award_id} in {year}")
    return problems


def main() -> int:
    paths = [ROOT / copy for copy in COPIES]
    missing = [str(path.relative_to(ROOT)) for path in paths if not path.exists()]
    if missing:
        print(f"::error::missing awards data: {', '.join(missing)} (run tools/awards/generate_awards.py)")
        return 1
    first, second = (path.read_bytes() for path in paths)
    problems = []
    if first != second:
        problems.append("the Apple and Android copies differ: regenerate both with tools/awards/generate_awards.py")
    try:
        problems += problems_of(json.loads(first))
    except json.JSONDecodeError as error:
        problems.append(f"not JSON: {error}")
    for problem in problems[:50]:
        print(f"::error file={COPIES[0]}::{problem}")
    print(f"{len(problems)} problem(s).")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
