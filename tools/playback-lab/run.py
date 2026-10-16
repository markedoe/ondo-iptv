"""Plays every scenario of a suite with every player setting and prints a table of the freezes seen on screen.

    python3 run.py live        # live channels: ideal, bursty, stalls, slow; buffer sizes and VLC's clock
    python3 run.py vod         # films: ideal, stall, dropped connection, slow
    python3 run.py film        # films in four formats on three networks: first picture and seeks
    python3 run.py resume      # films resumed at 60 s, as the app does with `:start-time`
    python3 run.py zap         # how long until the picture shows, with and without a provider's head start
    python3 run.py reactions   # Ondo's former reaction to every VLC event, on screen
    python3 run.py traces      # libVLC's counters as the app reads them, for check_verdict.py
    python3 run.py heavy       # the same, with a device that does not keep up (4K on half a core)
    python3 run.py check       # what CI checks: each case against a limit, a non-zero exit when one fails

`repeat=N` as a second argument plays each case N times. Results go to results-<suite>.jsonl. Start
`faulty_server.py` first (and `make_media.sh` once). Three runs at a time on cores 0-2; the heavy suite runs
alone on core 3, next to a busy process.
"""
import json
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

HERE = Path(__file__).parent
BASE = "http://127.0.0.1:8765"
SECONDS = 45

LIVE = {
    "ideal": "rate=1.0",
    "bursty-1s": "rate=1.0&burst=1000",
    "bursty-2.5s": "rate=1.0&burst=2500",
    "stalls-1.5s+2.5s": "rate=1.0&stalls=12:1.5,28:2.5",
    "stall-5s": "rate=1.0&stalls=20:5",
    "slow-95%": "rate=0.95",
}
SUITES = {
    # suite: (path, scenarios, {setting: (media options, app reaction)})
    "live": ("live/test720.ts", LIVE, {
        "2 s": ([":network-caching=2000"], False),
        "3 s": ([":network-caching=3000"], False),
        "5 s": ([":network-caching=5000"], False),
        "10 s": ([":network-caching=10000"], False),
        "2 s + clock-synchro=0": ([":network-caching=2000", ":clock-synchro=0"], False),
        "5 s + clock-synchro=0": ([":network-caching=5000", ":clock-synchro=0"], False),
    }),
    "vod": ("vod/test720.ts", {
        "ideal": "rate=0",
        "stall-4s": "rate=0&stall_at=30:4",
        "drop-at-30s": "rate=0&drop_at=30",
        "slow-90%": "rate=0.9",
    }, {
        "3 s": ([":network-caching=3000"], False),
        "5 s": ([":network-caching=5000"], False),
    }),
    "zap": ("live/test720.ts", {
        "real time": "rate=1.0",
        "8 s head start": "rate=1.0&prefill=8",
    }, {
        "2 s": ([":network-caching=2000"], False),
        "3 s": ([":network-caching=3000"], False),
        "4 s": ([":network-caching=4000"], False),
        "5 s": ([":network-caching=5000"], False),
    }),
    "reactions": ("live/test720.ts", {
        "ideal": "rate=1.0",
        "bursty-1s": "rate=1.0&burst=1000",
    }, {
        "2 s": ([":network-caching=2000"], False),
        "2 s + reactions (Ondo before)": ([":network-caching=2000"], True),
    }),
}
FORMATS = ["film.mp4", "film-faststart.mp4", "film.mkv", "film.ts"]
NETWORKS = {"fast": "rate=0", "far (150 ms, 3x)": "latency=150&rate=3", "slow (300 ms, 1.5x)": "latency=300&rate=1.5"}
FILM_SETTINGS = {
    "1 s": [":network-caching=1000"],
    "3 s": [":network-caching=3000"],
    "5 s": [":network-caching=5000"],
    "1 s + fast seek": [":network-caching=1000", ":input-fast-seek"],
}
# Skip forward 10 s, back 10 s, then jump to 1:30, as a viewer does.
FILM_SEEKS = [[5, "+10"], [12, "-10"], [19, 90]]

