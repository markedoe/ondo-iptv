"""collect_appstore_screenshots.py: the review screenshot of the In-App Purchases never goes with the listing's.

Run: python3 -m unittest discover -s .github/scripts/tests
"""
import json
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
import zlib
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "collect_appstore_screenshots.py"


def png() -> bytes:
    """A 2 × 1 RGB image."""
    def chunk(kind: bytes, body: bytes) -> bytes:
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 2, 1, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(b"\x00\xff\x00\x00\x00\x00\xff")) + chunk(b"IEND", b""))


class CollectScreenshotsTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root)
        self.raw = self.root / "raw"
        self.raw.mkdir()
        attachments = []
        for index, name in enumerate(("01-home", "iap-review-tips")):
            exported = f"{name}_0_{'0' * 8}-0000-0000-0000-{index:012d}.png"
            (self.raw / exported).write_bytes(png())
            attachments.append({"exportedFileName": exported, "suggestedHumanReadableName": exported})
        (self.raw / "manifest.json").write_text(json.dumps([{"attachments": attachments}]))

    def collect(self, *review: str) -> None:
        subprocess.run([sys.executable, str(SCRIPT), str(self.raw), str(self.root / "out"), "en-US", "iphone-max", *review],
                       check=True, capture_output=True, cwd=SCRIPT.parent)

    def files(self, folder: str) -> list:
        return sorted(path.relative_to(self.root / folder).as_posix() for path in (self.root / folder).rglob("*.png"))

    def test_the_review_screenshot_goes_to_its_own_folder(self):
        self.collect(str(self.root / "iap-review"))
        self.assertEqual(self.files("out"), ["en-US/01-home-iphone-max.png"])
        self.assertEqual(self.files("iap-review"), ["en-US/iap-review-tips-iphone-max.png"])

    def test_without_a_review_folder_the_review_screenshot_is_left_out(self):
        self.collect()
        self.assertEqual(self.files("out"), ["en-US/01-home-iphone-max.png"])
        self.assertFalse((self.root / "iap-review").exists())


if __name__ == "__main__":
    unittest.main()
