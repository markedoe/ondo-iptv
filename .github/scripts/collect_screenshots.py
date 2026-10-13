"""Renames xcresult attachments after their test-given names and converts them to small JPEGs.

Text attachments (UI hierarchy and element descriptions recorded by XCTest when an assertion
fails) are copied to `<target>/debug/` so failures can be diagnosed from the published branch."""
import json
import pathlib
import re
import subprocess
import sys

source, target = map(pathlib.Path, sys.argv[1:3])
target.mkdir(parents=True, exist_ok=True)
names = {}
manifest = source / "manifest.json"
if manifest.exists():
    for test in json.loads(manifest.read_text()):
        for attachment in test.get("attachments", []):
            exported = attachment.get("exportedFileName")
            human = attachment.get("suggestedHumanReadableName") or exported
            if exported:
                names[exported] = re.sub(r"_\d+_[0-9A-F-]{36}", "", pathlib.Path(human).stem)
debug = target / "debug"
for item in sorted(source.glob("*")):
    if item.suffix.lower() == ".txt":
        debug.mkdir(exist_ok=True)
        name = re.sub(r"[^A-Za-z0-9._ -]", "_", names.get(item.name, item.stem))[:120]
        (debug / f"{name}-{item.stem[:8]}.txt").write_bytes(item.read_bytes())
        print("debug:", name)
for image in sorted(source.glob("*")):
    if image.suffix.lower() not in (".png", ".jpg", ".jpeg", ".heic"):
        continue
    stem = names.get(image.name, image.stem)
    out = target / f"{stem}.jpg"
    subprocess.run(["sips", "-s", "format", "jpeg", "-s", "formatOptions", "70", "-Z", "1000", str(image), "--out", str(out)],
                   check=False, stdout=subprocess.DEVNULL)
    print("screenshot:", out.name)
