"""listing_screenshots.py: every Google Play listing language gets each kind of screenshot, or nothing is copied.

Run: python3 -m unittest discover -s .github/scripts/tests
"""
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "listing_screenshots.py"
KINDS = ("phoneScreenshots", "sevenInchScreenshots", "tenInchScreenshots", "tvScreenshots")


class ListingScreenshotsTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root)
        self.play = self.root / "play"
        self.metadata = self.root / "metadata-android"

    def listing(self, *locales):
        for locale in locales:
            (self.metadata / locale).mkdir(parents=True)
            (self.metadata / locale / "title.txt").write_text("Ondo\n", encoding="utf-8")

    def shots(self, locale, count=8, kinds=KINDS):
        for kind in kinds:
            folder = self.play / locale / kind
            folder.mkdir(parents=True)
            for number in range(1, count + 1):
                (folder / f"{number:02d}-shot.png").write_bytes(f"{locale} {kind} {number}".encode())

    def run_script(self, *extra):
        return subprocess.run([sys.executable, "-I", str(SCRIPT), *extra, str(self.play), str(self.metadata)],
                              capture_output=True, text=True)

    def test_every_listing_language_gets_its_own_screenshots(self):
        self.listing("en-US", "ar", "hi-IN")
        for locale in ("en-US", "ar", "hi-IN"):
            self.shots(locale)
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        for locale in ("en-US", "ar", "hi-IN"):
            for kind in KINDS:
                files = sorted((self.metadata / locale / "images" / kind).iterdir())
                self.assertEqual(len(files), 8)
                self.assertEqual(files[0].read_bytes(), f"{locale} {kind} 1".encode())

    def test_latin_american_spanish_shows_the_spanish_screenshots(self):
        self.listing("es-ES", "es-419")
        self.shots("es-ES")
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        shot = self.metadata / "es-419" / "images" / "tvScreenshots" / "01-shot.png"
        self.assertEqual(shot.read_bytes(), b"es-ES tvScreenshots 1")

    def test_a_language_without_screenshots_stops_the_copy(self):
        self.listing("en-US", "zh-CN")
        self.shots("en-US")
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("zh-CN phoneScreenshots: 0 screenshots", result.stdout)
        self.assertFalse((self.metadata / "en-US" / "images").exists(), "nothing copied")

    def test_a_missing_kind_or_too_many_stops_the_copy(self):
        self.listing("fr-FR", "pt-BR")
        self.shots("fr-FR", kinds=KINDS[:3])
        self.shots("pt-BR", count=9)
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("fr-FR tvScreenshots: 0 screenshots", result.stdout)
        self.assertIn("pt-BR phoneScreenshots: 9 screenshots", result.stdout)

    def test_only_the_languages_asked_for(self):
        self.listing("en-US", "es-ES", "es-419")
        self.shots("es-ES")
        result = self.run_script("--only", "es-ES es-419")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertFalse((self.metadata / "en-US" / "images").exists())
        self.assertTrue((self.metadata / "es-419" / "images" / "phoneScreenshots" / "08-shot.png").exists())
        self.assertNotEqual(self.run_script("--only", "de-DE").returncode, 0)

    def test_an_earlier_copy_leaves_nothing_behind(self):
        self.listing("en-US")
        self.shots("en-US")
        old = self.metadata / "en-US" / "images" / "phoneScreenshots" / "09-old.png"
        old.parent.mkdir(parents=True)
        old.write_bytes(b"old")
        self.assertEqual(self.run_script().returncode, 0)
        self.assertFalse(old.exists())


if __name__ == "__main__":
    unittest.main()
