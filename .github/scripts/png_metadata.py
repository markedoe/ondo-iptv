"""Removes the metadata of a PNG file in place, keeping its pixels and colours.

The simulator's captures carry EXIF and XMP: an orientation that no longer matches once collect_appstore_screenshots.py
has turned an iPad capture upright (browsers and image services would turn it again), and details of the machine
that took them. Only the chunks that draw the image stay: header, palette, colour space, pixel density and data.
Standard library only.
"""
import struct
import sys
import zlib
from pathlib import Path

SIGNATURE = b"\x89PNG\r\n\x1a\n"
KEPT = {b"IHDR", b"PLTE", b"tRNS", b"cHRM", b"gAMA", b"iCCP", b"sBIT", b"sRGB", b"cICP", b"pHYs", b"IDAT", b"IEND"}


def chunks(data: bytes):
    if not data.startswith(SIGNATURE):
        raise ValueError("not a PNG file")
    index = len(SIGNATURE)
    while index < len(data):
        (length,) = struct.unpack(">I", data[index:index + 4])
        kind = data[index + 4:index + 8]
        body = data[index + 8:index + 8 + length]
        (crc,) = struct.unpack(">I", data[index + 8 + length:index + 12 + length])
        if zlib.crc32(kind + body) != crc:
            raise ValueError(f"damaged {kind.decode(errors='replace')} chunk")
        yield kind, data[index:index + 12 + length]
        index += 12 + length


def strip(path: Path) -> list:
    """Rewrites the file without its metadata; returns the names of the chunks removed."""
    data = path.read_bytes()
    kept, removed = [SIGNATURE], []
    for kind, raw in chunks(data):
        if kind in KEPT:
            kept.append(raw)
        else:
            removed.append(kind.decode(errors="replace"))
    if removed:
        path.write_bytes(b"".join(kept))
    return removed


if __name__ == "__main__":
    for name in sys.argv[1:]:
        print(name, "removed:", ", ".join(strip(Path(name))) or "nothing")
