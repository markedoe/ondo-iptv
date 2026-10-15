"""Plays every scenario of a suite with every player setting and prints a table of the freezes seen on screen.

    python3 run.py live        # live channels: ideal, bursty, stalls, slow; buffer sizes and VLC's clock
    python3 run.py vod         # films: ideal, stall, dropped connection, slow
    python3 run.py zap         # how long until the picture shows, with and without a provider's head start
    python3 run.py reactions   # Ondo's former reaction to every VLC event, on screen
    python3 run.py traces      # libVLC's counters as the app reads them, for check_verdict.py
    python3 run.py heavy       # the same, with a device that does not keep up (4K on half a core)

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


if __name__ == "__main__":
    suite = sys.argv[1] if len(sys.argv) > 1 else "live"
    repeat = int(sys.argv[2].split("=")[1]) if len(sys.argv) > 2 else 1
    if suite in TRACES:
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
