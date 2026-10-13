"""Captures the Mac App Store screenshots of a Debug build of Ondo.

Usage: capture_mac_screenshots.py <Ondo.app> <output dir> <locale, e.g. en-US>

Each page is opened at launch with `-uitest-route` on the demo catalogue; the app gives its window a 16:10
frame and prints it (`WINDOW_RECT x,y,w,h SCALE s`). The window is captured with `screencapture` and
resized to a size the App Store accepts (2880×1800, 2560×1600, 1440×900 or 1280×800)."""
import pathlib
import re
import subprocess
import sys
import threading
import time

PAGES = [
    ("01-home", "home"),
    ("02-movie", "movie:Northern Lights"),
    ("03-series", "series:Harbor Lights"),
    ("04-movies", "movies"),
    ("05-channels", "live"),
    ("06-tv-guide", "guide"),
]
ACCEPTED = [(2880, 1800), (2560, 1600), (1440, 900), (1280, 800)]

app, target, locale = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), sys.argv[3]
language = locale.split("-")[0]
executable = app / "Contents" / "MacOS" / "Ondo"
target = target / "mac" / locale
target.mkdir(parents=True, exist_ok=True)


def capture(name, route):
    arguments = [str(executable), "-uitest-reset", "-uitest-fixture", "-uitest-showcase",
                 "-uitest-route", route, "-uitest-window", "1",
                 "-AppleLanguages", f"({language})", "-AppleLocale", locale.replace("-", "_")]
    process = subprocess.Popen(arguments, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    found = {}
    output = []

    def read():
        for line in process.stdout:
            output.append(line.rstrip())
            match = re.search(r"WINDOW_RECT (\d+),(\d+),(\d+),(\d+) SCALE (\d+)", line)
            if match:
                found["rect"] = [int(value) for value in match.groups()]

    threading.Thread(target=read, daemon=True).start()
    deadline = time.time() + 60
    while "rect" not in found and time.time() < deadline and process.poll() is None:
        time.sleep(0.5)
    if "rect" not in found:
        process.kill()
        subprocess.run(["screencapture", "-x", str(target / f"{name}-failed.png")])
        print("\n".join(output[-40:]))
        sys.exit(f"{name}: the app did not report its window (exit code {process.poll()})")
    time.sleep(12)  # catalogue, artwork and guide
    x, y, width, height, scale = found["rect"]
    raw = target / f"{name}-raw.png"
    subprocess.run(["screencapture", "-x", "-R", f"{x},{y},{width},{height}", str(raw)], check=True)
    process.terminate()
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
    pixels = width * scale
    out_width, out_height = next(((w, h) for w, h in ACCEPTED if w <= pixels), ACCEPTED[-1])
    out = target / f"{name}-mac.png"
    subprocess.run(["sips", "-z", str(out_height), str(out_width), str(raw), "--out", str(out)],
                   check=True, stdout=subprocess.DEVNULL)
    raw.unlink()
    print("screenshot:", out, f"{out_width}x{out_height}", "from", f"{pixels}x{height * scale}")


for page in PAGES:
    capture(*page)
