"""The review playlist check must reject a third-party host, a non-https address, a guide off our site and a channel
missing from the guide, and accept the repository's playlist.

Run: python3 -m unittest discover -s .github/scripts/tests
"""
import datetime
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]
REPOSITORY = SCRIPTS.parents[1]
sys.path.insert(0, str(SCRIPTS))

import check_review_playlist  # noqa: E402
import review_guide  # noqa: E402

SITE = "https://example.github.io/ondo-iptv/review"
PLAYLIST = (f'#EXTM3U url-tvg="{SITE}/guide.xml"\n'
            f'#EXTINF:-1 tvg-id="ondo.test" tvg-logo="{SITE}/live/logo.png" tvg-type="live",Ondo Test Channel\n'
            f'{SITE}/live/channel.m3u8\n'
            '#EXTINF:-1 tvg-type="movie",Elephants Dream (2006)\n'
            'https://download.blender.org/ED/elephantsdream-720-h264-st-aac.mov\n')


class ReviewPlaylistTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root)
        (self.root / "site/review").mkdir(parents=True)
        self.write_guide(datetime.date(2026, 11, 2))

    def write_guide(self, today: datetime.date):
        (self.root / "site/review/guide.xml").write_text(review_guide.guide(today), encoding="utf-8")

    def run_check(self, playlist: str):
        (self.root / "site/review/ondo-review.m3u").write_text(playlist, encoding="utf-8")
        result = subprocess.run([sys.executable, str(SCRIPTS / "check_review_playlist.py"), str(self.root)],
                                capture_output=True, text=True)
        return result.returncode, result.stdout + result.stderr

    def assertFails(self, playlist: str, words: str):
        code, output = self.run_check(playlist)
        self.assertEqual(code, 1, output)
        self.assertIn(words, output)

    def test_accepts_our_channel_and_an_open_film(self):
        code, output = self.run_check(PLAYLIST)
        self.assertEqual(code, 0, output)

    def test_accepts_the_repository(self):
        result = subprocess.run([sys.executable, str(SCRIPTS / "check_review_playlist.py"), str(REPOSITORY)],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_rejects_a_third_party_stream(self):
        self.assertFails(PLAYLIST + '#EXTINF:-1,Some Channel\nhttps://streams.example.net/live/index.m3u8\n',
                         "streams.example.net/live/index.m3u8 is not on our site")

    def test_rejects_a_public_channel_list(self):
        self.assertFails(PLAYLIST.replace(f"{SITE}/live/channel.m3u8", "https://iptv.example.org/countries/fr.m3u"),
                         "iptv.example.org/countries/fr.m3u is not on our site")

    def test_rejects_a_logo_from_elsewhere(self):
        self.assertFails(PLAYLIST.replace(f"{SITE}/live/logo.png", "https://images.example.com/logo.png"),
                         "images.example.com/logo.png is not on our site")

    def test_rejects_plain_http_even_on_an_allowed_host(self):
        self.assertFails(PLAYLIST.replace("https://download.blender.org", "http://download.blender.org"),
                         "http://download.blender.org/ED/elephantsdream-720-h264-st-aac.mov is not on our site")

    def test_rejects_a_look_alike_host(self):
        self.assertFails(PLAYLIST.replace("download.blender.org", "download.blender.org.example.net"),
                         "is not on our site")

    def test_rejects_a_guide_off_our_site(self):
        self.assertFails(PLAYLIST.replace(f'url-tvg="{SITE}/guide.xml"', 'url-tvg=""'),
                         "the guide (url-tvg) must be on our site")

    def test_rejects_a_channel_missing_from_the_guide(self):
        self.assertFails(PLAYLIST.replace('tvg-id="ondo.test"', 'tvg-id="other.test"'),
                         "has no channel other.test")

    def test_rejects_what_is_not_a_playlist(self):
        self.assertFails("<html>Not found</html>\n", "not an M3U playlist")

    def test_the_guide_covers_two_days_back_and_four_weeks_ahead(self):
        guide = review_guide.guide(datetime.date(2026, 11, 2)).encode()
        utc = datetime.timezone.utc
        self.assertTrue(check_review_playlist.covers(guide, datetime.datetime(2026, 11, 2, 12, 30, tzinfo=utc)))
        self.assertTrue(check_review_playlist.covers(guide, datetime.datetime(2026, 10, 31, 0, 0, tzinfo=utc)))
        self.assertTrue(check_review_playlist.covers(guide, datetime.datetime(2026, 11, 29, 23, 59, tzinfo=utc)))
        self.assertFalse(check_review_playlist.covers(guide, datetime.datetime(2026, 11, 30, 0, 0, tzinfo=utc)))
        self.assertFalse(check_review_playlist.covers(guide, datetime.datetime(2026, 10, 30, 23, 0, tzinfo=utc)))


if __name__ == "__main__":
    unittest.main()
