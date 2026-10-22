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



class FakeAppStoreConnect:
    """Answers the few requests submit and publish make, and records the ones that would change something."""

    def __init__(self, version_state: str, asset_state: str = "PREPARE_FOR_SUBMISSION", submissions=()):
        self.version_state, self.submissions, self.changes = version_state, list(submissions), []
        self.images = [{"id": f"{locale}-{kind}", "attributes": {
            "category": "CREATIVE_ASSETS", "state": asset_state,
            "referenceName": asset_library.reference_name(kind, locale, (asset_library.CREATIVE / locale / asset_library.FILES[kind]).read_bytes()),
        }} for locale in asset_library.LOCALES for kind in asset_library.SLOTS]

    def call(self, method, path, body=None, query=None):
        if method != "GET":
            self.changes.append((method, path))
            return {"data": {"id": "submission", "attributes": {"state": "WAITING_FOR_REVIEW"}}}
        if path == "/v1/apps":
            return {"data": [{"id": "app"}]}
        if path.endswith("/assetLibrary"):
            return {"data": {"id": "library"}}
        raise AssertionError(path)

    def all(self, path, query=None):
        if path.endswith("/appStoreVersions"):
            return [{"id": "version", "attributes": {"versionString": "1.1.0", "appStoreState": self.version_state}}]
        if path == "/v1/reviewSubmissions":
            return self.submissions
        if path.endswith("/images"):
            return self.images
        if path.endswith("/items"):
            return []
        raise AssertionError(path)


class SubmitTest(unittest.TestCase):
    def test_nothing_is_submitted_while_the_version_is_in_review(self):
        for state in ("WAITING_FOR_REVIEW", "IN_REVIEW", "PREPARE_FOR_SUBMISSION"):
            fake = FakeAppStoreConnect(state)
            with self.assertRaises(SystemExit):
                asset_library.submit(fake)
            self.assertEqual([], fake.changes, state)

    def test_nothing_is_submitted_while_a_review_submission_is_under_way(self):
        fake = FakeAppStoreConnect("READY_FOR_SALE", submissions=[{"id": "s", "attributes": {"state": "IN_REVIEW"}}])
        with self.assertRaises(SystemExit):
            asset_library.submit(fake)
        self.assertEqual([], fake.changes)

    def test_once_approved_the_four_assets_go_in_one_submission(self):
        fake = FakeAppStoreConnect("READY_FOR_SALE")
        asset_library.submit(fake)
        self.assertEqual([("POST", "/v1/reviewSubmissions")] + [("POST", "/v1/reviewSubmissionItems")] * 4
                         + [("PATCH", "/v1/reviewSubmissions/submission")], fake.changes)

    def test_assets_already_submitted_are_not_sent_again(self):
        fake = FakeAppStoreConnect("READY_FOR_SALE", asset_state="WAITING_FOR_REVIEW")
        asset_library.submit(fake)
        self.assertEqual([], fake.changes)

    def test_only_approved_assets_are_published(self):
        fake = FakeAppStoreConnect("READY_FOR_SALE", asset_state="IN_REVIEW")
        with self.assertRaises(SystemExit):
            asset_library.publish(fake)
        self.assertEqual([], fake.changes)


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
