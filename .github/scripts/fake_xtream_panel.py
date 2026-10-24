#!/usr/bin/env python3
"""A made-up Xtream Codes panel on 127.0.0.1, for running the Android app against (tv-release-check.yml).

Answers player_api.php (account, categories, channels, movies, series, their details), xmltv.php (a guide for the
channels) and images (one generated poster), in the shapes providers send, at the size asked: a large provider
has thousands of channels and tens of thousands of movies. Every request is written to the log given, so a run
shows what the app asked for. Login: viewer / secret. Names and addresses are made up (example.com).

With --media, the streams play: every channel answers with the folder's live.m3u8 (and its seg*.ts) or clip.ts, every
movie and episode with clip.mkv (byte ranges served, for seeking); movie 1 with heavy.mkv when the folder has one (a
high-bitrate film, as a 4K one weighs). Without it, a stream's address answers 404.
"""
import argparse
import gzip
import json
import os
import struct
import sys
import tempfile
import time
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

USERNAME = "viewer"
PASSWORD = "secret"
CATEGORIES = 200


def name(prefix, i):
    # One title in five in Arabic script, as in multi-language catalogues.
    return f"AR: فيلم رقم {i}" if i % 5 == 0 else f"{prefix} {i} ({1980 + i % 45})"


def png(width, height, rgb):
    """A plain PNG of one colour (no imaging library on the runner)."""
    row = b"\x00" + bytes(rgb) * width
    raw = zlib.compress(row * height, 9)

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)) + chunk(b"IDAT", raw) + chunk(b"IEND", b"")


def write_array(path, count, entry):
    with open(path, "w", encoding="utf-8") as out:
        out.write("[")
        for i in range(1, count + 1):
            if i > 1:
                out.write(",")
            out.write(json.dumps(entry(i), ensure_ascii=False))
        out.write("]")