# libVLC's counters, with the verdict the app should reach given what the screen showed (check_verdict.py).
TRACES = {
    "traces": [
        ("live ideal, 2 s", "live/test720.ts?rate=1.0", ":network-caching=2000", "smooth"),
        ("live bursty-2.5s, 2 s", "live/test720.ts?rate=1.0&burst=2500", ":network-caching=2000", "smooth"),
        ("live stall-5s, 2 s", "live/test720.ts?rate=1.0&stalls=20:5", ":network-caching=2000", "network"),
        ("live stall-5s, 5 s", "live/test720.ts?rate=1.0&stalls=20:5", ":network-caching=5000", "smooth"),
        ("live slow-95%, 2 s", "live/test720.ts?rate=0.95", ":network-caching=2000", "network"),
        ("vod slow-90%, 3 s", "vod/test720.ts?rate=0.9", ":network-caching=3000", "network"),
        ("vod ideal, 3 s", "vod/test720.ts?rate=0", ":network-caching=3000", "smooth"),
    ],
    "heavy": [
        ("live 4K, busy device", "live/heavy.ts?rate=1.0", ":network-caching=2000", "player"),
        ("vod 4K, busy device", "vod/heavy.ts?rate=0", ":network-caching=3000", "player"),
    ],
}


def no_freeze(r):
    return [f for f in r.get("freeze_list", []) if f["seconds"] >= 0.5] == [] and r.get("freezes", 0) == len(
        [f for f in r.get("freeze_list", []) if f["seconds"] < 0.5])


def seeks_ok(r, seconds, landing):
    return len(r.get("seeks", [])) == len(FILM_SEEKS) and all(
        s.get("seconds") is not None and s["seconds"] <= seconds and abs(s["landing_error"]) <= landing for s in r["seeks"])


# What CI checks (the defaults Ondo ships: 3 s for live channels, 1 s for films). The limits leave room for shared
# machines: they catch a regression (a freeze that comes back, a start or a seek that hangs), not a 10 % drift.
CHECKS = [
    ("a 1.5 s and a 2.5 s stop of a live channel do not show with 3 s of buffer",
     {"url": "live/test720.ts?rate=1.0&stalls=12:1.5,28:2.5", "options": [":network-caching=3000"], "seconds": 40},
     no_freeze),
    ("a live channel sent in 2.5 s bursts never freezes",
     {"url": "live/test720.ts?rate=1.0&burst=2500", "options": [":network-caching=3000"], "seconds": 40}, no_freeze),
    ("a live channel shows within its 3 s of buffer + 3 s",
     {"url": "live/test720.ts?rate=1.0", "options": [":network-caching=3000"], "seconds": 20},
     lambda r: r.get("first_picture_seconds") is not None and r["first_picture_seconds"] <= 6),
    ("a film starts within 2 s and its seeks land on time within 3 s",
     {"url": "vod/film-faststart.mp4?rate=0", "options": [":network-caching=1000"], "seconds": 32, "seeks": FILM_SEEKS},
     lambda r: r.get("first_picture_seconds") is not None and r["first_picture_seconds"] <= 2 and seeks_ok(r, 3, 0.5)),
    ("an MKV film on a distant server starts within 5 s and seeks within 4 s",
     {"url": "vod/film.mkv?latency=150&rate=3", "options": [":network-caching=1000"], "seconds": 32, "seeks": FILM_SEEKS},
     lambda r: r.get("first_picture_seconds") is not None and r["first_picture_seconds"] <= 5 and seeks_ok(r, 4, 0.5)),
    ("a film resumes at 1:00 within 3 s on a distant server",
     {"url": "vod/film.mp4?latency=150&rate=3", "options": [":network-caching=1000", ":start-time=60"], "seconds": 12},
     lambda r: r.get("first_picture_seconds") is not None and r["first_picture_seconds"] <= 3
     and abs(r["first_frame_number"] / 25 - 60) <= 1),
    ("a 4 s stop of the server does not freeze a film",
     {"url": "vod/film.mkv?rate=3&stall_at=30:4", "options": [":network-caching=1000"], "seconds": 45}, no_freeze),
    ("a film whose connection drops goes on",
     {"url": "vod/film.mkv?rate=0&drop_at=30", "options": [":network-caching=1000"], "seconds": 45},
     lambda r: no_freeze(r) and r.get("played_seconds", 0) >= 40),
]
# libVLC's counters recorded during these runs must give the verdict the screen showed.
CHECK_TRACES = [t for t in TRACES["traces"] if t[0] in
                ("live bursty-2.5s, 2 s", "live stall-5s, 2 s", "vod slow-90%, 3 s", "vod ideal, 3 s")]


