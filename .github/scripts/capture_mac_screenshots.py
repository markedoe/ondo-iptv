"""Captures the Mac App Store screenshots of a Debug build of Ondo.

Usage: capture_mac_screenshots.py <Ondo.app> <output dir> <locale, e.g. en-US>

Each page is opened at launch (through `open`, like a double click) with `-uitest-route` on the demo
catalogue; the app gives its window a 16:10 frame and prints it (`WINDOW_RECT x,y,w,h SCALE s`). The window is captured with `screencapture` and
resized to a size the App Store accepts (2880×1800, 2560×1600, 1440×900 or 1280×800)."""
import pathlib
import re
import subprocess
import sys
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


def diagnose(log):
    """Why no window: is the app running, did it crash, what did it log."""
    print("--- app output ---")
    print(log.read_text(errors="replace")[-4000:] if log.exists() else "(no output file)")
    print("--- processes ---")
    subprocess.run(["pgrep", "-lf", "Ondo"])
    print("--- windows ---")
    subprocess.run(["osascript", "-e", 'tell application "System Events" to get name of every window of '
                    'every process whose name is "Ondo"'])
    print("--- crash reports ---")
    reports = sorted(pathlib.Path.home().glob("Library/Logs/DiagnosticReports/Ondo*"))
    for report in reports[-2:]:
        print(report.name)
        print(report.read_text(errors="replace")[:6000])
    print("--- system log ---")
    system_log = subprocess.run(["log", "show", "--last", "2m", "--style", "compact", "--predicate",
                                 'process == "Ondo" OR eventMessage CONTAINS "Ondo"'],
                                capture_output=True, text=True, errors="replace").stdout
    print("\n".join(system_log.splitlines()[-150:]))


def capture(name, route):
    arguments = ["-uitest-reset", "-uitest-fixture", "-uitest-showcase",
                 "-uitest-route", route, "-uitest-window", "1",
                 "-AppleLanguages", f"({language})", "-AppleLocale", locale.replace("-", "_")]
    log = target / f"{name}.log"
    log.unlink(missing_ok=True)
    # Launched through LaunchServices like a double click (activation, window), output kept in a file.
    subprocess.run(["open", "-n", str(app), "--stdout", str(log), "--stderr", str(log),
                    "--args", *arguments], check=True)
    rect = None
    start = time.time()
    early_shot = False
    while rect is None and time.time() - start < 60:
        time.sleep(0.5)
        text = log.read_text(errors="replace") if log.exists() else ""
        match = re.search(r"WINDOW_RECT (\d+),(\d+),(\d+),(\d+) SCALE (\d+)", text)
        if match:
            rect = [int(value) for value in match.groups()]
        elif not early_shot and time.time() - start > 15:
            # What the screen looks like while waiting (kept only if the capture fails).
            subprocess.run(["screencapture", "-x", str(target / f"{name}-waiting.png")])
            early_shot = True
    if rect is None:
        subprocess.run(["screencapture", "-x", str(target / f"{name}-failed.png")])
        diagnose(log)
        subprocess.run(["pkill", "-x", "Ondo"])
        sys.exit(f"{name}: the app did not report its window")
    (target / f"{name}-waiting.png").unlink(missing_ok=True)
    time.sleep(12)  # catalogue, artwork and guide
    x, y, width, height, scale = rect
    raw = target / f"{name}-raw.png"
    subprocess.run(["screencapture", "-x", "-R", f"{x},{y},{width},{height}", str(raw)], check=True)
    subprocess.run(["pkill", "-x", "Ondo"])
    time.sleep(3)
    log.unlink(missing_ok=True)
    pixels = width * scale
    out_width, out_height = next(((w, h) for w, h in ACCEPTED if w <= pixels), ACCEPTED[-1])
    out = target / f"{name}-mac.png"
    subprocess.run(["sips", "-z", str(out_height), str(out_width), str(raw), "--out", str(out)],
                   check=True, stdout=subprocess.DEVNULL)
    raw.unlink()
    print("screenshot:", out, f"{out_width}x{out_height}", "from", f"{pixels}x{height * scale}")


for page in PAGES:
    capture(*page)
