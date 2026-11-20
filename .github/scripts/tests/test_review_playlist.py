"""The review playlist check must reject any host but our site (the films' former hosts included), a non-https
address, a guide off our site, a channel missing from the guide and a file or segment missing from site/review, and
accept the repository's playlist.

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
            f'{SITE}/films/elephants-dream.mov\n')
# The hosts the open films were linked from until they answered 403 to the review checks.
FORMER_HOSTS = (
    "https://download.blender.org/ED/elephantsdream-720-h264-st-aac.mov",
    "https://download.blender.org/durian/trailer/sintel_trailer-720p.mp4",
    "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
    "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8",
)


class ReviewPlaylistTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root)
        review = self.root / "site/review"
        for folder in ("live", "films"):
            (review / folder).mkdir(parents=True)
        (review / "live/channel.m3u8").write_text("#EXTM3U\n#EXTINF:6.000,\nsegment00.ts\n", encoding="utf-8")
        for name in ("live/segment00.ts", "live/logo.png", "films/elephants-dream.mov"):
            (review / name).write_bytes(b"\0")
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

    def test_accepts_our_channel_and_an_open_film_on_our_site(self):
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

    def test_rejects_the_former_hosts_of_the_open_films(self):
        for address in FORMER_HOSTS:
            with self.subTest(address=address):
                self.assertFails(PLAYLIST.replace(f"{SITE}/films/elephants-dream.mov", address),
                                 f"{address} is not on our site")

    def test_the_repository_names_only_our_site(self):
        playlist = (REPOSITORY / "site/review/ondo-review.m3u").read_text(encoding="utf-8")
        for address in check_review_playlist.addresses(playlist):
            self.assertRegex(address, check_review_playlist.OWN_SITE)

    def test_rejects_plain_http_even_on_our_site(self):
        self.assertFails(PLAYLIST.replace(f"{SITE}/films/", "http://example.github.io/ondo-iptv/review/films/"),
                         "http://example.github.io/ondo-iptv/review/films/elephants-dream.mov is not on our site")

    def test_rejects_a_look_alike_host(self):
        for look_alike in ("https://example.github.io.example.net/ondo-iptv/review/films/",
                           "https://example.github.io/other-repository/review/films/",
                           "https://example.github.io/ondo-iptv/other/films/"):
            with self.subTest(look_alike=look_alike):
                self.assertFails(PLAYLIST.replace(f"{SITE}/films/", look_alike), "is not on our site")

    def test_rejects_a_film_missing_from_the_site(self):
        (self.root / "site/review/films/elephants-dream.mov").unlink()
        self.assertFails(PLAYLIST, "names site/review/films/elephants-dream.mov, which is missing")

    def test_rejects_a_missing_segment(self):
        (self.root / "site/review/live/segment00.ts").unlink()
        self.assertFails(PLAYLIST, "site/review/live/channel.m3u8: segment segment00.ts is missing")

    def test_rejects_a_segment_elsewhere(self):
        (self.root / "site/review/live/channel.m3u8").write_text(
            "#EXTM3U\n#EXTINF:6.000,\nhttps://cdn.example.net/segment00.ts\n", encoding="utf-8")
        self.assertFails(PLAYLIST, "https://cdn.example.net/segment00.ts must be a file next to it")

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
