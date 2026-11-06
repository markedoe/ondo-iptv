#!/usr/bin/env python3
"""Puts the Google Play listing screenshots into fastlane/metadata-android, for every listing language.

The screenshot tests (android/app/src/test/kotlin/.../PlayStoreScreenshots.kt) write
android/app/src/test/screenshots/play/<locale>/<kind>/NN-name.png; each language of the listing
(fastlane/metadata-android/<locale>/) gets its own, or those of the language it shares them with (ALIASES: es-419
shows the Spanish ones). Each listing language must get every kind (phone, 7" and 10" tablets, TV) with 2 to 8 images
(Google Play's limits), or nothing is copied and the run fails: the upload never sends a listing short of a kind.

Usage: listing_screenshots.py [--only "LOCALE LOCALE…"] <play screenshots folder> <metadata-android folder>
"""
import argparse
import shutil
import sys
from pathlib import Path

KINDS = ("phoneScreenshots", "sevenInchScreenshots", "tenInchScreenshots", "tvScreenshots")
# A listing language rendered in another one's screenshots.
ALIASES = {"es-419": "es-ES"}
LIMITS = (2, 8)


def plan(play: Path, metadata: Path, only: list | None = None) -> tuple[dict, list]:
    """What each listing language gets ({(locale, kind): [png]}), and the problems that stop the copy."""
    locales = sorted(p.name for p in metadata.iterdir() if p.is_dir() and not p.name.startswith("."))
    if only:
        unknown = sorted(set(only) - set(locales))
        if unknown:
            return {}, [f"{', '.join(unknown)}: not a language of the listing ({metadata})"]
        locales = [locale for locale in locales if locale in only]
    copies, problems = {}, []
    for locale in locales:
        source = ALIASES.get(locale, locale)
        for kind in KINDS:
            shots = sorted((play / source / kind).glob("*.png"))
            if not LIMITS[0] <= len(shots) <= LIMITS[1]:
                problems.append(f"{locale} {kind}: {len(shots)} screenshots in {play / source / kind} "
                                f"(Google Play takes {LIMITS[0]} to {LIMITS[1]})")
            copies[(locale, kind)] = shots
    return copies, problems


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--only", default="", help="these listing languages only, separated by spaces (default: all)")
    parser.add_argument("play", type=Path)
    parser.add_argument("metadata", type=Path)
    args = parser.parse_args()
    copies, problems = plan(args.play, args.metadata, args.only.split())
    if problems:
        for problem in problems:
            print(f"::error::{problem}")
        return 1
    for (locale, kind), shots in copies.items():
        target = args.metadata / locale / "images" / kind
        # Only today's screenshots: none left from an earlier copy.
        shutil.rmtree(target, ignore_errors=True)
        target.mkdir(parents=True)
        for shot in shots:
            shutil.copyfile(shot, target / shot.name)
        print(f"{locale} {kind}: {len(shots)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
