"""Every Gradle build of the Android app asks Google's Maven Central mirror before Maven Central itself.

Maven Central answers GitHub's runners 429 (Too Many Requests) at times, and Gradle fails on that answer instead of
falling back to the next repository: the mirror has to come first, in every repositories block that lists Central."""

import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]
MIRROR = "https://maven-central.storage-download.googleapis.com/maven2/"
BUILDS = [ROOT / "android" / "settings.gradle.kts", ROOT / "android" / "core" / "settings.gradle.kts"]


class MavenMirrorTest(unittest.TestCase):
    def test_every_block_listing_central_asks_the_mirror_first(self):
        for path in BUILDS:
            text = path.read_text(encoding="utf-8")
            blocks = re.findall(r"repositories\s*\{(.*?)\n\s*\}", text, re.S)
            self.assertTrue(blocks, path)
            for block in blocks:
                if "mavenCentral()" not in block:
                    continue
                self.assertIn(MIRROR, block, f"{path}: the mirror is missing from a block that lists Maven Central")
                self.assertLess(block.index(MIRROR), block.index("mavenCentral()"), f"{path}: the mirror comes after Central")


if __name__ == "__main__":
    unittest.main()