def check():
    from check_verdict import PlaybackHealth, samples

    def run_check(case):
        name, spec, passes = case
        result = harness(dict(spec, url=f"{BASE}/{spec['url']}", label=name), "0-2")
        return name, ("crash" not in result and passes(result)), result

    def run_trace(case):
        label, path, option, expected = case
        result = harness({"url": f"{BASE}/{path}", "options": [option], "seconds": 40, "label": label, "trace": True}, "0-2")
        health = PlaybackHealth(buffer=int(option.split("=")[1]) / 1000)
        for sample in samples(result.get("trace", [])):
            health.add(sample)
        verdict, _ = health.verdict()
        return f"verdict of {label}: {expected}", verdict == expected, dict(result, verdict=verdict)

    with ThreadPoolExecutor(max_workers=3) as pool:
        outcomes = list(pool.map(run_check, CHECKS)) + list(pool.map(run_trace, CHECK_TRACES))
    for name, ok, result in outcomes:
        detail = {k: result.get(k) for k in ("first_picture_seconds", "freezes", "freeze_list", "seeks", "played_seconds",
                                               "verdict", "crash") if result.get(k) not in (None, [], 0)}
        print(f"{'PASS' if ok else 'FAIL'}  {name}  {json.dumps(detail)}", flush=True)
    return outcomes


def harness(spec, cores):
    cmd = ["taskset", "-c", cores, sys.executable, "-W", "ignore", str(HERE / "harness.py"), json.dumps(spec)]
    out = subprocess.run(cmd, capture_output=True, text=True, timeout=spec["seconds"] + 60)
    try:
        return json.loads(out.stdout.strip().splitlines()[-1])
    except Exception:  # noqa: BLE001
        return {"label": spec["label"], "options": spec["options"], "crash": out.stderr[-500:]}


def job(path, scenario, query, setting, options, reaction, rep):
    spec = {"url": f"{BASE}/{path}?{query}", "options": options, "seconds": SECONDS, "app_reaction": reaction,
            "label": f"{scenario} | {setting}"}
    result = harness(spec, "0-2")
    result.update({"scenario": scenario, "setting": setting, "rep": rep})
    print(f"{result['label']}: {result.get('freezes')} freezes, {result.get('frozen_seconds')} s frozen, "
          f"first picture at {result.get('first_picture_seconds')} s", flush=True)
    return result


def traces(suite):
    heavy = suite == "heavy"
    busy = subprocess.Popen(["taskset", "-c", "3", sys.executable, "-c", "while True: pass"]) if heavy else None
    results = []
    try:
        for label, path, option, expected in TRACES[suite]:
            spec = {"url": f"{BASE}/{path}", "options": [option], "seconds": 50, "label": label, "trace": True,
                    "instance_options": ["--avcodec-threads=1"] if heavy else []}
            result = harness(spec, "3" if heavy else "0-2")
            result["expected"] = expected
            print(f"{label}: {result.get('freezes')} freezes on screen, {result.get('lost_pictures')} pictures lost",
                  flush=True)
            results.append(result)
    finally:
        if busy:
            busy.kill()
    return results