def generate(folder, base, live, movies, series, guide_channels, guide_days):
    img = f"{base}/img"
    files = {}

    def path(key):
        files[key] = os.path.join(folder, key.replace("/", "_") + ".json")
        return files[key]

    account = {
        "user_info": {"username": USERNAME, "password": PASSWORD, "auth": 1, "status": "Active", "exp_date": "1900000000",
                      "is_trial": "0", "active_cons": "0", "max_connections": "1", "allowed_output_formats": ["m3u8", "ts"]},
        "server_info": {"url": "localhost", "port": "8080", "timezone": "UTC"},
    }
    with open(path(""), "w") as out:
        json.dump(account, out)
    for action, label in (("get_live_categories", "Category"), ("get_vod_categories", "Films"), ("get_series_categories", "Series")):
        write_array(path(action), CATEGORIES, lambda i, label=label: {"category_id": str(i), "category_name": f"FR | {label} {i}", "parent_id": 0})
    write_array(path("get_live_streams"), live, lambda i: {
        "num": i, "name": f"{name('FR: Channel', i)} HD", "stream_type": "live", "stream_id": i,
        "stream_icon": f"{img}/logo{i}.png", "epg_channel_id": f"channel{i}.example", "added": "1700000000", "is_adult": "0",
        "category_id": str(i % CATEGORIES + 1), "category_ids": [i % CATEGORIES + 1], "custom_sid": "",
        "tv_archive": i % 7 // 6, "direct_source": "", "tv_archive_duration": i % 7 // 6 * 3})
    now = int(time.time())
    write_array(path("get_vod_streams"), movies, lambda i: {
        "num": i, "name": name("Movie", i), "title": name("Movie", i), "year": str(1980 + i % 45), "stream_type": "movie",
        "stream_id": i, "stream_icon": f"{img}/poster{i}.jpg", "rating": f"{i % 10}.{i % 7}", "rating_5based": i % 5 + 0.5,
        # A few added this week: the "new" shelves have something.
        "added": str(now - i * 600), "is_adult": "0", "category_id": str(i % CATEGORIES + 1),
        "category_ids": [i % CATEGORIES + 1], "container_extension": "mkv", "custom_sid": "", "direct_source": ""})
    write_array(path("get_series"), series, lambda i: {
        "num": i, "name": name("Series", i), "title": name("Series", i), "year": str(1980 + i % 45), "stream_type": "series",
        "series_id": i, "cover": f"{img}/cover{i}.jpg",
        "plot": f"A made-up story number {i} about a family, a town and a long summer that changes everything for them.",
        "cast": "Actor One, Actor Two, Actor Three", "director": f"Director {i}", "genre": "Drama / Comedy",
        "releaseDate": f"{1980 + i % 45}-05-01", "last_modified": str(now - i * 600), "rating": "7", "rating_5based": 3.5,
        "backdrop_path": [f"{img}/backdrop{i}.jpg"], "youtube_trailer": "", "episode_run_time": "45",
        "category_id": str(i % CATEGORIES + 1), "category_ids": [i % CATEGORIES + 1]})
    guide = os.path.join(folder, "guide.xml")
    step = 3600
    start = now - now % step - 86400
    with open(guide, "w", encoding="utf-8") as out:
        out.write('<?xml version="1.0" encoding="UTF-8"?>\n<tv generator-info-name="test">\n')
        for c in range(1, guide_channels + 1):
            out.write(f'<channel id="channel{c}.example"><display-name>Channel {c}</display-name></channel>\n')
        for c in range(1, guide_channels + 1):
            for p in range(guide_days * 24):
                begin = start + p * step
                fmt = lambda t: time.strftime("%Y%m%d%H%M%S +0000", time.gmtime(t))
                out.write(f'<programme start="{fmt(begin)}" stop="{fmt(begin + step)}" channel="channel{c}.example">'
                          f'<title lang="fr">Programme {p} of channel {c}</title>'
                          f'<desc lang="fr">A made-up description of programme {p}, long enough to look like a real one.</desc>'
                          f'<category lang="fr">Magazine</category></programme>\n')
        out.write("</tv>\n")
    files["xmltv"] = guide
    return files


def movie_info(vod_id, base):
    return {"info": {"name": name("Movie", vod_id), "plot": "A made-up plot.", "cast": "Actor One, Actor Two", "director": "Director",
                     "genre": "Drama", "releasedate": "2001-05-01", "duration_secs": 5400, "rating": "7",
                     "movie_image": f"{base}/img/poster{vod_id}.jpg", "backdrop_path": [f"{base}/img/backdrop{vod_id}.jpg"]},
            "movie_data": {"stream_id": vod_id, "name": name("Movie", vod_id), "container_extension": "mkv"}}


def series_info(series_id, base):
    episodes = {str(s): [{"id": f"{series_id}{s:02d}{e:02d}", "episode_num": e, "title": f"Episode {e}", "container_extension": "mkv",
                          "info": {"duration_secs": 2700, "plot": "A made-up episode."}, "season": s} for e in range(1, 11)] for s in range(1, 4)}
    return {"seasons": [{"season_number": s, "name": f"Season {s}"} for s in range(1, 4)],
            "info": {"name": name("Series", series_id), "plot": "A made-up story.", "cover": f"{base}/img/cover{series_id}.jpg"},
            "episodes": episodes}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--live", type=int, default=5000)
    parser.add_argument("--movies", type=int, default=20000)
    parser.add_argument("--series", type=int, default=5000)
    parser.add_argument("--guide-channels", type=int, default=2000)
    parser.add_argument("--guide-days", type=int, default=3)
    parser.add_argument("--log", default="panel-requests.log")
    parser.add_argument("--media", default="", help="folder with clip.mkv, clip.ts, live.m3u8 and its seg*.ts (streams that play)")
    args = parser.parse_args()
    base = f"http://localhost:{args.port}"
    folder = tempfile.mkdtemp(prefix="panel")
    files = generate(folder, base, args.live, args.movies, args.series, args.guide_channels, args.guide_days)
    poster = png(300, 450, (40, 70, 120))
    log = open(args.log, "a", buffering=1)
    sizes = {key: os.path.getsize(f) for key, f in files.items()}
    log.write(f"panel ready: {json.dumps(sizes)}\n")

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *_):
            pass

        def send(self, status, body, kind="application/json", gzipped=False):
            if gzipped:
                body = gzip.compress(body, 6)
            self.send_response(status)
            self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(len(body)))
            if gzipped:
                self.send_header("Content-Encoding", "gzip")
            self.end_headers()
            self.wfile.write(body)

        def send_file(self, file, kind, ranges=False):
            size = os.path.getsize(file)
            start, end = 0, size - 1
            wanted = self.headers.get("Range", "") if ranges else ""
            if wanted.startswith("bytes="):
                first, _, last = wanted[6:].split(",")[0].partition("-")
                if first.strip():
                    start = int(first)
                    end = min(int(last), size - 1) if last.strip() else size - 1
                elif last.strip():
                    start = max(0, size - int(last))
                if start >= size:
                    self.send_response(416)
                    self.send_header("Content-Range", f"bytes */{size}")
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
            self.send_response(206 if wanted.startswith("bytes=") else 200)
            self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(end - start + 1))
            if ranges:
                self.send_header("Accept-Ranges", "bytes")
            if wanted.startswith("bytes="):
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            self.end_headers()
            with open(file, "rb") as source:
                source.seek(start)
                left = end - start + 1
                while left > 0 and (chunk := source.read(min(1 << 16, left))):
                    self.wfile.write(chunk)
                    left -= len(chunk)

        def send_stream(self, path):
            """A channel, a movie or an episode: /live|movie|series/<user>/<password>/<id>.<extension>."""
            parts = path.strip("/").split("/")
            if not args.media or len(parts) != 4 or parts[0] not in ("live", "movie", "series"):
                return False
            if parts[1] != USERNAME or parts[2] != PASSWORD:
                self.send(403, b"forbidden", "text/plain")
                return True
            leaf = os.path.basename(parts[3])
            if leaf.endswith(".m3u8"):
                self.send_file(os.path.join(args.media, "live.m3u8"), "application/vnd.apple.mpegurl")
            elif leaf.startswith("seg") and leaf.endswith(".ts") and os.path.isfile(os.path.join(args.media, leaf)):
                self.send_file(os.path.join(args.media, leaf), "video/mp2t")
            elif leaf.endswith(".ts"):
                self.send_file(os.path.join(args.media, "clip.ts"), "video/mp2t")
            elif parts[0] == "movie" and leaf.split(".")[0] == "1" and os.path.isfile(os.path.join(args.media, "heavy.mkv")):
                self.send_file(os.path.join(args.media, "heavy.mkv"), "video/x-matroska", ranges=True)
            else:
                self.send_file(os.path.join(args.media, "clip.mkv"), "video/x-matroska", ranges=True)
            return True

        def do_GET(self):
            url = urlparse(self.path)
            query = {k: v[-1] for k, v in parse_qs(url.query).items()}
            log.write(f"{time.strftime('%H:%M:%S')} GET {url.path} {query.get('action', '')}\n")
            gzipped = "gzip" in self.headers.get("Accept-Encoding", "")
            try:
                if url.path.startswith("/img/"):
                    return self.send(200, poster, "image/png")
                if self.send_stream(url.path):
                    return
                if url.path in ("/player_api.php", "/xmltv.php"):
                    if query.get("username") != USERNAME or query.get("password") != PASSWORD:
                        return self.send(200, b'{"user_info":{"auth":0}}')
                    if url.path == "/xmltv.php":
                        return self.send_file(files["xmltv"], "application/xml")
                    action = query.get("action", "")
                    if action in files:
                        return self.send_file(files[action], "application/json")
                    if action == "get_vod_info":
                        return self.send(200, json.dumps(movie_info(int(query.get("vod_id", "1")), base)).encode(), gzipped=gzipped)
                    if action == "get_series_info":
                        return self.send(200, json.dumps(series_info(int(query.get("series_id", "1")), base)).encode(), gzipped=gzipped)
                    if action in ("get_short_epg", "get_simple_data_table"):
                        return self.send(200, b'{"epg_listings":[]}')
                self.send(404, b"not found", "text/plain")
            except (BrokenPipeError, ConnectionResetError):
                pass

    ThreadingHTTPServer.daemon_threads = True
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"panel on {base}, files in {folder}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    sys.exit(main())
