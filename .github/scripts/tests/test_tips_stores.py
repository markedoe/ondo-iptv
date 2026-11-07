"""The tips' products on both stores: the texts of fastlane/tips.json (the apps' own names, the stores' limits, every
listing language), their creation on App Store Connect (asset_library.py iap_create) and Google Play (play_tips.py),
idempotent, and their submission with a version tag (asset_library.py iap_pending / iap_submit, fastlane/Fastfile).

Run: python3 -m unittest discover -s .github/scripts/tests
"""
import contextlib
import hashlib
import importlib.util
import io
import json
import re
import tempfile
import unittest
import xml.etree.ElementTree as ElementTree
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]
ROOT = SCRIPTS.parents[1]


def load(name):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


asset_library = load("asset_library")
play_tips = load("play_tips")
CATALOG = json.loads((ROOT / "fastlane" / "tips.json").read_text(encoding="utf-8"))
SIZES = ("small", "medium", "large")
# The apps' languages: Apple's catalog key and Android's values folder of each.
APPLE_LANGUAGE = {"en": "en", "fr": "fr", "es": "es", "pt": "pt", "zh-Hans": "zh-Hans", "ar": "ar", "hi": "hi"}
ANDROID_FOLDER = {"en": "values", "fr": "values-fr", "es": "values-es", "pt": "values-pt", "zh-Hans": "values-zh-rCN",
                  "ar": "values-ar", "hi": "values-hi"}


def quiet(function, *arguments, **keywords):
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        result = function(*arguments, **keywords)
    return result, out.getvalue()


class CatalogTest(unittest.TestCase):
    def test_the_names_are_the_apps_own_in_every_language(self):
        strings = json.loads((ROOT / "App/Resources/Localizable.xcstrings").read_text(encoding="utf-8"))["strings"]
        english = {"small": "Small tip", "medium": "Medium tip", "large": "Large tip"}
        self.assertEqual(set(APPLE_LANGUAGE), set(CATALOG["languages"]))
        for language, texts in CATALOG["languages"].items():
            for size in SIZES:
                key = english[size]
                apple = key if language == "en" else strings[key]["localizations"][APPLE_LANGUAGE[language]]["stringUnit"]["value"]
                android = ElementTree.parse(ROOT / "android/app/src/main/res" / ANDROID_FOLDER[language] / "strings.xml")
                value = next(item.text for item in android.getroot() if item.get("name") == f"tip_{size}")
                self.assertEqual(apple, texts["names"][size], (language, size))
                self.assertEqual(value, texts["names"][size], (language, size))

    def test_every_listing_language_of_both_stores_is_covered(self):
        apple = {path.name for path in (ROOT / "fastlane/metadata").iterdir() if path.is_dir() and path.name != "review_information"}
        play = {path.name for path in (ROOT / "fastlane/metadata-android").iterdir() if path.is_dir()}
        self.assertEqual(apple, set(CATALOG["apple_locales"]))
        self.assertEqual(play, set(CATALOG["play_languages"]))
        for language in list(CATALOG["apple_locales"].values()) + list(CATALOG["play_languages"].values()):
            self.assertIn(language, CATALOG["languages"])

    def test_the_texts_fit_apples_limits_and_say_nothing_misleading(self):
        for size in SIZES:
            wanted = asset_library.apple_localizations(CATALOG, size)
            for locale, texts in wanted.items():
                self.assertLessEqual(len(texts["name"]), 30, locale)
                self.assertLessEqual(len(texts["description"]), 45, locale)
                self.assertTrue(texts["description"].strip(), locale)
        words = re.compile(r"(?i)\b(free|gratuit|gratis|grátis|beta|test)\b|免费|मुफ़्त|مجان")
        for language, texts in CATALOG["languages"].items():
            self.assertIsNone(words.search(texts["description"] + " ".join(texts["names"].values())), language)

    def test_a_text_over_the_limit_stops_before_anything_is_sent(self):
        catalog = json.loads(json.dumps(CATALOG))
        catalog["languages"]["fr"]["description"] = "x" * 46
        with self.assertRaises(SystemExit):
            asset_library.apple_localizations(catalog, "small")

    def test_the_products_and_prices_are_the_apps(self):
        self.assertEqual(asset_library.TIP_PRODUCTS, tuple(CATALOG["tips"][size]["apple"] for size in SIZES))
        self.assertEqual(play_tips.play.TIP_PRODUCTS, tuple(CATALOG["tips"][size]["play"] for size in SIZES))
        self.assertEqual(["1.99", "4.99", "9.99"], [CATALOG["tips"][size]["eur"] for size in SIZES])
        for size in SIZES:
            note = asset_library.review_note(CATALOG["tips"][size]["apple"])
            self.assertIn("unlocks nothing", note)
            self.assertIn("Settings → Leave a tip", note)
            self.assertIn(CATALOG["tips"][size]["apple"], note)


