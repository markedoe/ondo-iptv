#!/usr/bin/env python3
"""Closed testing catches up with internal testing (.github/workflows/play-catchup.yml, release-android.yml).

Every build of internal testing also goes to closed testing (CLAUDE.md). While Google reviews a closed testing release,
the next one waits: promoting commits with changesInReviewBehavior ERROR_IF_IN_REVIEW (Fastfile, KEEP_PLAY_REVIEW), so
Google Play refuses it rather than cancel and restart that review. No API shows whether a review is pending; that
refusal is how it shows. Once the review is over, the newest internal build is promoted, once: alpha then holds it.

  play_catchup.py decide           reads the tracks inside an edit that is deleted, never committed, and writes
                                   version_code=<code> to GITHUB_OUTPUT when internal testing is ahead of closed testing
  play_catchup.py outcome LOG      after a refused promotion: a notice and exit 0 when Google Play refused it because
                                   changes are in review, an error and exit 1 for anything else

Standard library only, with play_status.py's token and client; the log gets version codes and statuses only.
"""
import importlib.util
import json
import os
import re
import sys
from pathlib import Path

_spec = importlib.util.spec_from_file_location("play_status", Path(__file__).resolve().parent / "play_status.py")
play_status = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(play_status)

# Releases testers get: a draft of internal testing has reached no one.
DELIVERED = {"completed", "inProgress", "halted"}

# Google Play's refusal of a commit made with ERROR_IF_IN_REVIEW while changes are in review. "Sent for review" (managed
# publishing on, changesNotSentForReview) is another refusal, which must not pass for this one.
IN_REVIEW = re.compile(r"(?i)\bin review\b|ERROR_IF_IN_REVIEW|currently being reviewed|\bunder review\b")


def newest(tracks: list, name: str, statuses=None):
    """The highest version code of track [name] among its releases of [statuses] (all when None), or None."""
    codes = [
        int(code)
        for track in tracks if track.get("track") == name
        for release in track.get("releases", []) if statuses is None or release.get("status") in statuses
        for code in release.get("versionCodes", []) if str(code).isdigit()
    ]
    return max(codes) if codes else None


def decide(tracks: list):
    """The version code to promote to closed testing: the newest delivered internal build when closed testing holds
    an older one (any status: a draft put there by hand counts as taken), else None."""
    internal = newest(tracks, "internal", DELIVERED)
    alpha = newest(tracks, "alpha")
    if internal is None or (alpha is not None and internal <= alpha):
        return None
    return internal


def refused_for_review(log: str) -> bool:
    return bool(IN_REVIEW.search(log))


def read_tracks(client, package: str = play_status.PACKAGE) -> list:
    """The tracks, read inside an edit that is always deleted, never committed."""
    base = f"{play_status.PUBLISHER}{package}/edits"
    edit = client.call("POST", base, body={})
    try:
        return client.call("GET", f"{base}/{edit['id']}/tracks").get("tracks", [])
    finally:
        client.call("DELETE", f"{base}/{edit['id']}")


def write_output(line: str) -> None:
    output = os.environ.get("GITHUB_OUTPUT")
    if output:
        with open(output, "a", encoding="utf-8") as file:
            file.write(line + "\n")


def main_decide() -> int:
    raw = os.environ.get("PLAY_SERVICE_ACCOUNT_JSON", "")
    if not raw.strip():
        print("::error::The secret PLAY_SERVICE_ACCOUNT_JSON is not set")
        return 1
    try:
        client = play_status.Client(play_status.access_token(json.loads(raw)))
        tracks = read_tracks(client)
    except (ValueError, KeyError):
        print("::error::PLAY_SERVICE_ACCOUNT_JSON is not a service account key (JSON with client_email and private_key)")
        return 1
    except (play_status.ApiError, RuntimeError, OSError) as error:
        print(play_status.safe(f"::error::Google Play could not be read: {error}"))
        return 1
    internal, alpha = newest(tracks, "internal", DELIVERED), newest(tracks, "alpha")
    print(f"Internal testing: {internal or 'nothing'}; closed testing: {alpha or 'nothing'}")
    code = decide(tracks)
    if code is None:
        print("Closed testing is up to date: nothing to do.")
        return 0
    print(f"Closed testing is behind: version code {code} goes there, unless a review is in progress.")
    write_output(f"version_code={code}")
    return 0


def main_outcome(path: str) -> int:
    log = Path(path).read_text(encoding="utf-8", errors="replace")
    if refused_for_review(log):
        print("::notice::Google Play has changes in review: closed testing will catch up after Google's review "
              "(Play catch-up, every hour).")
        return 0
    print("::error::Google Play refused the closed testing release (see the log above): internal testing has it, closed "
          "testing does not yet. Play catch-up tries again every hour; Promote Android sends it by hand.")
    return 1


def main(argv: list) -> int:
    if argv[1:2] == ["decide"]:
        return main_decide()
    if argv[1:2] == ["outcome"] and len(argv) == 3:
        return main_outcome(argv[2])
    print("usage: play_catchup.py decide | outcome LOG", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
