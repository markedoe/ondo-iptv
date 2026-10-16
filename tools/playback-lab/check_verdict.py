"""Runs the app's freeze verdict (IPTVCore PlaybackHealth, ported line for line) on recorded samples.

    python3 run.py traces && python3 run.py heavy
    python3 check_verdict.py results-traces.jsonl results-heavy.jsonl

Each recording carries the cause measured on screen; a line starting with BAD means the verdict missed it.
The samples are libVLC's own counters, as the app reads them: they stand still while no data comes in, even
when the picture goes on from the buffer, so `buffer` (the network caching) is given as the app gives it.
Keep this file in step with PlaybackHealth.swift.
"""
import json
import sys

MINIMUM_FREEZE = 0.7
ENOUGH_DATA = 0.97
DATA_GAP = 1.0
SMOOTH_PACE = 0.97
LOST_SHARE = 0.02


def is_played(step):
    """A move of the position between two samples that is playback, not a seek."""
    return 0 < step <= 30


class PlaybackHealth:
    def __init__(self, buffer=0.0):
        self.buffer = buffer
        self.entries = []  # (sample, started)
        self.freezes = []
        self.still_since = None
        self.has_moved = False
        self.pictures_still_since = None

    def add(self, sample):
        if self.entries:
            last = self.entries[-1][0]
            if sample["received"] < last["received"] or sample["consumed"] < last["consumed"]:
                self.entries, self.still_since, self.has_moved = [], None, False
                self.pictures_still_since = None
        last = self.entries[-1][0] if self.entries else None
        if last is not None:
            self.note_pictures(sample, last)
        moved = last is not None and ((sample["media"] or 0) != (last["media"] or 0) or sample["shown"] != last["shown"])
        if last is not None and sample["media"] is not None and last["media"] is not None:
            if is_played(sample["media"] - last["media"]):
                self.has_moved = True
        if last is not None:
            if sample["playing"] and not moved and sample["media"] is not None and self.has_moved:
                self.still_since = self.still_since or sample
            elif self.still_since is not None:
                still, self.still_since = self.still_since, None
                freeze = self.freeze(still["clock"], sample["clock"])
                if freeze:
                    self.freezes.append(freeze)
        self.entries.append((sample, self.has_moved and sample["playing"] and sample["media"] is not None))
        while self.entries[0][0]["clock"] < sample["clock"] - 120:
            self.entries.pop(0)

    def note_pictures(self, sample, last):
        """Still pictures while the position goes on: a freeze of the picture alone (VLC, after a refill, starts the
        picture again at a key frame while the sound goes on)."""
        advanced = is_played((sample["media"] or 0) - (last["media"] or 0))
        if sample["playing"] and self.has_moved and advanced and sample["shown"] > 0 and sample["shown"] == last["shown"]:
            if self.pictures_still_since is None:
                self.pictures_still_since = sample["clock"]
        else:
            self.end_picture_freeze(sample["clock"])

    def end_picture_freeze(self, end):
        start, self.pictures_still_since = self.pictures_still_since, None
        if start is None or end - start < MINIMUM_FREEZE:
            return
        self.freezes.append({"start": start, "duration": end - start, "rate": self.received_rate(start - 5, end),
                             "gap": self.longest_gap(start - 3, end), "pictures_only": True})

    def window(self, start, end):
        return [s for s, _ in self.entries if start <= s["clock"] <= end]

    def received_rate(self, start, end):
        w = self.window(start, end)
        if len(w) < 2 or w[-1]["clock"] <= w[0]["clock"]:
            return 0
        return (w[-1]["received"] - w[0]["received"]) / (w[-1]["clock"] - w[0]["clock"])

    def longest_gap(self, start, end):
        longest, since = 0.0, None
        w = self.window(start, end)
        for previous, current in zip(w, w[1:]):
            if current["received"] == previous["received"]:
                since = previous["clock"] if since is None else since
                longest = max(longest, current["clock"] - since)
            else:
                since = None
        return longest

    def covered(self, start, end):
        """The part of a stall the buffer covers: the picture goes on from it while no data comes in; while data keeps
        coming in, the player is refilling and the picture stands still from the start."""
        return min(self.buffer, self.longest_gap(start - 0.75, end))

    def freeze(self, start, end):
        """The freeze a stall from start to end caused, if it outlasted what the buffer covered by MINIMUM_FREEZE."""
        covered = self.covered(start, end)
        duration = end - start - covered
        if duration < MINIMUM_FREEZE:
            return None
        return {"start": start + covered, "duration": duration, "rate": self.received_rate(start - 5, end),
                "gap": self.longest_gap(start - 3, end)}

    def played(self):
        wall = media = received = 0.0
        end = self.still_since["clock"] if self.still_since else float("inf")
        for (previous, started_before), (current, started) in zip(self.entries, self.entries[1:]):
            if started_before and started and current["clock"] < end:
                elapsed = current["clock"] - previous["clock"]
                wall += elapsed
                step = current["media"] - previous["media"]
                media += step if is_played(step) else 0
                received += current["received"] - previous["received"]
        return wall, media, received

    def verdict(self):
        if not self.entries or self.entries[-1][0]["clock"] - self.entries[0][0]["clock"] < 10:
            return "measuring", {}
        first, last = self.entries[0][0], self.entries[-1][0]
        wall, media, received = self.played()
        lost = last["lost"] - first["lost"]
        shown = last["shown"] - first["shown"] if last["shown"] > first["shown"] else int(media * 25)
        recent = [f for f in self.freezes if f["start"] >= first["clock"]]
        ongoing = self.still_since and self.freeze(self.still_since["clock"], last["clock"])
        if ongoing:
            recent.append(ongoing)
        needed = received / media if media >= 5 else None
        pace = media / wall if wall >= 5 else None
        info = {"freezes": len(recent), "lost %": round(100 * lost / max(shown + lost, 1), 1),
                "pace": pace and round(pace, 2), "needed Mb/s": needed and round(needed * 8e-6, 2),
                "received Mb/s": round(received / wall * 8e-6, 2) if wall else None}
        # Lost pictures point at the device when the data kept up: a player starved of data loses some too.
        if lost > 0 and lost / max(shown + lost, 1) > LOST_SHARE and (pace is None or pace >= SMOOTH_PACE):
            return "player", info
        if not recent:
            return ("smooth" if pace is None or pace >= SMOOTH_PACE else "network"), info
        if not needed:
            return "network", info
        starved = [f for f in recent if f["gap"] >= DATA_GAP or f["rate"] < needed * ENOUGH_DATA]
        return ("network" if len(starved) * 2 >= len(recent) else "player"), info


def samples(trace):
    for clock, media, received, consumed, shown, lost, playing, _ in trace:
        yield {"clock": clock, "media": media, "received": received, "consumed": consumed, "shown": shown,
               "lost": lost, "playing": bool(playing)}


if __name__ == "__main__":
    for path in sys.argv[1:]:
        for line in open(path):
            result = json.loads(line)
            if "trace" not in result:
                print(f"BAD {result['label']}: no samples ({result.get('crash', '')[-200:]})")
                continue
            caching = next((int(o.split("=")[1]) / 1000 for o in result["options"] if o.startswith(":network-caching=")), 0)
            health = PlaybackHealth(buffer=caching)
            for sample in samples(result["trace"]):
                health.add(sample)
            verdict, info = health.verdict()
            mark = "OK " if verdict == result["expected"] else "BAD"
            print(f"{mark} {result['label']:24} expected {result['expected']:8} got {verdict:8} {info}")
