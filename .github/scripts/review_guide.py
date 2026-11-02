#!/usr/bin/env python3
"""Writes the TV guide (XMLTV) of the review playlist's test channel: site/review/guide.xml.

App Review and the Google Play reviewer test Ondo with a playlist hosted on the project's site
(site/review/ondo-review.m3u). Its live channel is our own test pattern; this guide gives it made-up programmes, one
an hour, from two days ago to four weeks ahead, so that now/next and the guide grid have something to show. The
website workflow (pages.yml) writes it again at each deployment and every week.

Usage: review_guide.py [output file] [--today YYYY-MM-DD]
"""
import argparse
import datetime
import sys
from pathlib import Path
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[2]
CHANNEL_ID = "ondo.test"
CHANNEL_NAME = "Ondo Test Channel"
DAYS_BEFORE = 2
DAYS_AFTER = 28
# Made-up programme titles and descriptions: the channel shows a test pattern, whatever the guide says.
PROGRAMMES = [
    ("Colour Bars at Dawn", "Seven bars, one tone, no surprises."),
    ("The Test Card Hour", "A calm hour with the channel's test card and its clock."),
    ("Tone and Pattern", "A steady tone over a moving pattern."),
    ("Signal Check", "The picture, the sound and a running clock, live from the test studio."),
    ("Night Pattern", "The test pattern keeps the channel company overnight."),
    ("Calibration Corner", "Bars and gradients to set up a screen."),
]


def guide(today: datetime.date) -> str:
    start = datetime.datetime.combine(today - datetime.timedelta(days=DAYS_BEFORE), datetime.time(), datetime.timezone.utc)
    hours = (DAYS_BEFORE + DAYS_AFTER) * 24
    lines = ['<?xml version="1.0" encoding="UTF-8"?>',
             '<tv generator-info-name="Ondo review guide">',
             f'  <channel id="{CHANNEL_ID}">',
             f'    <display-name>{escape(CHANNEL_NAME)}</display-name>',
             '  </channel>']
    for hour in range(hours):
        begin = start + datetime.timedelta(hours=hour)
        end = begin + datetime.timedelta(hours=1)
        title, description = PROGRAMMES[hour % len(PROGRAMMES)]
        lines += [f'  <programme start="{begin:%Y%m%d%H%M%S} +0000" stop="{end:%Y%m%d%H%M%S} +0000" channel="{CHANNEL_ID}">',
                  f'    <title lang="en">{escape(title)}</title>',
                  f'    <desc lang="en">{escape(description)}</desc>',
                  '  </programme>']
    lines.append('</tv>')
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("output", nargs="?", default=str(ROOT / "site/review/guide.xml"))
    parser.add_argument("--today", type=datetime.date.fromisoformat,
                        default=datetime.datetime.now(datetime.timezone.utc).date())
    arguments = parser.parse_args()
    Path(arguments.output).write_text(guide(arguments.today), encoding="utf-8")
    print(f"{arguments.output}: {(DAYS_BEFORE + DAYS_AFTER) * 24} programmes from {arguments.today - datetime.timedelta(days=DAYS_BEFORE)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
