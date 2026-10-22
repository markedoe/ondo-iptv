"""Screenshots lose their metadata (png_metadata.py), above all the orientation tag that would turn an upright
iPad capture sideways again, and keep their pixels.

Run: python3 -m unittest discover -s .github/scripts/tests
"""
import importlib.util
import shutil
import struct
import tempfile
import unittest
import zlib
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "png_metadata.py"
spec = importlib.util.spec_from_file_location("png_metadata", SCRIPT)
png_metadata = importlib.util.module_from_spec(spec)
spec.loader.exec_module(png_metadata)


def chunk(kind: bytes, body: bytes) -> bytes:
    return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body))


def png(*extra: bytes) -> bytes:
    """A 2 × 1 RGB image, with the given chunks before its pixels."""
    header = chunk(b"IHDR", struct.pack(">IIBBBBB", 2, 1, 8, 2, 0, 0, 0))
    pixels = chunk(b"IDAT", zlib.compress(b"\x00\xff\x00\x00\x00\x00\xff"))
    return png_metadata.SIGNATURE + header + chunk(b"sRGB", b"\x00") + b"".join(extra) + pixels + chunk(b"IEND", b"")


class PngMetadataTest(unittest.TestCase):
    def setUp(self):
        self.folder = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.folder)

    def test_the_orientation_and_the_text_go_and_the_image_stays(self):
        # EXIF with orientation 8 (rotated), as the simulator writes it, and an XMP packet.
        exif = b"MM\x00*\x00\x00\x00\x08\x00\x01\x01\x12\x00\x03\x00\x00\x00\x01\x00\x08\x00\x00\x00\x00\x00\x00"
        path = self.folder / "shot.png"
        path.write_bytes(png(chunk(b"eXIf", exif), chunk(b"iTXt", b"XML:com.adobe.xmp\x00\x00\x00\x00\x00<x/>")))
        self.assertEqual(["eXIf", "iTXt"], png_metadata.strip(path))
        self.assertEqual(png(), path.read_bytes())

    def test_a_file_without_metadata_is_left_as_it_is(self):
        path = self.folder / "clean.png"
        path.write_bytes(png())
        self.assertEqual([], png_metadata.strip(path))
        self.assertEqual(png(), path.read_bytes())

    def test_a_damaged_file_is_refused_and_left_as_it_is(self):
        path = self.folder / "damaged.png"
        data = bytearray(png(chunk(b"tEXt", b"Software\x00Simulator")))
        data[-20] ^= 0xFF
        path.write_bytes(bytes(data))
        with self.assertRaises(ValueError):
            png_metadata.strip(path)
        self.assertEqual(bytes(data), path.read_bytes())


if __name__ == "__main__":
    unittest.main()
