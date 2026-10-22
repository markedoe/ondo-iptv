"""The App Store Connect token of asset_library.py: openssl's DER signature turned into the JWS form Apple checks,
the API key secret read as PEM or base64, and asset names that follow the file's content.

Run: python3 -m unittest discover -s .github/scripts/tests
"""
import base64
import importlib.util
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "asset_library.py"
spec = importlib.util.spec_from_file_location("asset_library", SCRIPT)
asset_library = importlib.util.module_from_spec(spec)
spec.loader.exec_module(asset_library)


def der(raw: bytes) -> bytes:
    """r ‖ s back to DER, as openssl reads it."""
    def integer(value: bytes) -> bytes:
        value = value.lstrip(b"\x00") or b"\x00"
        if value[0] & 0x80:
            value = b"\x00" + value
        return bytes([0x02, len(value)]) + value
    body = integer(raw[:32]) + integer(raw[32:])
    return bytes([0x30, len(body)]) + body


class AssetLibraryTest(unittest.TestCase):
    def setUp(self):
        self.folder = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.folder)

    def openssl(self, *arguments, data=None) -> bytes:
        return subprocess.run(["openssl", *arguments], input=data, capture_output=True, check=True).stdout

    def test_a_signature_converted_for_the_token_still_verifies(self):
        key = self.folder / "key.p8"
        key.write_bytes(self.openssl("genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-256"))
        public = self.folder / "public.pem"
        public.write_bytes(self.openssl("pkey", "-in", str(key), "-pubout"))
        message = b"header.claims"
        for _ in range(8):
            # Each signature differs; some r or s start with a high bit (a leading zero in DER) or are shorter.
            raw = asset_library.der_to_raw(self.openssl("dgst", "-sha256", "-sign", str(key), data=message))
            self.assertEqual(64, len(raw))
            signature = self.folder / "signature.der"
            signature.write_bytes(der(raw))
            verified = self.openssl("dgst", "-sha256", "-verify", str(public), "-signature", str(signature), data=message)
            self.assertIn(b"Verified OK", verified)

    def test_anything_else_than_a_signature_is_refused(self):
        for value in (b"", b"\x02\x01\x01", b"\x30\x06\x03\x01\x01\x02\x01\x01"):
            with self.assertRaises(ValueError):
                asset_library.der_to_raw(value)

    def test_the_key_secret_is_read_as_the_file_or_its_base64(self):
        pem = "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----"
        self.assertEqual(pem + "\n", asset_library.key_pem(pem))
        self.assertEqual(pem, asset_library.key_pem(base64.b64encode(pem.encode()).decode()))

    def test_an_asset_is_named_by_its_slot_language_and_content(self):
        name = asset_library.reference_name("header", "fr-FR", b"one")
        self.assertEqual(name, asset_library.reference_name("header", "fr-FR", b"one"))
        self.assertNotEqual(name, asset_library.reference_name("header", "fr-FR", b"two"))
        self.assertNotEqual(name, asset_library.reference_name("search", "fr-FR", b"one"))
        self.assertTrue(name.startswith("Ondo header fr-FR "))

    def test_each_listing_language_has_both_assets_at_the_app_store_sizes(self):
        for locale in asset_library.LOCALES:
            header = (asset_library.CREATIVE / locale / asset_library.FILES["header"]).read_bytes()
            self.assertEqual(b"\x89PNG", header[:4], f"{locale}: the 21:9 header is taken as PNG only")
            self.assertEqual((3840, 1646), (int.from_bytes(header[16:20], "big"), int.from_bytes(header[20:24], "big")))
            # Colour type 2: RGB, no alpha channel (refused for these assets).
            self.assertEqual(2, header[25], f"{locale}: the header has no alpha channel")
            search = (asset_library.CREATIVE / locale / asset_library.FILES["search"]).read_bytes()
            self.assertEqual(b"\xff\xd8", search[:2], f"{locale}: the search results asset is a JPEG")
            self.assertEqual((3840, 2560), jpeg_size(search), f"{locale}: search results asset size")


def jpeg_size(data: bytes) -> tuple[int, int]:
    """Width and height from the JPEG's start-of-frame segment."""
    index = 2
    while index < len(data):
        marker, length = data[index + 1], int.from_bytes(data[index + 2:index + 4], "big")
        if marker in (0xC0, 0xC1, 0xC2):
            height, width = int.from_bytes(data[index + 5:index + 7], "big"), int.from_bytes(data[index + 7:index + 9], "big")
            return width, height
        index += 2 + length
    raise ValueError("no frame")


if __name__ == "__main__":
    unittest.main()