class FakeConnect:
    """App Store Connect in memory: the tips' products, versions, localizations, prices, availability and
    screenshots, and the review submissions. Records every call that would change something."""

    TERRITORIES = ["FRA", "USA", "BRA", "JPN"]

    def __init__(self, products=None, points=None, versions=None, localizations=None, schedules=None,
                 availabilities=None, screenshots=None, submissions=None):
        self.products = products or {}
        self.points = points if points is not None else [
            {"id": f"pp-{price}", "attributes": {"customerPrice": price}} for price in ("0.99", "1.99", "2.99", "4.99", "9.99")]
        self.versions = versions or {}
        self.localizations = localizations or {}
        self.schedules = schedules or {}
        self.availabilities = availabilities or {}
        self.screenshots = screenshots or {}
        self.submissions = submissions or []
        self.app_version_state = "READY_FOR_REVIEW"
        self.changes = []
        self.bodies = []

    def call(self, method, path, body=None, query=None):
        if method != "GET":
            self.changes.append((method, path))
            self.bodies.append(body)
            return self.write(method, path, body)
        if path == "/v1/apps":
            return {"data": [{"id": "app"}]}
        match = re.fullmatch(r"/v2/inAppPurchases/(\w+)/(\w+)", path)
        if match:
            ident, part = match.groups()
            if part == "iapPriceSchedule":
                return {"data": {"id": f"schedule-{ident}"} if ident in self.schedules else None}
            if part == "inAppPurchaseAvailability":
                availability = self.availabilities.get(ident)
                return {"data": {"id": f"availability-{ident}", "attributes": {"availableInNewTerritories": availability[0]}}
                        if availability else None}
            if part == "appStoreReviewScreenshot":
                checksum = self.screenshots.get(ident)
                return {"data": {"id": "shot", "attributes": {"sourceFileChecksum": checksum, "assetDeliveryState": {"state": "COMPLETE"}}}
                        if checksum else None}
        match = re.fullmatch(r"/v2/inAppPurchases/(\w+)", path)
        if match:
            return {"data": {"id": match.group(1), "attributes": {"state": "READY_TO_SUBMIT"}}}
        match = re.fullmatch(r"/v1/inAppPurchasePriceSchedules/schedule-(\w+)/(\w+)", path)
        if match:
            ident, part = match.groups()
            base, price = self.schedules[ident]
            if part == "baseTerritory":
                return {"data": {"type": "territories", "id": base}}
            return {"data": [{"id": "p", "attributes": {"endDate": None},
                              "relationships": {"inAppPurchasePricePoint": {"data": {"id": "point"}}}}],
                    "included": [{"type": "inAppPurchasePricePoints", "id": "point", "attributes": {"customerPrice": price}}]}
        if path == "/v1/inAppPurchaseAppStoreReviewScreenshots/shot":
            return {"data": {"id": "shot", "attributes": {"assetDeliveryState": {"state": "COMPLETE"}}}}
        match = re.fullmatch(r"/v1/appStoreVersions/(\w+)", path)
        if match:
            return {"data": {"id": match.group(1), "attributes": {"appVersionState": self.app_version_state}}}
        raise AssertionError(f"GET {path}")

    def all(self, path, query=None):
        if path == "/v1/apps/app/inAppPurchasesV2":
            return list(self.products.values())
        if path == "/v1/territories":
            return [{"id": code} for code in self.TERRITORIES]
        match = re.fullmatch(r"/v2/inAppPurchases/(\w+)/(\w+)", path)
        if match:
            ident, part = match.groups()
            if part == "versions":
                return self.versions.get(ident, [])
            if part == "pricePoints":
                self.assertTerritory(query)
                return self.points
        match = re.fullmatch(r"/v1/inAppPurchaseVersions/(\w+)/localizations", path)
        if match:
            return self.localizations.get(match.group(1), [])
        match = re.fullmatch(r"/v1/inAppPurchaseAvailabilities/availability-(\w+)/availableTerritories", path)
        if match:
            return [{"id": code} for code in self.availabilities[match.group(1)][1]]
        if path == "/v1/apps/app/appStoreVersions":
            return [{"id": "asv", "attributes": {"versionString": "2.0.0", "appStoreState": "PREPARE_FOR_SUBMISSION"}}]
        if path == "/v1/builds":
            return [{"id": "build", "attributes": {"version": "123", "usesNonExemptEncryption": False}}]
        if path == "/v1/reviewSubmissions":
            return self.submissions
        if path.endswith("/items"):
            return []
        raise AssertionError(f"all {path}")

    @staticmethod
    def assertTerritory(query):
        if (query or {}).get("filter[territory]") != "FRA":
            raise AssertionError("price points read outside the base territory")

    def write(self, method, path, body):
        if method == "POST" and path == "/v2/inAppPurchases":
            attributes = body["data"]["attributes"]
            ident = attributes["productId"].rsplit(".", 1)[1]
            self.products[attributes["productId"]] = {"id": ident, "attributes": {**attributes, "state": "MISSING_METADATA"}}
            self.versions[ident] = [{"id": f"v{ident}", "attributes": {"version": 1, "state": "PREPARE_FOR_SUBMISSION"}}]
            return {"data": self.products[attributes["productId"]]}
        if method == "POST" and path == "/v1/inAppPurchaseVersions":
            ident = body["data"]["relationships"]["inAppPurchase"]["data"]["id"]
            return {"data": {"id": f"new-{ident}", "attributes": {"version": 1, "state": "PREPARE_FOR_SUBMISSION"}}}
        if path == "/v1/inAppPurchaseAppStoreReviewScreenshots":
            return {"data": {"id": "shot", "attributes": {"uploadOperations": []}}}
        if path == "/v1/reviewSubmissions":
            return {"data": {"id": "submission", "attributes": {"state": "READY_FOR_REVIEW"}}}
        return {"data": {"id": "x", "attributes": {"state": "WAITING_FOR_REVIEW"}}}


