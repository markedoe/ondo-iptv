#!/usr/bin/env python3
"""The review playlist holds only content we have the rights to, and answers.

App Review and the Google Play reviewer test Ondo with site/review/ondo-review.m3u, hosted on the project's site (the
apps ship no playlist or channel). It must stay what the review notes say it is: our own test channel and series, and
Blender Foundation films under Creative Commons, from the hosts listed in site/review/README.md.

1. Every address in the playlist (streams, logos, the guide) is https on an allowed host: our site, or one of the
   hosts of the open films. A host not on the list is a third party whose rights nobody checked.
2. The playlist is an M3U with a guide (url-tvg) on our site, and each channel's tvg-id is in the guide
   (site/review/guide.xml).
3. With --online URL (the release workflows, with the Pages address of main): the hosted playlist is fetched, the same
   rules apply to it, every address answers (HEAD, or a one-byte range when HEAD is refused), and the hosted guide
   covers the present hour.

Usage: check_review_playlist.py [repository root] [--online URL]. Exit code 1 lists each problem.
"""
import argparse
import datetime
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ElementTree
from pathlib import Path

# Our site (GitHub Pages of the repository) and the hosts of the open films (site/review/README.md gives each source).
OWN_SITE = re.compile(r"^https://[\w-]+\.github\.io/ondo-iptv/review/")
ALLOWED_HOSTS = {
    "download.blender.org",          # Blender Foundation, the films' own downloads
    "test-streams.mux.dev",          # Mux's public test streams (Big Buck Bunny)
    "demo.unified-streaming.com",    # Unified Streaming's public demo streams (Tears of Steel)
}
ADDRESS = re.compile(r"""https?://[^\s"'<>,]+""")
ATTRIBUTE = re.compile(r'([\w-]+)="([^"]*)"')
TIMEOUT = 20


def allowed(address: str) -> bool:
    if OWN_SITE.match(address):
        return True
    parsed = urllib.parse.urlsplit(address)
    return parsed.scheme == "https" and parsed.hostname in ALLOWED_HOSTS


def addresses(playlist: str) -> list:
    """Every address in the playlist, in order, once: header and entry attributes and stream lines."""
    found = []
    for address in ADDRESS.findall(playlist):
        if address not in found:
            found.append(address)
    return found


def channel_ids(playlist: str) -> list:
    ids = []
    for line in playlist.splitlines():
        if line.startswith("#EXTINF"):
            attributes = dict(ATTRIBUTE.findall(line))
            if attributes.get("tvg-type", "live").lower() == "live" and attributes.get("tvg-id"):
                ids.append(attributes["tvg-id"])
    return ids


def guide_address(playlist: str):
    header = playlist.splitlines()[0] if playlist else ""
    attributes = dict(ATTRIBUTE.findall(header))
    return attributes.get("url-tvg") or attributes.get("x-tvg-url")


def check_playlist(playlist: str, where: str, problems: list) -> None:
    if not playlist.startswith("#EXTM3U"):
        problems.append(f"{where}: not an M3U playlist (#EXTM3U)")
        return
    if not re.search(r"^https?://", playlist, re.MULTILINE):
        problems.append(f"{where}: no stream")
    for address in addresses(playlist):
        if not allowed(address):
            problems.append(f"{where}: {address} is not on our site nor an allowed host of the open films "
                            "(.github/scripts/check_review_playlist.py ALLOWED_HOSTS, site/review/README.md)")
    guide = guide_address(playlist)
    if not guide or not OWN_SITE.match(guide):
        problems.append(f"{where}: the guide (url-tvg) must be on our site, site/review/guide.xml")


def guide_ids(guide: bytes) -> set:
    return {channel.get("id") for channel in ElementTree.fromstring(guide).iter("channel")}


def covers(guide: bytes, now: datetime.datetime) -> bool:
    stamp = now.strftime("%Y%m%d%H%M%S")
    for programme in ElementTree.fromstring(guide).iter("programme"):
        if programme.get("start", "")[:14] <= stamp < programme.get("stop", "")[:14]:
            return True
    return False


def fetch(address: str, method: str = "GET", byte_range: bool = False):
    request = urllib.request.Request(address, method=method, headers={"User-Agent": "Ondo review check"})
    if byte_range:
        request.add_header("Range", "bytes=0-0")
    return urllib.request.urlopen(request, timeout=TIMEOUT)


def answers(address: str):
    """None when the address answers, else why not. HEAD first; a one-byte range when HEAD is refused; twice, so that
    one dropped connection does not stop a release."""
    reason = None
    for method, byte_range in (("HEAD", False), ("GET", True)) * 2:
        try:
            with fetch(address, method, byte_range) as response:
                if response.status < 400:
                    return None
                reason = f"HTTP {response.status}"
        except urllib.error.HTTPError as error:
            reason = f"HTTP {error.code}"
        except (urllib.error.URLError, OSError) as error:
            reason = str(getattr(error, "reason", error))
    return reason


def check_online(address: str, problems: list) -> None:
    try:
        with fetch(address) as response:
            playlist = response.read().decode("utf-8")
    except (urllib.error.URLError, OSError) as error:
        problems.append(f"{address} does not answer: {getattr(error, 'reason', error)}")
        return
    check_playlist(playlist, address, problems)
    for item in addresses(playlist):
        reason = answers(item)
        print(f"{'ok' if reason is None else reason}: {item}")
        if reason is not None:
            problems.append(f"{item} does not answer ({reason})")
    guide = guide_address(playlist)
    if guide:
        try:
            with fetch(guide) as response:
                data = response.read()
            if not covers(data, datetime.datetime.now(datetime.timezone.utc)):
                problems.append(f"{guide} has no programme now: the website workflow writes it again (pages.yml)")
            for channel in channel_ids(playlist):
                if channel not in guide_ids(data):
                    problems.append(f"{guide} has no channel {channel}")
        except (urllib.error.URLError, OSError, ElementTree.ParseError) as error:
            problems.append(f"{guide} cannot be read: {getattr(error, 'reason', error)}")


def check_repository(root: Path, problems: list) -> None:
    playlist_path = root / "site/review/ondo-review.m3u"
    if not playlist_path.is_file():
        problems.append("site/review/ondo-review.m3u is missing: App Review tests the apps with it")
        return
    playlist = playlist_path.read_text(encoding="utf-8")
    check_playlist(playlist, "site/review/ondo-review.m3u", problems)
    guide_path = root / "site/review/guide.xml"
    if not guide_path.is_file():
        problems.append("site/review/guide.xml is missing (.github/scripts/review_guide.py writes it)")
        return
    try:
        ids = guide_ids(guide_path.read_bytes())
    except ElementTree.ParseError as error:
        problems.append(f"site/review/guide.xml is not XML: {error}")
        return
    for channel in channel_ids(playlist):
        if channel not in ids:
            problems.append(f"site/review/guide.xml has no channel {channel} (tvg-id in site/review/ondo-review.m3u)")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("root", nargs="?", default=str(Path(__file__).resolve().parents[2]))
    parser.add_argument("--online", metavar="URL", help="the hosted playlist to fetch and check")
    arguments = parser.parse_args()
    problems = []
    if arguments.online:
        check_online(arguments.online, problems)
    else:
        check_repository(Path(arguments.root), problems)
    for problem in problems:
        print(f"::error::{problem}")
    print(f"Review playlist: {len(problems)} problem(s).")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
