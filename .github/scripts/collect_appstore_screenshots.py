"""Copies the full-resolution App Store screenshots of an xcresult export to `<target>/<locale>/`.

deliver detects the device from the image size, so files only need a sortable name. iPad captures
are taken in landscape but XCTest stores them in the device's portrait pixel orientation: they are
turned upright with `sips`."""
import json
import pathlib
import re
import shutil
import struct
import subprocess
import sys


def size(path):
    with open(path, "rb") as image:
        header = image.read(24)
    return struct.unpack(">II", header[16:24])

source, target, locale, device = sys.argv[1:5]
source, target = pathlib.Path(source), pathlib.Path(target) / locale
target.mkdir(parents=True, exist_ok=True)
manifest = json.loads((source / "manifest.json").read_text())
for test in manifest:
    for attachment in test.get("attachments", []):
        exported = attachment.get("exportedFileName", "")
        human = attachment.get("suggestedHumanReadableName") or exported
        if not exported.lower().endswith((".png", ".jpg", ".jpeg")):
            continue
        name = re.sub(r"_\d+_[0-9A-F-]{36}", "", pathlib.Path(human).stem)
        out = target / f"{name}-{device}{pathlib.Path(exported).suffix.lower()}"
        shutil.copy(source / exported, out)
        if device.startswith("ipad") and out.suffix == ".png":
            width, height = size(out)
            if width < height:
                subprocess.run(["sips", "--rotate", "270", str(out)], check=True, stdout=subprocess.DEVNULL)
        print("screenshot:", out, size(out) if out.suffix == ".png" else "")