def right_store(screenshot_checksum=None):
    """App Store Connect with the three tips exactly as fastlane/tips.json wants them."""
    products, versions, localizations, schedules, availabilities, screenshots = {}, {}, {}, {}, {}, {}
    for size in SIZES:
        tip = CATALOG["tips"][size]
        products[tip["apple"]] = {"id": size, "attributes": {
            "productId": tip["apple"], "name": tip["reference"], "reviewNote": asset_library.review_note(tip["apple"]),
            "inAppPurchaseType": "CONSUMABLE", "state": "READY_TO_SUBMIT"}}
        versions[size] = [{"id": f"v{size}", "attributes": {"version": 1, "state": "PREPARE_FOR_SUBMISSION"}}]
        localizations[f"v{size}"] = [{"id": f"{size}-{locale}", "attributes": {"locale": locale, **texts}}
                                     for locale, texts in asset_library.apple_localizations(CATALOG, size).items()]
        schedules[size] = ("FRA", tip["eur"])
        availabilities[size] = (True, FakeConnect.TERRITORIES)
        if screenshot_checksum:
            screenshots[size] = screenshot_checksum
    return FakeConnect(products=products, versions=versions, localizations=localizations, schedules=schedules,
                       availabilities=availabilities, screenshots=screenshots)


class AppleCreateTest(unittest.TestCase):
    def setUp(self):
        folder = Path(tempfile.mkdtemp())
        self.addCleanup(lambda: [path.unlink() for path in folder.iterdir()] and None)
        self.screenshot = folder / "iap-review.png"
        self.missing = folder / "absent.png"

    def test_from_nothing_each_tip_gets_every_part(self):
        fake = FakeConnect()
        _, text = quiet(asset_library.iap_create, fake, self.missing)
        locales = len(CATALOG["apple_locales"])
        expected = []
        for size in SIZES:
            expected += [("POST", "/v2/inAppPurchases")] + [("POST", "/v2/inAppPurchaseLocalizations")] * locales
            expected += [("POST", "/v1/inAppPurchasePriceSchedules"), ("POST", "/v1/inAppPurchaseAvailabilities")]
        self.assertEqual(expected, fake.changes)
        created = fake.bodies[0]["data"]
        self.assertEqual({"productId": "com.markedoe.ondo.tip.small", "name": "Tip small", "inAppPurchaseType": "CONSUMABLE",
                          "familySharable": False, "reviewNote": asset_library.review_note("com.markedoe.ondo.tip.small")},
                         created["attributes"])
        self.assertEqual({"app": {"data": {"type": "apps", "id": "app"}}}, created["relationships"])
        localization = fake.bodies[1]["data"]
        self.assertEqual({"version": {"data": {"type": "inAppPurchaseVersions", "id": "vsmall"}}}, localization["relationships"])
        schedule = fake.bodies[1 + locales]
        self.assertEqual("FRA", schedule["data"]["relationships"]["baseTerritory"]["data"]["id"])
        self.assertEqual("pp-1.99", schedule["included"][0]["relationships"]["inAppPurchasePricePoint"]["data"]["id"])
        self.assertEqual(schedule["data"]["relationships"]["manualPrices"]["data"][0]["id"], schedule["included"][0]["id"])
        availability = fake.bodies[2 + locales]["data"]
        self.assertTrue(availability["attributes"]["availableInNewTerritories"])
        self.assertEqual(FakeConnect.TERRITORIES, [item["id"] for item in availability["relationships"]["availableTerritories"]["data"]])
        large = fake.bodies[-2]
        self.assertEqual("pp-9.99", large["included"][0]["relationships"]["inAppPurchasePricePoint"]["data"]["id"])
        self.assertIn("iap-review.png", text.replace("absent.png", "iap-review.png"))
        self.assertIn("missing, not uploaded", text)

    def test_run_again_on_tips_already_right_it_changes_nothing(self):
        fake = right_store()
        _, text = quiet(asset_library.iap_create, fake, self.missing)
        self.assertEqual([], fake.changes)
        self.assertIn("the 8 languages already set", text)
        self.assertIn("FRA 1.99 EUR already", text)

    def test_only_what_differs_is_changed_and_nothing_is_deleted(self):
        fake = right_store()
        fake.localizations["vsmall"] = [item for item in fake.localizations["vsmall"] if item["attributes"]["locale"] != "hi"]
        fake.localizations["vsmall"][0]["attributes"]["description"] = "Old text"
        fake.localizations["vsmall"].append({"id": "extra", "attributes": {"locale": "de-DE", "name": "Trinkgeld", "description": "x"}})
        fake.schedules["medium"] = ("USA", "4.99")
        fake.availabilities["large"] = (False, FakeConnect.TERRITORIES)
        quiet(asset_library.iap_create, fake, self.missing)
        self.assertEqual([("PATCH", "/v2/inAppPurchaseLocalizations/small-en-US"), ("POST", "/v2/inAppPurchaseLocalizations"),
                          ("POST", "/v1/inAppPurchasePriceSchedules"), ("POST", "/v1/inAppPurchaseAvailabilities")], fake.changes)
        self.assertFalse([change for change in fake.changes if change[0] == "DELETE"])

    def test_no_exact_price_point_stops_without_a_price(self):
        fake = FakeConnect(points=[{"id": "near", "attributes": {"customerPrice": "1.98"}}])
        with self.assertRaises(SystemExit) as stop:
            quiet(asset_library.iap_create, fake, self.missing)
        self.assertIn("exactly 1.99", str(stop.exception))
        self.assertNotIn(("POST", "/v1/inAppPurchasePriceSchedules"), fake.changes)

    def test_a_price_is_compared_as_a_number(self):
        self.assertEqual("b", asset_library.exact_price_point([{"id": "a", "attributes": {"customerPrice": "19.9"}},
                                                               {"id": "b", "attributes": {"customerPrice": "1.990"}}], "1.99"))

    def test_the_review_screenshot_is_uploaded_once(self):
        self.screenshot.write_bytes(b"\x89PNG fake")
        fake = right_store()
        quiet(asset_library.iap_create, fake, self.screenshot)
        self.assertEqual([("POST", "/v1/inAppPurchaseAppStoreReviewScreenshots"),
                          ("PATCH", "/v1/inAppPurchaseAppStoreReviewScreenshots/shot")] * 3, fake.changes)
        self.assertEqual({"uploaded": True, "sourceFileChecksum": hashlib.md5(b"\x89PNG fake").hexdigest()},
                         fake.bodies[1]["data"]["attributes"])
        again = right_store(hashlib.md5(b"\x89PNG fake").hexdigest())
        quiet(asset_library.iap_create, again, self.screenshot)
        self.assertEqual([], again.changes)

    def test_the_releases_screenshot_goes_on_each_tip_without_one(self):
        self.screenshot.write_bytes(b"\x89PNG release")
        fake = right_store()
        fake.screenshots["medium"] = hashlib.md5(b"\x89PNG release").hexdigest()
        _, text = quiet(asset_library.iap_screenshot, fake, str(self.screenshot), wait=lambda _: None)
        self.assertEqual([("POST", "/v1/inAppPurchaseAppStoreReviewScreenshots"),
                          ("PATCH", "/v1/inAppPurchaseAppStoreReviewScreenshots/shot")] * 2, fake.changes)
        self.assertIn("uploaded, COMPLETE", text)
        with self.assertRaises(SystemExit):
            quiet(asset_library.iap_screenshot, fake, str(self.missing))

    def test_the_release_uploads_the_screenshot_before_it_submits(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        download = workflow.index("name: iap-review-screenshot")
        upload = workflow.index("asset_library.py iap_screenshot")
        self.assertLess(download, upload)
        self.assertLess(upload, workflow.index("name: iOS → App Review"))
        # Outside the checkout: never among the listing's screenshots (fastlane/screenshots).
        self.assertIn("path: ${{ runner.temp }}/iap-review", workflow)

    def test_a_tip_with_app_review_is_left_alone(self):
        fake = right_store()
        fake.products["com.markedoe.ondo.tip.small"]["attributes"].update(state="IN_REVIEW", reviewNote="old")
        fake.schedules.pop("small")
        quiet(asset_library.iap_create, fake, self.missing)
        self.assertEqual([], fake.changes)


class AppleSubmitTest(unittest.TestCase):
    def test_an_app_store_edition_without_the_tips_submits_none_and_reads_nothing(self):
        fake = right_store()
        pending, text = quiet(asset_library.iap_pending, fake, "IOS", sells=False)
        self.assertEqual([], pending)
        self.assertTrue(text.rstrip().endswith("tips to submit: 0"))

    def test_today_the_app_store_edition_has_no_tips(self):
        # Tips.swift is under #if TESTFLIGHT_DOWNLOADS || DEBUG: a tag submits no In-App Purchase until that changes.
        gated = re.search(r"^#if TESTFLIGHT_DOWNLOADS", (ROOT / "App/Model/Tips.swift").read_text(encoding="utf-8"), re.M)
        self.assertEqual(bool(gated), not asset_library.app_store_edition_sells_tips())

    def test_only_the_tips_ready_to_submit_are_pending(self):
        fake = right_store()
        fake.products["com.markedoe.ondo.tip.medium"]["attributes"]["state"] = "APPROVED"
        fake.versions["large"] = [{"id": "vlarge", "attributes": {"version": 1, "state": "WAITING_FOR_REVIEW"}}]
        pending, text = quiet(asset_library.iap_pending, fake, "MAC_OS", sells=True, wait=lambda _: None)
        self.assertEqual(["small"], [product["id"] for product in pending])
        self.assertIn("tips to submit: 1", text)
        self.assertEqual([], fake.changes)

    def test_a_tip_not_ready_stops_a_build_that_sells_the_tips(self):
        fake = right_store()
        fake.products["com.markedoe.ondo.tip.large"]["attributes"]["state"] = "MISSING_METADATA"
        waits = []
        with self.assertRaises(SystemExit) as stop:
            quiet(asset_library.iap_pending, fake, "IOS", sells=True, wait=waits.append)
        self.assertIn("com.markedoe.ondo.tip.large", str(stop.exception))
        self.assertEqual(5, len(waits))  # a moment for a screenshot just processed
        self.assertEqual([], fake.changes)
        with self.assertRaises(SystemExit):
            quiet(asset_library.iap_submit, fake, "IOS", "2.0.0", "123", sells=True, wait=lambda _: None)
        self.assertEqual([], fake.changes)

    def test_the_version_and_its_tips_go_in_one_submission(self):
        fake = right_store()
        quiet(asset_library.iap_submit, fake, "IOS", "2.0.0", "123", sells=True, wait=lambda _: None)
        self.assertEqual([("PATCH", "/v1/appStoreVersions/asv/relationships/build"), ("POST", "/v1/reviewSubmissions")]
                         + [("POST", "/v1/reviewSubmissionItems")] * 4 + [("PATCH", "/v1/reviewSubmissions/submission")], fake.changes)
        items = [body["data"]["relationships"] for body in fake.bodies[2:6]]
        self.assertEqual({"type": "appStoreVersions", "id": "asv"}, items[0]["appStoreVersion"]["data"])
        self.assertEqual(["vsmall", "vmedium", "vlarge"], [item["inAppPurchaseVersion"]["data"]["id"] for item in items[1:]])
        self.assertEqual({"type": "reviewSubmissions", "id": "submission"}, items[1]["reviewSubmission"]["data"])
        self.assertEqual({"submitted": True}, fake.bodies[-1]["data"]["attributes"])
        self.assertEqual({"platform": "IOS"}, fake.bodies[1]["data"]["attributes"])

    def test_nothing_is_submitted_while_a_submission_is_in_progress(self):
        fake = right_store()
        fake.submissions = [{"id": "s", "attributes": {"state": "WAITING_FOR_REVIEW"}}]
        with self.assertRaises(SystemExit):
            quiet(asset_library.iap_submit, fake, "IOS", "2.0.0", "123", sells=True, wait=lambda _: None)
        self.assertEqual([("PATCH", "/v1/appStoreVersions/asv/relationships/build")], fake.changes)

    def test_the_tag_lanes_add_the_tips_only_when_they_wait(self):
        fastfile = (ROOT / "fastlane/Fastfile").read_text(encoding="utf-8")
        release = fastfile[fastfile.index("def release_for_review"):fastfile.index("def withdraw_from_review")]
        self.assertLess(release.index("withdraw_from_review(platform) if submit"), release.index("tips = submit && tips_to_submit?(platform)"))
        self.assertIn("submit_for_review: submit && !tips", release)
        self.assertIn("submit_with_tips(platform, build) if tips", release)
        manual = fastfile[fastfile.index("def submit_for_review"):fastfile.index("def release_for_review")]
        self.assertIn("submit_for_review: !tips", manual)
        self.assertIn("submit_with_tips(platform, build) if tips", manual)
        # precheck cannot read In-App Purchases with an API key: it would stop the release.
        self.assertNotIn("precheck_include_in_app_purchases: true", fastfile)
        self.assertNotIn("include_in_app_purchases: true", fastfile)


class FakePlay:
    """Google Play's one-time products in memory; records every call."""

    def __init__(self, products=None, refuse=None):
        self.products, self.refuse, self.calls = dict(products or {}), refuse, []

    def call(self, method, url, params=None, body=None):
        self.calls.append((method, url.split("/applications/com.markedoe.ondo/", 1)[1], params, body))
        if self.refuse and method != "GET":
            raise self.refuse
        path = self.calls[-1][1]
        if method == "GET":
            product_id = path.split("/", 1)[1]
            if product_id not in self.products:
                raise play_tips.play.ApiError(404, "NOT_FOUND")
            return self.products[product_id]
        if path == "pricing:convertRegionPrices":
            return converted()
        if method == "PATCH":
            stored = json.loads(json.dumps(body))
            stored["purchaseOptions"][0]["state"] = "DRAFT"
            self.products[body["productId"]] = stored
            return stored
        return {}

    def writes(self):
        return [call for call in self.calls if call[0] != "GET"]


def converted():
    return {"regionVersion": {"version": "2025/03"},
            "convertedRegionPrices": {
                "FR": {"regionCode": "FR", "price": {"currencyCode": "EUR", "units": "2", "nanos": 390000000}},
                "DE": {"regionCode": "DE", "price": {"currencyCode": "EUR", "units": "2", "nanos": 370000000}},
                "US": {"regionCode": "US", "price": {"currencyCode": "USD", "units": "2", "nanos": 190000000}}},
            "convertedOtherRegionsPrice": {"usdPrice": {"currencyCode": "USD", "units": "2"},
                                           "eurPrice": {"currencyCode": "EUR", "units": "1", "nanos": 990000000}}}


def right_play():
    products = {}
    for size in SIZES:
        body = play_tips.product_body(CATALOG, size, converted())
        body["purchaseOptions"][0]["state"] = "ACTIVE"
        products[CATALOG["tips"][size]["play"]] = body
    return products


class PlayTest(unittest.TestCase):
    def run_create(self, fake):
        report = play_tips.play.Report()
        code = play_tips.create(fake, report, CATALOG)
        return code, "\n".join(report.lines)

    def test_euro_regions_pay_exactly_the_chosen_price_and_the_others_plays_conversion(self):
        configs = {config["regionCode"]: config for config in play_tips.regional_configs(converted(), "1.99")}
        self.assertEqual({"currencyCode": "EUR", "units": "1", "nanos": 990000000}, configs["FR"]["price"])
        self.assertEqual({"currencyCode": "EUR", "units": "1", "nanos": 990000000}, configs["DE"]["price"])
        self.assertEqual({"currencyCode": "USD", "units": "2", "nanos": 190000000}, configs["US"]["price"])
        self.assertEqual({"AVAILABLE"}, {config["availability"] for config in configs.values()})

    def test_from_nothing_each_tip_is_created_priced_and_activated(self):
        fake = FakePlay()
        code, text = self.run_create(fake)
        self.assertEqual(0, code)
        writes = fake.writes()
        self.assertEqual(["POST", "PATCH", "POST"] * 3, [call[0] for call in writes])
        _, path, params, body = writes[1]
        self.assertEqual("onetimeproducts/tip_small", path)
        self.assertEqual({"allowMissing": "true", "regionsVersion.version": "2025/03", "updateMask": "listings,purchaseOptions"}, params)
        self.assertEqual({"currencyCode": "EUR", "units": "1", "nanos": 990000000}, writes[0][3]["price"])
        option = body["purchaseOptions"][0]
        self.assertEqual("buy", option["purchaseOptionId"])
        self.assertEqual({"legacyCompatible": True}, option["buyOption"])
        self.assertEqual("AVAILABLE", option["newRegionsConfig"]["availability"])
        self.assertEqual(set(CATALOG["play_languages"]), {listing["languageCode"] for listing in body["listings"]})
        self.assertEqual("oneTimeProducts/tip_small/purchaseOptions:batchUpdateStates", writes[2][1])
        self.assertEqual({"requests": [{"activatePurchaseOptionRequest": {
            "packageName": "com.markedoe.ondo", "productId": "tip_small", "purchaseOptionId": "buy"}}]}, writes[2][3])
        self.assertEqual("nanos", list(play_tips.to_money("9.99", "EUR"))[-1])
        self.assertEqual(990000000, play_tips.to_money("9.99", "EUR")["nanos"])
        self.assertIn("tip_large", text)

    def test_run_again_on_tips_already_right_it_changes_nothing(self):
        fake = FakePlay(right_play())
        code, text = self.run_create(fake)
        self.assertEqual(0, code)
        self.assertEqual([], fake.writes())
        self.assertIn("already right", text)

    def test_a_tip_only_waiting_for_activation_is_only_activated(self):
        products = right_play()
        products["tip_medium"]["purchaseOptions"][0]["state"] = "DRAFT"
        fake = FakePlay(products)
        self.run_create(fake)
        self.assertEqual([("POST", "oneTimeProducts/tip_medium/purchaseOptions:batchUpdateStates")],
                         [(call[0], call[1]) for call in fake.writes()])

    def test_an_update_keeps_the_listings_and_options_that_are_not_ours(self):
        existing = right_play()["tip_small"]
        existing["listings"].append({"languageCode": "de-DE", "title": "Trinkgeld", "description": "x"})
        existing["purchaseOptions"].append({"purchaseOptionId": "other", "state": "ACTIVE", "buyOption": {}})
        body = play_tips.product_body(CATALOG, "small", converted(), existing)
        self.assertIn("de-DE", [listing["languageCode"] for listing in body["listings"]])
        self.assertEqual([{"purchaseOptionId": "other", "buyOption": {}}], body["purchaseOptions"][1:])

    def test_a_missing_payments_profile_is_named_and_googles_words_stay_out(self):
        refusal = play_tips.play.ApiError(403, "PERMISSION_DENIED", (), "Project 123456789: the developer has no merchant account")
        fake = FakePlay(refuse=refusal)
        code, text = self.run_create(fake)
        self.assertEqual(1, code)
        self.assertIn("payments profile", text)
        self.assertIn("Play Console", text)
        self.assertNotIn("123456789", text)
        self.assertNotIn("the developer has no merchant account", text)
        self.assertEqual(1, len(fake.writes()))  # stopped at the first refusal

    def test_the_tips_workflow_shares_the_release_android_queue(self):
        workflow = (ROOT / ".github/workflows/play-tips.yml").read_text(encoding="utf-8")
        self.assertIn("group: release-android", workflow)
        self.assertIn("options: [status, create]", workflow)
        self.assertIn('python3 -I .github/scripts/play_tips.py "$ACTION"', workflow)
        assets = (ROOT / ".github/workflows/app-store-assets.yml").read_text(encoding="utf-8")
        self.assertIn("iap_create]", assets)


if __name__ == "__main__":
    unittest.main()
