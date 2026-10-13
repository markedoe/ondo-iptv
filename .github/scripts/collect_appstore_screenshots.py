"""Copies the full-resolution App Store screenshots of an xcresult export to `<target>/<locale>/`.

deliver detects the device from the image size, so files only need a sortable name. iPad captures
are taken in landscape but XCTest stores them in the device's portrait pixel orientation: they are
turned upright with `sips`. Mac window captures are scaled (and padded if needed) to the largest size
the App Store accepts (2880×1800, 2560×1600, 1440×900 or 1280×800)."""
import json
import pathlib
import re
import shutil
import struct
import subprocess
import sys


MAC_SIZES = [(2880, 1800), (2560, 1600), (1440, 900), (1280, 800)]


def size(path):
    with open(path, "rb") as image:
        header = image.read(24)
    return struct.unpack(">II", header[16:24])


def fit_mac(path):
    """Scales the window capture into an accepted 16:10 size, padding with the app background."""
    width, height = size(path)
    out_width, out_height = next(((w, h) for w, h in MAC_SIZES if w <= width), MAC_SIZES[-1])
    scale = min(out_width / width, out_height / height)
    subprocess.run(["sips", "-z", str(round(height * scale)), str(round(width * scale)), str(path)],
                   check=True, stdout=subprocess.DEVNULL)
    subprocess.run(["sips", "--padToHeightWidth", str(out_height), str(out_width), "--padColor", "0B0B0F", str(path)],
                   check=True, stdout=subprocess.DEVNULL)


source, target, locale, device = sys.argv[1:5]
source, target = pathlib.Path(source), pathlib.Path(target) / locale
target.mkdir(parents=True, exist_ok=True)
manifest = json.loads((source / "manifest.json").read_text())
failures = 0
for test in manifest:
    for attachment in test.get("attachments", []):
        exported = attachment.get("exportedFileName", "")
        human = attachment.get("suggestedHumanReadableName") or exported
        if human.startswith("App UI hierarchy"):
            # What XCTest saw when a test failed (windows, buttons…): printed for the CI log.
            print("---", human)
            print("\n".join((source / exported).read_text(errors="replace").splitlines()[:150]))
            continue
        if not exported.lower().endswith((".png", ".jpg", ".jpeg")):
            continue
        name = re.sub(r"_\d+_[0-9A-F-]{36}", "", pathlib.Path(human).stem)
        if name.startswith("Screenshot of main screen"):
            # Taken by XCTest when a test fails: kept aside, never uploaded to the store.
            failures += 1
            (target / "failures").mkdir(exist_ok=True)
            shutil.copy(source / exported, target / "failures" / f"{failures}-{device}{pathlib.Path(exported).suffix.lower()}")
            continue
        out = target / f"{name}-{device}{pathlib.Path(exported).suffix.lower()}"
        shutil.copy(source / exported, out)
        if device == "mac" and out.suffix == ".png":
            fit_mac(out)
        if device.startswith("ipad") and out.suffix == ".png":
            width, height = size(out)
            if width < height:
                subprocess.run(["sips", "--rotate", "270", str(out)], check=True, stdout=subprocess.DEVNULL)
        print("screenshot:", out, size(out) if out.suffix == ".png" else "")