def table(results, scenarios, settings):
    print("\n| Scenario | " + " | ".join(settings) + " |")
    print("|---|" + "---|" * len(settings))
    for scenario in scenarios:
        cells = []
        for setting in settings:
            runs = [r for r in results if r["scenario"] == scenario and r["setting"] == setting]
            cells.append(" · ".join("crash" if "crash" in r else f"{r['freezes']} / {r['frozen_seconds']} s"
                                    for r in runs))
        print(f"| {scenario} | " + " | ".join(cells) + " |")


def film_job(name, network, query, setting, options, resume):
    spec = {"url": f"{BASE}/vod/{name}?{query}", "options": options + ([":start-time=60"] if resume else []),
            "seconds": 12 if resume else 32, "seeks": [] if resume else FILM_SEEKS,
            "label": f"{name} | {network} | {setting}"}
    result = harness(spec, "0-2")
    result.update({"scenario": f"{name} | {network}", "setting": setting})
    seeks = " ".join(f"{s['seconds']}s({s.get('landing_error')})" for s in result.get("seeks", []))
    print(f"{result['label']}: first picture {result.get('first_picture_seconds')} s "
          f"(frame {result.get('first_frame_number')}), {result.get('freezes')} freezes, seeks {seeks}", flush=True)
    return result


def film_table(results, settings, resume):
    print("\n| File | Network | " + " | ".join(settings) + " |")
    print("|---|---|" + "---|" * len(settings))
    for name in FORMATS:
        for network in NETWORKS:
            cells = []
            for setting in settings:
                r = next(r for r in results if r["scenario"] == f"{name} | {network}" and r["setting"] == setting)
                if "crash" in r or r.get("first_picture_seconds") is None:
                    cells.append("no picture")
                elif resume:
                    cells.append(f"{r['first_picture_seconds']} s at {r['first_frame_number'] / 25:.1f}")
                else:
                    seeks = " / ".join(f"{s['seconds']}" if s.get("seconds") is not None else "–" for s in r["seeks"])
                    cells.append(f"{r['first_picture_seconds']} s; {seeks}")
            print(f"| {name} | {network} | " + " | ".join(cells) + " |")


if __name__ == "__main__":
    suite = sys.argv[1] if len(sys.argv) > 1 else "live"
    repeat = int(sys.argv[2].split("=")[1]) if len(sys.argv) > 2 else 1
    if suite == "check":
        outcomes = check()
        with open(HERE / "results-check.jsonl", "w") as f:
            for name, ok, result in outcomes:
                f.write(json.dumps({"check": name, "ok": ok, **result}) + "\n")
        sys.exit(0 if all(ok for _, ok, _ in outcomes) else 1)
    if suite in ("film", "resume"):
        resume = suite == "resume"
        settings = {k: v for k, v in FILM_SETTINGS.items() if not resume or k in ("1 s", "3 s")}
        jobs = [(name, network, query, setting, options, resume) for name in FORMATS
                for network, query in NETWORKS.items() for setting, options in settings.items()]
        with ThreadPoolExecutor(max_workers=3) as pool:
            results = list(pool.map(lambda j: film_job(*j), jobs))
        film_table(results, settings, resume)
    elif suite in TRACES:
        results = traces(suite)
    else:
        path, scenarios, settings = SUITES[suite]
        jobs = [(path, sc, q, st, *conf, rep) for rep in range(repeat)
                for sc, q in scenarios.items() for st, conf in settings.items()]
        with ThreadPoolExecutor(max_workers=3) as pool:
            results = list(pool.map(lambda j: job(*j), jobs))
        table(results, scenarios, settings)
    with open(HERE / f"results-{suite}.jsonl", "w") as f:
        for r in results:
            f.write(json.dumps(r) + "\n")
