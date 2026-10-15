"""A provider that misbehaves on purpose, to see how the player copes.

/live/<file>?rate=1.0&stalls=10:3,40:5&burst=0&drop=60&prefill=0
    An endless MPEG-TS "live" stream (the file loops), sent in real time at `rate` x the file's bitrate,
    no length, no Range. `stalls` pauses the sending at T seconds for D seconds; `burst` sends the data in
    bursts every N ms (a jittery provider); `drop` closes the connection at T seconds; `prefill` sends the
    last N seconds at once on connection, as providers that keep a few seconds of each channel do.
/vod/<file>?rate=0&stall_at=30:4&drop_at=45
    A file with Range support. `rate` 0 sends as fast as possible, otherwise at rate x the bitrate.
    `stall_at` pauses at the byte matching T seconds of media for D seconds; `drop_at` closes the
    connection at the byte matching T seconds (once per request: a reconnect with Range resumes).
"""
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

MEDIA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "media")
DURATIONS = {"test720.ts": 120.02, "test720.mkv": 120.02, "heavy.ts": 60.02}
LOG = []
LOCK = threading.Lock()


def log(event):
    with LOCK:
        LOG.append((time.time(), event))
    print(f"[server {time.strftime('%H:%M:%S')}] {event}", file=sys.stderr, flush=True)


def pairs(value):
    out = []
    for part in filter(None, (value or "").split(",")):
        at, _, length = part.partition(":")
        out.append((float(at), float(length or 0)))
    return out


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def do_GET(self):
        url = urlparse(self.path)
        query = {k: v[-1] for k, v in parse_qs(url.query).items()}
        kind, _, name = url.path.strip("/").partition("/")
        path = os.path.join(MEDIA, name)
        if not os.path.isfile(path):
            self.send_error(404)
            return
        size = os.path.getsize(path)
        bps = size / DURATIONS.get(name, 120.0)
        try:
            if kind == "live":
                self.live(path, size, bps, query)
            else:
                self.vod(path, size, bps, query)
        except (BrokenPipeError, ConnectionResetError):
            log(f"{kind}: client closed the connection")

    def live(self, path, size, bps, query):
        rate = float(query.get("rate", 1.0))
        stalls = pairs(query.get("stalls"))
        burst = float(query.get("burst", 0)) / 1000
        drop = float(query.get("drop", 0))
        prefill = float(query.get("prefill", 0))
        self.send_response(200)
        self.send_header("Content-Type", "video/mp2t")
        self.send_header("Connection", "close")
        self.end_headers()
        log(f"live: start rate={rate} stalls={stalls} burst={burst * 1000:.0f}ms drop={drop} prefill={prefill}")
        start = time.monotonic()
        paused = 0.0
        sent = 0
        chunk = 188 * 64
        with open(path, "rb") as f:
            while True:
                elapsed = time.monotonic() - start
                if drop and elapsed >= drop:
                    log(f"live: dropping the connection at {elapsed:.1f}s")
                    self.close_connection = True
                    return
                for at, length in stalls:
                    if at <= elapsed - paused < at + 0.05 and length > 0:
                        log(f"live: stall {length}s at {elapsed:.1f}s")
                        time.sleep(length)
                        paused += length
                        stalls.remove((at, length))
                        break
                # Real time: never ahead of the clock. After a stall, what was held back goes at once, as
                # providers do when their own source comes back.
                target = (time.monotonic() - start + prefill) * bps * rate
                if sent >= target:
                    time.sleep(burst if burst else 0.01)
                    continue
                data = f.read(chunk)
                if not data:
                    f.seek(0)
                    continue
                self.wfile.write(data)
                sent += len(data)

    def vod(self, path, size, bps, query):
        rate = float(query.get("rate", 0))
        stall_at = pairs(query.get("stall_at"))
        drop_at = float(query.get("drop_at", 0))
        first, last = 0, size - 1
        header = self.headers.get("Range")
        if header and header.startswith("bytes="):
            a, _, b = header[6:].partition("-")
            first = int(a or 0)
            last = int(b) if b else size - 1
            self.send_response(206)
            self.send_header("Content-Range", f"bytes {first}-{last}/{size}")
        else:
            self.send_response(200)
        self.send_header("Content-Type", "video/mp2t" if path.endswith(".ts") else "video/x-matroska")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(last - first + 1))
        self.end_headers()
        log(f"vod: bytes {first}-{last} rate={rate} stall_at={stall_at} drop_at={drop_at}")
        start = time.monotonic()
        position = first
        chunk = 64 * 1024
        with open(path, "rb") as f:
            f.seek(first)
            while position <= last:
                if drop_at and first < drop_at * bps <= position:
                    log(f"vod: dropping the connection at byte {position}")
                    self.close_connection = True
                    return
                for at, length in list(stall_at):
                    if position >= at * bps > first:
                        log(f"vod: stall {length}s at byte {position}")
                        time.sleep(length)
                        stall_at.remove((at, length))
                if rate:
                    target = (time.monotonic() - start) * bps * rate
                    if position - first >= target:
                        time.sleep(0.01)
                        continue
                data = f.read(min(chunk, last - position + 1))
                if not data:
                    break
                self.wfile.write(data)
                position += len(data)


def serve(port):
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    server.daemon_threads = True
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server


if __name__ == "__main__":
    serve(int(sys.argv[1]) if len(sys.argv) > 1 else 8765)
    log("serving")
    while True:
        time.sleep(3600)
