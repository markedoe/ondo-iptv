#!/usr/bin/env python3
"""The App Store product page header and search results assets (iOS and iPadOS 27) in App Store Connect's Asset Library.

Usage (.github/workflows/app-store-assets.yml, with the API key from GitHub secrets ASC_KEY_ID, ASC_ISSUER_ID and
ASC_KEY_P8, as for the release workflow):
  asset_library.py status   what App Store Connect holds: the iOS versions and their states, the creative assets of
                            the library, the header and search results slots and specifications, and the placements
                            on each iOS version. Changes nothing.
  asset_library.py upload   puts fastlane/creative/<locale>/header.png and search.jpg into the Asset Library, once per file
                            content. No placement and no submission: the assets wait there, unreviewed.
  asset_library.py place    shows them in the Header and Search Results tab of the iOS version being prepared, one
                            pair per listing language. Refused for a version in review or already approved: App Review
                            sees the version as it was submitted.
  asset_library.py submit   submits them for review on their own (a review submission of the Asset Library), once an
                            iOS version is approved. Refused while an iOS version or review submission is in review,
                            so that nothing changes what App Review is looking at. Only with the user's go (CLAUDE.md).
  asset_library.py publish  shows the approved assets on the live iOS version's product page, without a new version.
  asset_library.py iap      where the tips' In-App Purchases stand: each product (type, state, Family Sharing, review
                            note set or not, localizations, price schedule, availability, review screenshot, versions),
                            the app's tip products App Store Connect lacks, and every App Store version of each
                            platform with its state. Reads only (GET); a refused read is reported and the rest goes on.
  asset_library.py iap_create
                            creates the three tips (fastlane/tips.json) as consumable In-App Purchases, or completes
                            them: reference name and review note, a name and description in every listing language,
                            the price in euros with France as the base territory, every territory and the new ones, and
                            the review screenshot fastlane/creative/iap-review.png when the file exists. Idempotent: what
                            is already right is left alone, nothing is deleted, nothing is submitted.
                            iap_create PNG takes the review screenshot from that file instead.
  asset_library.py iap_screenshot PNG
                            the review screenshot of each tip from that file (a tag's release: the
                            iap-review-screenshot artifact of its screenshots run), where none is set yet.
  asset_library.py iap_pending IOS|MAC_OS
                            reads which tips a version submission must take with it (READY_TO_SUBMIT), and none when the
                            App Store edition does not sell them (no StoreKit in its build). Ends on "tips to submit: N".
  asset_library.py iap_submit IOS|MAC_OS VERSION BUILD
                            the submission of a version tag when tips are pending (fastlane/Fastfile, release and
                            submit lanes, after deliver uploaded the listing): selects the build, puts the version and
                            each pending tip in one review submission and submits it. Only with the user's go.

Prints no secret and no contact detail. Standard library and the openssl command only.
"""
import base64
import decimal
import hashlib
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

API = "https://api.appstoreconnect.apple.com"
BUNDLE_ID = "com.markedoe.ondo"
ROOT = Path(__file__).resolve().parents[2]
CREATIVE = ROOT / "fastlane" / "creative"
# The tips' names, descriptions and prices, shared with play_tips.py.
TIPS_FILE = ROOT / "fastlane" / "tips.json"
# One screenshot of Settings → Leave a tip, for App Review, used for the three tips.
REVIEW_SCREENSHOT = CREATIVE / "iap-review.png"
# The prices are set in euros, France being the base territory: the other territories follow Apple's equalization.
BASE_TERRITORY = "FRA"
# App Store Connect's limits on an In-App Purchase's display name and description.
NAME_LIMIT, DESCRIPTION_LIMIT = 30, 45
# In-App Purchase versions whose localizations can still change.
IAP_VERSION_EDITABLE = {"PREPARE_FOR_SUBMISSION", "READY_FOR_REVIEW", "REJECTED", "DEVELOPER_REJECTED"}
# In-App Purchases App Review holds: nothing about them is changed meanwhile.
IAP_IN_REVIEW = {"WAITING_FOR_REVIEW", "IN_REVIEW"}
PLATFORMS = ("IOS", "MAC_OS")
LOCALES = ("en-US", "fr-FR")
# Asset → App Store slot.
SLOTS = {"header": "PRODUCT_PAGE_HEADER_ASSET", "search": "APP_STORE_SEARCH_RESULTS_ASSET"}
# The file of each asset in fastlane/creative/<locale>/: the 21:9 header is taken as PNG only (reference data).
FILES = {"header": "header.png", "search": "search.jpg"}
# The iOS version states whose product page can still change (App Store Connect Help, screenshots).
EDITABLE = {"PREPARE_FOR_SUBMISSION", "DEVELOPER_REJECTED", "REJECTED", "METADATA_REJECTED", "INVALID_BINARY"}
# An iOS version Apple is looking at, and one it has approved.
IN_REVIEW = {"WAITING_FOR_REVIEW", "IN_REVIEW", "READY_FOR_REVIEW", "WAITING_FOR_EXPORT_COMPLIANCE"}
APPROVED = {"READY_FOR_SALE", "PENDING_DEVELOPER_RELEASE", "PENDING_APPLE_RELEASE", "PROCESSING_FOR_APP_STORE", "ACCEPTED"}
# The tips the app sells (App/Model/Tips.swift, kept equal by a test).
TIP_PRODUCTS = tuple(f"{BUNDLE_ID}.tip.{size}" for size in ("small", "medium", "large"))
# Review submissions under way (READY_FOR_REVIEW is a draft not sent yet).
SUBMISSION_IN_REVIEW = {"WAITING_FOR_REVIEW", "IN_REVIEW", "UNRESOLVED_ISSUES", "CANCELING", "COMPLETING"}


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def der_to_raw(signature: bytes) -> bytes:
    """An ECDSA P-256 signature as openssl writes it (DER: SEQUENCE of two INTEGERs) → r ‖ s, 32 bytes each (JWS)."""
    if len(signature) < 8 or signature[0] != 0x30:
        raise ValueError("not a DER ECDSA signature")
    index = 2 if signature[1] < 0x80 else 2 + (signature[1] & 0x7F)
    parts = []
    for _ in range(2):
        if signature[index] != 0x02:
            raise ValueError("not a DER ECDSA signature")
        length = signature[index + 1]
        value = signature[index + 2:index + 2 + length].lstrip(b"\x00")
        if len(value) > 32:
            raise ValueError("not a P-256 signature")
        parts.append(value.rjust(32, b"\x00"))
        index += 2 + length
    return parts[0] + parts[1]


def key_pem(value: str) -> str:
    """The secret holds the .p8 file as is, or its base64 (as the Fastfile accepts)."""
    value = value.strip()
    return value + "\n" if "BEGIN PRIVATE KEY" in value else base64.b64decode(value).decode()


class ApiError(SystemExit):
    """A refused request: ends the run as before, unless a reader reports it and goes on."""

    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status


class Client:
    def __init__(self, key_id: str, issuer: str, pem: str):
        self.key_id, self.issuer = key_id, issuer
        # The key as a file for openssl, readable by this user only, outside the checkout, deleted at exit.
        handle, self.key_path = tempfile.mkstemp(suffix=".p8", dir=os.environ.get("RUNNER_TEMP"))
        with os.fdopen(handle, "w") as file:
            file.write(pem)
        os.chmod(self.key_path, 0o600)

    def close(self):
        if os.path.exists(self.key_path):
            os.remove(self.key_path)

    def token(self) -> str:
        now = int(time.time())
        header = b64url(json.dumps({"alg": "ES256", "kid": self.key_id, "typ": "JWT"}).encode())
        claims = b64url(json.dumps({"iss": self.issuer, "iat": now, "exp": now + 900, "aud": "appstoreconnect-v1"}).encode())
        signing_input = f"{header}.{claims}"
        signature = subprocess.run(["openssl", "dgst", "-sha256", "-sign", self.key_path], input=signing_input.encode(),
                                   capture_output=True, check=True).stdout
        return f"{signing_input}.{b64url(der_to_raw(signature))}"

    def call(self, method: str, path: str, body=None, query=None):
        url = path if path.startswith("http") else API + path
        if query:
            url += "?" + urllib.parse.urlencode(query, safe="[],")
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(url, data=data, method=method,
                                         headers={"Authorization": f"Bearer {self.token()}", "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                text = response.read().decode()
                return json.loads(text) if text else {}
        except urllib.error.HTTPError as error:
            # Apple's error titles and details: no secret in them.
            details = json.loads(error.read().decode() or "{}").get("errors", [])
            message = "; ".join(f"{item.get('title')}: {item.get('detail')}" for item in details)
            raise ApiError(error.code, f"{method} {path}: HTTP {error.code} {message}") from None

    def all(self, path: str, query=None):
        """Every item of a list, following the pages."""
        items, page = [], self.call("GET", path, query=query)
        while True:
            items += page.get("data", [])
            following = page.get("links", {}).get("next")
            if not following:
                return items
            page = self.call("GET", following)


APP_QUERY = {"filter[bundleId]": BUNDLE_ID, "fields[apps]": "bundleId"}


def app_id(client: Client) -> str:
    return found_app(client.call("GET", "/v1/apps", query=APP_QUERY))


def found_app(answer: dict) -> str:
    apps = answer["data"]
    if not apps:
        raise SystemExit(f"No app with the bundle ID {BUNDLE_ID}")
    return apps[0]["id"]


def library_id(client: Client, app: str) -> str:
    return client.call("GET", f"/v1/apps/{app}/assetLibrary")["data"]["id"]


def ios_versions(client: Client, app: str):
    return client.all(f"/v1/apps/{app}/appStoreVersions",
                      query={"filter[platform]": "IOS", "fields[appStoreVersions]": "versionString,appStoreState", "limit": "20"})


def creative_images(client: Client, library: str):
    images = client.all(f"/v1/appAssetLibraries/{library}/images", query={"limit": "200"})
    return [image for image in images if image["attributes"].get("category") == "CREATIVE_ASSETS"]


def review_submissions(client: Client, app: str):
    return client.all("/v1/reviewSubmissions", query={"filter[app]": app, "filter[platform]": "IOS", "limit": "20",
                                                     "fields[reviewSubmissions]": "state,platform"})


def current_assets(client: Client, library: str) -> dict:
    """The library image of each file as it is today, by (locale, kind); missing ones are left out."""
    images = {image["attributes"].get("referenceName"): image for image in creative_images(client, library)}
    found = {}
    for locale in LOCALES:
        for kind in SLOTS:
            name = reference_name(kind, locale, (CREATIVE / locale / FILES[kind]).read_bytes())
            if name in images and images[name]["attributes"].get("state") not in ("FAILED", "ARCHIVED"):
                found[(locale, kind)] = images[name]
    return found


def reference_name(kind: str, locale: str, content: bytes) -> str:
    """Names a file by its slot, language and content: an unchanged file is not uploaded again."""
    return f"Ondo {kind} {locale} {hashlib.sha256(content).hexdigest()[:12]}"


def slot_groups(client: Client) -> dict:
    """The placement group of each slot on App Store versions, read from the reference data (not an enumeration)."""
    reference = client.call("GET", "/v1/appAssetLibraryRefData",
                            query={"filter[placementTypes]": ",".join(SLOTS.values()), "fields[appAssetLibraryRefData]": "features,imageSpecs"})
    data = reference.get("data", [])
    data = data if isinstance(data, list) else [data]
    groups, specs = {}, []
    for datum in data:
        attributes = datum.get("attributes", {})
        for feature in attributes.get("features") or []:
            if feature.get("featureId") != "APP_STORE_VERSIONS":
                continue
            for policy in feature.get("placementPolicies") or []:
                if policy.get("placementType") in SLOTS.values():
                    ids = [group for limit in policy.get("groupLimits") or [] for group in limit.get("groupIds") or []]
                    groups.setdefault(policy["placementType"], []).extend(ids)
        for spec in attributes.get("imageSpecs") or []:
            if set(spec.get("compatiblePlacementTypes") or []) & set(SLOTS.values()):
                specs.append(spec)
    return {"groups": groups, "specs": specs}


def status(client: Client):
    app = app_id(client)
    library = library_id(client, app)
    versions = ios_versions(client, app)
    for version in versions:
        attributes = version["attributes"]
        print(f"iOS {attributes['versionString']}: {attributes['appStoreState']}")
    reference = slot_groups(client)
    for slot, groups in reference["groups"].items():
        print(f"slot {slot}: groups {sorted(set(groups))}")
    for spec in reference["specs"]:
        print(f"spec {spec.get('specId')} {spec.get('aspectRatio')} {spec.get('dimensions')} "
              f"{spec.get('fileExtensions')} for {spec.get('compatiblePlacementTypes')}")
    for submission in review_submissions(client, app):
        print(f"iOS review submission: {submission['attributes'].get('state')}")
    images = creative_images(client, library)
    for image in images:
        attributes = image["attributes"]
        print(f"asset {attributes.get('referenceName')}: {attributes.get('state')} spec {attributes.get('specId')}")
    if not images:
        print("no creative asset in the library")
    if versions:
        listing_ipad_screenshots(client, versions[0])
    for version in versions[:3]:
        for localization in client.all(f"/v1/appStoreVersions/{version['id']}/appStoreVersionLocalizations",
                                       query={"fields[appStoreVersionLocalizations]": "locale"}):
            placements = client.all(f"/v1/appStoreVersionLocalizations/{localization['id']}/placements")
            slots = [p["attributes"].get("placementType") for p in placements if p["attributes"].get("placementType") in SLOTS.values()]
            print(f"iOS {version['attributes']['versionString']} {localization['attributes']['locale']}: {slots or 'no header or search results asset'}")


def listing_ipad_screenshots(client: Client, version: dict):
    """The iPad screenshots of the listing as App Store Connect stored them: landscape captures must come out
    landscape (an orientation tag left in a file once turned them sideways)."""
    for localization in client.all(f"/v1/appStoreVersions/{version['id']}/appStoreVersionLocalizations",
                                   query={"fields[appStoreVersionLocalizations]": "locale"}):
        for screenshot_set in client.all(f"/v1/appStoreVersionLocalizations/{localization['id']}/appScreenshotSets"):
            display = screenshot_set["attributes"].get("screenshotDisplayType", "")
            if "IPAD" not in display:
                continue
            for shot in client.all(f"/v1/appScreenshotSets/{screenshot_set['id']}/appScreenshots"):
                attributes = shot["attributes"]
                asset = attributes.get("imageAsset") or {}
                width, height = asset.get("width"), asset.get("height")
                shape = "landscape" if width and height and width > height else "PORTRAIT"
                preview = (asset.get("templateUrl") or "").replace("{w}", "800").replace("{h}", "600").replace("{f}", "png")
                print(f"listing {localization['attributes']['locale']} {display} {attributes.get('fileName')}: "
                      f"{width}×{height} {shape} {preview}")


class Reader:
    """GET only: the iap report is given this, never the client, so it cannot change anything."""

    def __init__(self, client):
        self._client = client

    def get(self, path: str, query=None) -> dict:
        return self._client.call("GET", path, query=query)

    def all(self, path: str, query=None) -> list:
        return self._client.all(path, query=query)

    @staticmethod
    def attempt(label: str, read):
        """One read; a refusal is printed and the report goes on."""
        try:
            return read()
        except ApiError as error:
            hint = {403: " (the API key's role may lack access to this: App Manager or Admin)",
                    404: " (not found: nothing set yet, or not readable with this key)"}.get(error.status, "")
            print(f"  {label}: unavailable, {error}{hint}")
            return None


def describe_localizations(localizations: list) -> list:
    lines = []
    for item in sorted(localizations, key=lambda item: item["attributes"].get("locale", "")):
        attributes = item["attributes"]
        lines.append(f"  localization {attributes.get('locale')}: name \"{attributes.get('name') or ''}\" "
                     f"{'set' if attributes.get('name') else 'NOT SET'}, description "
                     f"{'set' if attributes.get('description') else 'NOT SET'}, {attributes.get('state')}")
    return lines or ["  no localization"]


def describe_screenshot(screenshot) -> str:
    if not screenshot:
        return "  review screenshot: none"
    attributes = screenshot.get("attributes", {})
    delivery = attributes.get("assetDeliveryState") or {}
    errors = len(delivery.get("errors") or [])
    return f"  review screenshot: present, {delivery.get('state', '?')}" + (f", {errors} error(s)" if errors else "")


def describe_prices(prices: dict, base: str) -> str:
    """The customer price in the base territory, read from the price points included with the manual prices."""
    points = {item["id"]: item.get("attributes", {}) for item in prices.get("included", [])
              if item.get("type") == "inAppPurchasePricePoints"}
    for price in prices.get("data", []):
        point = (price.get("relationships", {}).get("inAppPurchasePricePoint", {}).get("data") or {}).get("id")
        if point in points and not price.get("attributes", {}).get("endDate"):
            return f"{base} {points[point].get('customerPrice')}"
    return f"{base} price not readable"


def describe_versions(versions: list) -> list:
    """Every App Store version by platform, with both states (appVersionState is the newer one)."""
    lines = []
    for version in sorted(versions, key=lambda item: (item["attributes"].get("platform") or "", item["attributes"].get("createdDate") or "")):
        attributes = version["attributes"]
        lines.append(f"{attributes.get('platform')} {attributes.get('versionString')}: appStoreState "
                     f"{attributes.get('appStoreState')}, appVersionState {attributes.get('appVersionState')}")
    return lines or ["no App Store version"]


def missing_products(found: list) -> list:
    return [product for product in TIP_PRODUCTS if product not in found]


def iap_product(reader: Reader, product: dict):
    attributes, ident = product["attributes"], product["id"]
    print(f"{attributes.get('productId')} \"{attributes.get('name')}\": {attributes.get('inAppPurchaseType')}, "
          f"{attributes.get('state')}, Family Sharing {'yes' if attributes.get('familySharable') else 'no'}, "
          f"review note {'yes' if attributes.get('reviewNote') else 'no'}")
    localizations = reader.attempt("localizations", lambda: reader.all(f"/v2/inAppPurchases/{ident}/inAppPurchaseLocalizations",
                                                                      query={"limit": "50"}))
    if localizations is not None:
        for line in describe_localizations(localizations):
            print(line)
    schedule = reader.attempt("price schedule", lambda: reader.get(f"/v2/inAppPurchases/{ident}/iapPriceSchedule"))
    if schedule is not None:
        data = schedule.get("data")
        if not data:
            print("  price schedule: NOT SET")
        else:
            territory = reader.attempt("base territory", lambda: reader.get(
                f"/v1/inAppPurchasePriceSchedules/{data['id']}/baseTerritory"))
            base = ((territory or {}).get("data") or {}).get("id", "?")
            prices = reader.attempt("manual prices", lambda: reader.get(
                f"/v1/inAppPurchasePriceSchedules/{data['id']}/manualPrices",
                query={"include": "inAppPurchasePricePoint,territory", "filter[territory]": base, "limit": "50"}
                if base != "?" else {"include": "inAppPurchasePricePoint,territory", "limit": "50"}))
            print(f"  price schedule: set, base territory {base}" + (f", {describe_prices(prices, base)}" if prices else ""))
    availability = reader.attempt("availability", lambda: reader.get(f"/v2/inAppPurchases/{ident}/inAppPurchaseAvailability"))
    if availability is not None:
        data = availability.get("data")
        if not data:
            print("  availability: NOT SET")
        else:
            territories = reader.attempt("territories", lambda: reader.all(
                f"/v1/inAppPurchaseAvailabilities/{data['id']}/availableTerritories", query={"limit": "200"}))
            new = "yes" if data.get("attributes", {}).get("availableInNewTerritories") else "no"
            count = len(territories) if territories is not None else "?"
            print(f"  availability: {count} territories, new territories {new}")
    screenshot = reader.attempt("review screenshot", lambda: reader.get(f"/v2/inAppPurchases/{ident}/appStoreReviewScreenshot"))
    if screenshot is not None:
        print(describe_screenshot(screenshot.get("data")))
    versions = reader.attempt("versions", lambda: reader.all(f"/v2/inAppPurchases/{ident}/versions"))
    for version in versions or []:
        # Since API 4.4.1 the localizations App Review reads belong to a version, and so may the review image.
        localizations = reader.attempt("version localizations", lambda: reader.all(
            f"/v1/inAppPurchaseVersions/{version['id']}/localizations", query={"limit": "200"}))
        image = reader.attempt("version image", lambda: reader.get(f"/v1/inAppPurchaseVersions/{version['id']}/image"))
        parts = [f"{len(localizations)} localization(s) {', '.join(sorted(item['attributes'].get('locale', '?') for item in localizations))}"
                 if localizations is not None else "localizations unknown",
                 "image unknown" if image is None else f"image {'yes' if image.get('data') else 'no'}"]
        print(f"  version {version['attributes'].get('version')}: {version['attributes'].get('state')}, {', '.join(parts)}")
    if attributes.get("state") == "READY_TO_SUBMIT":
        print("  ready: submitted with the next version (a version tag), when the App Store edition sells the tips")


def iap(client: Client):
    """Reads only: everything below goes through the GET-only reader."""
    reader = Reader(client)
    app = found_app(reader.get("/v1/apps", query=APP_QUERY))
    print("== In-App Purchases")
    products = reader.attempt("In-App Purchases", lambda: reader.all(f"/v1/apps/{app}/inAppPurchasesV2", query={"limit": "200"}))
    for product in products or []:
        iap_product(reader, product)
    if products is not None:
        if not products:
            print("no In-App Purchase on App Store Connect")
        missing = missing_products([product["attributes"].get("productId") for product in products])
        print(f"missing on App Store Connect: {', '.join(missing) if missing else 'none'}")
    print("== App Store versions")
    versions = reader.attempt("App Store versions", lambda: reader.all(f"/v1/apps/{app}/appStoreVersions", query={"limit": "200"}))
    for line in describe_versions(versions or []) if versions is not None else []:
        print(line)


def send_parts(operations: list, content: bytes) -> None:
    """The file's parts to the addresses a reservation gave (no API token: the addresses carry their own)."""
    for operation in operations:
        start, length = operation["offset"], operation["length"]
        headers = {header["name"]: header["value"] for header in operation.get("requestHeaders") or []}
        request = urllib.request.Request(operation["url"], data=content[start:start + length], method=operation["method"], headers=headers)
        with urllib.request.urlopen(request, timeout=300):
            pass


def upload_file(client: Client, library: str, path: Path, name: str) -> dict:
    content = path.read_bytes()
    reserved = client.call("POST", "/v1/appAssetLibraryImages", {"data": {
        "type": "appAssetLibraryImages",
        "attributes": {"category": "CREATIVE_ASSETS", "fileName": path.name, "fileSize": len(content), "referenceName": name},
        "relationships": {"assetLibrary": {"data": {"type": "appAssetLibraries", "id": library}}},
    }})["data"]
    send_parts(reserved["attributes"].get("uploadOperations") or [], content)
    client.call("PATCH", f"/v1/appAssetLibraryImages/{reserved['id']}",
                {"data": {"type": "appAssetLibraryImages", "id": reserved["id"], "attributes": {"uploaded": True}}})
    # Processing matches the file to a specification, or fails with the reason.
    for _ in range(60):
        image = client.call("GET", f"/v1/appAssetLibraryImages/{reserved['id']}")["data"]
        if image["attributes"]["state"] not in ("AWAITING_UPLOAD", "UPLOAD_COMPLETE"):
            return image
        time.sleep(5)
    return image


def upload(client: Client):
    app = app_id(client)
    library = library_id(client, app)
    existing = {image["attributes"].get("referenceName"): image for image in creative_images(client, library)
                if image["attributes"].get("state") not in ("FAILED", "ARCHIVED")}
    failed = False
    for locale in LOCALES:
        for kind in SLOTS:
            path = CREATIVE / locale / FILES[kind]
            name = reference_name(kind, locale, path.read_bytes())
            if name in existing:
                print(f"{name}: already in the library ({existing[name]['attributes']['state']})")
                continue
            image = upload_file(client, library, path, name)
            attributes = image["attributes"]
            print(f"{name}: {attributes['state']} spec {attributes.get('specId')}")
            if attributes["state"] == "FAILED":
                failed = True
                for detail in attributes.get("stateDetails") or []:
                    print(f"  {detail}")
    if failed:
        raise SystemExit(1)


def put_on_version(client: Client, version: dict, assets: dict):
    """One placement per slot and language on the version's listings; an earlier one in the slot is replaced."""
    groups = slot_groups(client)["groups"]
    localizations = {item["attributes"]["locale"]: item for item in client.all(
        f"/v1/appStoreVersions/{version['id']}/appStoreVersionLocalizations", query={"fields[appStoreVersionLocalizations]": "locale"})}
    for locale in LOCALES:
        localization = localizations.get(locale)
        if not localization:
            print(f"iOS {version['attributes']['versionString']} has no {locale} listing: skipped")
            continue
        current = client.all(f"/v1/appStoreVersionLocalizations/{localization['id']}/placements", query={"include": "image"})
        for kind, slot in SLOTS.items():
            image = assets[(locale, kind)]
            for group in sorted(set(groups.get(slot, []))) or [None]:
                same = [p for p in current if p["attributes"].get("placementType") == slot and p["attributes"].get("placementGroup") == group]
                if any(p.get("relationships", {}).get("image", {}).get("data", {}).get("id") == image["id"] for p in same):
                    print(f"{locale} {slot} {group}: already placed")
                    continue
                for old in same:
                    client.call("DELETE", f"/v1/appAssetLibraryPlacements/{old['id']}")
                attributes = {"placementType": slot}
                if group:
                    attributes["placementGroup"] = group
                placed = client.call("POST", "/v1/appAssetLibraryPlacements", {"data": {
                    "type": "appAssetLibraryPlacements", "attributes": attributes,
                    "relationships": {"image": {"data": {"type": "appAssetLibraryImages", "id": image["id"]}},
                                      "appStoreVersionLocalization": {"data": {"type": "appStoreVersionLocalizations", "id": localization["id"]}}},
                }})["data"]
                print(f"{locale} {slot} {group}: placed on iOS {version['attributes']['versionString']} ({placed['attributes'].get('state')})")


def all_assets(client: Client, library: str) -> dict:
    assets = current_assets(client, library)
    missing = [f"{locale} {kind}" for locale in LOCALES for kind in SLOTS if (locale, kind) not in assets]
    if missing:
        raise SystemExit(f"Not in the library: {', '.join(missing)}. Run upload first.")
    return assets


def place(client: Client):
    app = app_id(client)
    versions = [version for version in ios_versions(client, app) if version["attributes"]["appStoreState"] in EDITABLE]
    if not versions:
        raise SystemExit("No iOS version being prepared: a version in review or approved cannot change. Nothing placed.")
    put_on_version(client, versions[0], all_assets(client, library_id(client, app)))


def submit(client: Client):
    app = app_id(client)
    versions = ios_versions(client, app)
    reviewing = [v["attributes"]["versionString"] for v in versions if v["attributes"]["appStoreState"] in IN_REVIEW]
    if reviewing:
        raise SystemExit(f"iOS {', '.join(reviewing)} is in review: nothing submitted, to leave that review alone.")
    if not any(v["attributes"]["appStoreState"] in APPROVED for v in versions):
        raise SystemExit("No approved iOS version yet: the assets are reviewed against the latest one. Nothing submitted.")
    submissions = review_submissions(client, app)
    busy = [s["attributes"].get("state") for s in submissions if s["attributes"].get("state") in SUBMISSION_IN_REVIEW]
    if busy:
        raise SystemExit(f"An iOS review submission is under way ({', '.join(busy)}): nothing submitted.")
    assets = all_assets(client, library_id(client, app))
    pending = {key: image for key, image in assets.items() if image["attributes"]["state"] in ("PREPARE_FOR_SUBMISSION", "COMPLETE", "READY_FOR_REVIEW")}
    for (locale, kind), image in assets.items():
        if (locale, kind) not in pending:
            print(f"{locale} {kind}: {image['attributes']['state']}, not submitted again")
    if not pending:
        print("Nothing to submit")
        return
    # A draft submission holding anything else (an app version…) would send that too: never reuse one.
    drafts = [s for s in submissions if s["attributes"].get("state") == "READY_FOR_REVIEW"]
    for draft in drafts:
        items = client.all(f"/v1/reviewSubmissions/{draft['id']}/items")
        if items:
            raise SystemExit("An iOS review submission draft already holds items: finish or remove it in App Store Connect first.")
    submission = drafts[0] if drafts else client.call("POST", "/v1/reviewSubmissions", {"data": {
        "type": "reviewSubmissions", "attributes": {"platform": "IOS"},
        "relationships": {"app": {"data": {"type": "apps", "id": app}}},
    }})["data"]
    for (locale, kind), image in pending.items():
        client.call("POST", "/v1/reviewSubmissionItems", {"data": {
            "type": "reviewSubmissionItems",
            "relationships": {"reviewSubmission": {"data": {"type": "reviewSubmissions", "id": submission["id"]}},
                              "appAssetLibraryImage": {"data": {"type": "appAssetLibraryImages", "id": image["id"]}}},
        }})
        print(f"{locale} {kind}: added to the review submission")
    sent = client.call("PATCH", f"/v1/reviewSubmissions/{submission['id']}",
                       {"data": {"type": "reviewSubmissions", "id": submission["id"], "attributes": {"submitted": True}}})["data"]
    print(f"Review submission: {sent['attributes'].get('state')}")


def publish(client: Client):
    app = app_id(client)
    live = [version for version in ios_versions(client, app) if version["attributes"]["appStoreState"] == "READY_FOR_SALE"]
    if not live:
        raise SystemExit("No iOS version on the App Store yet: nothing published.")
    assets = all_assets(client, library_id(client, app))
    waiting = [f"{locale} {kind} ({image['attributes']['state']})" for (locale, kind), image in assets.items()
               if image["attributes"]["state"] != "APPROVED"]
    if waiting:
        raise SystemExit(f"Not approved yet: {', '.join(waiting)}. Nothing published.")
    put_on_version(client, live[0], assets)


# The tips' In-App Purchases: created and completed (iap_create), then submitted with a version (iap_pending, iap_submit).

def tips_catalog(path: Path = TIPS_FILE) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def review_note(product_id: str) -> str:
    """What App Review reads about each tip (English): the product, that it unlocks nothing, where to find it."""
    return (f"{product_id} is an optional tip for the developer, a consumable In-App Purchase. It unlocks nothing: "
            "every feature of Ondo is there with or without a tip. To find it: Settings → Leave a tip.")


def apple_localizations(catalog: dict, size: str) -> dict:
    """The name and description of a tip in each App Store listing language, checked against Apple's limits."""
    wanted = {}
    for locale, language in catalog["apple_locales"].items():
        texts = catalog["languages"][language]
        name, description = texts["names"][size], texts["description"]
        if len(name) > NAME_LIMIT or len(description) > DESCRIPTION_LIMIT:
            raise SystemExit(f"{locale} {size}: the name takes {NAME_LIMIT} characters and the description "
                             f"{DESCRIPTION_LIMIT}, fastlane/tips.json has {len(name)} and {len(description)}")
        wanted[locale] = {"name": name, "description": description}
    return wanted


def localization_changes(wanted: dict, existing: list) -> list:
    """("create", locale, texts) for a missing language, ("update", id, locale, texts) for one that differs. Never a
    deletion: a language App Store Connect has and the file has not is left as it is."""
    found = {item["attributes"].get("locale"): item for item in existing}
    changes = []
    for locale, texts in wanted.items():
        item = found.get(locale)
        if item is None:
            changes.append(("create", locale, texts))
        elif any((item["attributes"].get(key) or "") != value for key, value in texts.items()):
            changes.append(("update", item["id"], locale, texts))
    return changes


def same_price(customer_price, wanted: str) -> bool:
    try:
        return decimal.Decimal(str(customer_price)) == decimal.Decimal(wanted)
    except decimal.InvalidOperation:
        return False


def exact_price_point(points: list, wanted: str, territory: str = BASE_TERRITORY) -> str:
    """The price point whose customer price is exactly the one chosen; none is an error, never a nearby price."""
    for point in points:
        if same_price(point.get("attributes", {}).get("customerPrice"), wanted):
            return point["id"]
    raise SystemExit(f"No {territory} price point of exactly {wanted}: nothing priced (pick a price App Store Connect offers)")


def schedule_is_right(base: str, prices: dict, wanted: str) -> bool:
    """The base territory is France and its current manual price is the one chosen."""
    if base != BASE_TERRITORY:
        return False
    points = {item["id"]: item.get("attributes", {}) for item in prices.get("included", [])
              if item.get("type") == "inAppPurchasePricePoints"}
    for price in prices.get("data", []):
        point = (price.get("relationships", {}).get("inAppPurchasePricePoint", {}).get("data") or {}).get("id")
        if not price.get("attributes", {}).get("endDate") and point in points and same_price(points[point].get("customerPrice"), wanted):
            return True
    return False


def price_schedule_body(product: str, point: str) -> dict:
    """A schedule with one manual price in the base territory, from now on (startDate null), as POST
    /v1/inAppPurchasePriceSchedules takes it: the price inline, by a local id."""
    local = "${price}"
    return {
        "data": {"type": "inAppPurchasePriceSchedules", "relationships": {
            "inAppPurchase": {"data": {"type": "inAppPurchases", "id": product}},
            "baseTerritory": {"data": {"type": "territories", "id": BASE_TERRITORY}},
            "manualPrices": {"data": [{"type": "inAppPurchasePrices", "id": local}]}}},
        "included": [{"type": "inAppPurchasePrices", "id": local, "attributes": {"startDate": None},
                      "relationships": {"inAppPurchaseV2": {"data": {"type": "inAppPurchases", "id": product}},
                                        "inAppPurchasePricePoint": {"data": {"type": "inAppPurchasePricePoints", "id": point}}}}],
    }


def availability_is_right(availability, available: list, territories: list) -> bool:
    if not availability or not availability.get("attributes", {}).get("availableInNewTerritories"):
        return False
    return set(territories) <= {item["id"] for item in available}


def optional(read):
    """A relationship not set yet answers 404 on some reads: that is "nothing", not an error."""
    try:
        return read()
    except ApiError as error:
        if error.status == 404:
            return None
        raise


def editable_iap_version(client, product: dict, create: bool = True):
    """The version of the tip whose texts can change: the latest editable one; a new one when it has none at all.
    None when every version is with App Review or approved."""
    versions = client.all(f"/v2/inAppPurchases/{product['id']}/versions")
    editable = [item for item in versions if item["attributes"].get("state") in IAP_VERSION_EDITABLE]
    if editable:
        return max(editable, key=lambda item: int(item["attributes"].get("version") or 0))
    if versions or not create:
        return None
    return client.call("POST", "/v1/inAppPurchaseVersions", {"data": {
        "type": "inAppPurchaseVersions",
        "relationships": {"inAppPurchase": {"data": {"type": "inAppPurchases", "id": product["id"]}}}}})["data"]


def ensure_product(client, app: str, products: dict, tip: dict):
    attributes = {"name": tip["reference"], "reviewNote": review_note(tip["apple"])}
    product = products.get(tip["apple"])
    if product is None:
        product = client.call("POST", "/v2/inAppPurchases", {"data": {
            "type": "inAppPurchases",
            "attributes": {"productId": tip["apple"], "inAppPurchaseType": "CONSUMABLE", "familySharable": False, **attributes},
            "relationships": {"app": {"data": {"type": "apps", "id": app}}}}})["data"]
        print(f"{tip['apple']}: created, consumable")
        return product
    different = {key: value for key, value in attributes.items() if product["attributes"].get(key) != value}
    if different and product["attributes"].get("state") not in IAP_IN_REVIEW:
        client.call("PATCH", f"/v2/inAppPurchases/{product['id']}",
                    {"data": {"type": "inAppPurchases", "id": product["id"], "attributes": different}})
        print(f"{tip['apple']}: {', '.join(sorted(different))} set")
    else:
        print(f"{tip['apple']}: present, {product['attributes'].get('state')}")
    return product


def ensure_localizations(client, product: dict, wanted: dict):
    version = editable_iap_version(client, product)
    if version is None:
        print("  localizations: no editable version (with App Review or approved), left as they are")
        return
    existing = client.all(f"/v1/inAppPurchaseVersions/{version['id']}/localizations", query={"limit": "200"})
    changes = localization_changes(wanted, existing)
    for change in changes:
        if change[0] == "create":
            _, locale, texts = change
            client.call("POST", "/v2/inAppPurchaseLocalizations", {"data": {
                "type": "inAppPurchaseLocalizations", "attributes": {"locale": locale, **texts},
                "relationships": {"version": {"data": {"type": "inAppPurchaseVersions", "id": version["id"]}}}}})
            print(f"  localization {locale}: created")
        else:
            _, ident, locale, texts = change
            client.call("PATCH", f"/v2/inAppPurchaseLocalizations/{ident}",
                        {"data": {"type": "inAppPurchaseLocalizations", "id": ident, "attributes": texts}})
            print(f"  localization {locale}: updated")
    if not changes:
        print(f"  localizations: the {len(wanted)} languages already set")


def ensure_price(client, product: dict, wanted: str):
    ident = product["id"]
    schedule = optional(lambda: client.call("GET", f"/v2/inAppPurchases/{ident}/iapPriceSchedule"))
    data = (schedule or {}).get("data")
    if data:
        base = ((optional(lambda: client.call("GET", f"/v1/inAppPurchasePriceSchedules/{data['id']}/baseTerritory")) or {})
                .get("data") or {}).get("id")
        prices = client.call("GET", f"/v1/inAppPurchasePriceSchedules/{data['id']}/manualPrices",
                             query={"include": "inAppPurchasePricePoint", "filter[territory]": BASE_TERRITORY, "limit": "50"})
        if schedule_is_right(base, prices, wanted):
            print(f"  price: {BASE_TERRITORY} {wanted} EUR already")
            return
    points = client.all(f"/v2/inAppPurchases/{ident}/pricePoints",
                        query={"filter[territory]": BASE_TERRITORY, "fields[inAppPurchasePricePoints]": "customerPrice",
                               "limit": "8000"})
    point = exact_price_point(points, wanted)
    client.call("POST", "/v1/inAppPurchasePriceSchedules", price_schedule_body(ident, point))
    print(f"  price: {BASE_TERRITORY} {wanted} EUR set, the other territories by Apple's equalization")


def ensure_availability(client, product: dict, territories: list):
    ident = product["id"]
    availability = (optional(lambda: client.call("GET", f"/v2/inAppPurchases/{ident}/inAppPurchaseAvailability")) or {}).get("data")
    available = client.all(f"/v1/inAppPurchaseAvailabilities/{availability['id']}/availableTerritories",
                           query={"limit": "200"}) if availability else []
    if availability_is_right(availability, available, territories):
        print(f"  availability: all {len(territories)} territories and the new ones already")
        return
    client.call("POST", "/v1/inAppPurchaseAvailabilities", {"data": {
        "type": "inAppPurchaseAvailabilities", "attributes": {"availableInNewTerritories": True},
        "relationships": {"inAppPurchase": {"data": {"type": "inAppPurchases", "id": ident}},
                          "availableTerritories": {"data": [{"type": "territories", "id": code} for code in territories]}}}})
    print(f"  availability: all {len(territories)} territories and the new ones set")


def ensure_review_screenshot(client, product: dict, path: Path = REVIEW_SCREENSHOT, wait=time.sleep):
    if not path.is_file():
        print(f"  review screenshot: {path.name} missing, not uploaded (App Review needs one before the tips can be "
              "submitted: a tag's release uploads the one its screenshots run captures)")
        return
    content = path.read_bytes()
    checksum = hashlib.md5(content).hexdigest()
    current = ((optional(lambda: client.call("GET", f"/v2/inAppPurchases/{product['id']}/appStoreReviewScreenshot")) or {})
               .get("data"))
    if current:
        attributes = current.get("attributes", {})
        if attributes.get("sourceFileChecksum") == checksum:
            print(f"  review screenshot: already there ({(attributes.get('assetDeliveryState') or {}).get('state', '?')})")
        else:
            print("  review screenshot: another one is there, left as it is (delete it in App Store Connect to replace it)")
        return
    reserved = client.call("POST", "/v1/inAppPurchaseAppStoreReviewScreenshots", {"data": {
        "type": "inAppPurchaseAppStoreReviewScreenshots", "attributes": {"fileName": path.name, "fileSize": len(content)},
        "relationships": {"inAppPurchaseV2": {"data": {"type": "inAppPurchases", "id": product["id"]}}}}})["data"]
    send_parts(reserved["attributes"].get("uploadOperations") or [], content)
    client.call("PATCH", f"/v1/inAppPurchaseAppStoreReviewScreenshots/{reserved['id']}", {"data": {
        "type": "inAppPurchaseAppStoreReviewScreenshots", "id": reserved["id"],
        "attributes": {"uploaded": True, "sourceFileChecksum": checksum}}})
    # Processed within a minute or two; until then the tip is not READY_TO_SUBMIT.
    state = "?"
    for _ in range(40):
        shot = client.call("GET", f"/v1/inAppPurchaseAppStoreReviewScreenshots/{reserved['id']}")["data"]
        state = (shot.get("attributes", {}).get("assetDeliveryState") or {}).get("state", "?")
        if state in ("COMPLETE", "FAILED"):
            break
        wait(5)
    print(f"  review screenshot: uploaded, {state}")
    if state == "FAILED":
        raise SystemExit(f"{product['attributes'].get('productId')}: App Store Connect refused the review screenshot {path.name}")


def tip_products(client, app: str) -> dict:
    return {item["attributes"].get("productId"): item
            for item in client.all(f"/v1/apps/{app}/inAppPurchasesV2", query={"limit": "200"})}


def iap_screenshot(client, screenshot: str, wait=time.sleep):
    """The review screenshot of each tip already on App Store Connect, from a file (the release's
    iap-review-screenshot artifact, or a downloaded copy); one already there is left as it is."""
    path = Path(screenshot).resolve()
    if not path.is_file():
        raise SystemExit(f"No file {path.name}: no review screenshot uploaded")
    products = tip_products(client, app_id(client))
    for product_id in TIP_PRODUCTS:
        product = products.get(product_id)
        if not product:
            print(f"{product_id}: not on App Store Connect (run iap_create), no screenshot")
            continue
        print(f"{product_id}: {product['attributes'].get('state')}")
        if product["attributes"].get("state") in IAP_IN_REVIEW:
            print("  with App Review: nothing changed")
            continue
        ensure_review_screenshot(client, product, path, wait)


def iap_create(client, screenshot=REVIEW_SCREENSHOT):
    screenshot = Path(screenshot).resolve()
    catalog = tips_catalog()
    wanted = {size: apple_localizations(catalog, size) for size in catalog["tips"]}  # every limit checked first
    app = app_id(client)
    products = tip_products(client, app)
    territories = [item["id"] for item in client.all("/v1/territories", query={"limit": "200"})]
    for size, tip in catalog["tips"].items():
        product = ensure_product(client, app, products, tip)
        if product["attributes"].get("state") in IAP_IN_REVIEW:
            print("  with App Review: nothing changed")
            continue
        ensure_localizations(client, product, wanted[size])
        ensure_price(client, product, tip["eur"])
        ensure_availability(client, product, territories)
        ensure_review_screenshot(client, product, screenshot)
        state = client.call("GET", f"/v2/inAppPurchases/{product['id']}")["data"]["attributes"].get("state")
        print(f"  state: {state}" + (" (submitted with the next version: a version tag)" if state == "READY_TO_SUBMIT" else ""))


def load_check_app_store():
    spec = importlib.util.spec_from_file_location("check_app_store", Path(__file__).resolve().parent / "check_app_store.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def app_store_edition_sells_tips() -> bool:
    """The same answer as the App Store check (check_app_store.py): StoreKit in the App Store edition's build."""
    return load_check_app_store().app_store_edition_sells_tips()


def pending_tips(client, app: str) -> list:
    """The tips ready to go to App Review with a version: READY_TO_SUBMIT, with an editable version (or none yet).
    Approved ones, and ones App Review already holds, are left out."""
    pending = []
    products = tip_products(client, app)
    for product_id in TIP_PRODUCTS:
        product = products.get(product_id)
        state = product["attributes"].get("state") if product else "missing"
        if state != "READY_TO_SUBMIT":
            print(f"{product_id}: {state}, not submitted" + (" (run iap_create: it is not ready)" if state in
                  ("missing", "MISSING_METADATA", "WAITING_FOR_UPLOAD", "PROCESSING_CONTENT") else ""))
            continue
        versions = client.all(f"/v2/inAppPurchases/{product['id']}/versions")
        if any(item["attributes"].get("state") in ("WAITING_FOR_REVIEW", "IN_REVIEW") for item in versions):
            print(f"{product_id}: its version is with App Review already, not submitted again")
            continue
        print(f"{product_id}: READY_TO_SUBMIT")
        pending.append(product)
    return pending


def iap_pending(client, platform: str, sells=None):
    if platform not in PLATFORMS:
        raise SystemExit(f"Platform: {' or '.join(PLATFORMS)}")
    sells = app_store_edition_sells_tips() if sells is None else sells
    if not sells:
        print("The App Store edition has no tips (no StoreKit in its build): no In-App Purchase goes with this version.")
        print("tips to submit: 0")
        return []
    pending = pending_tips(client, app_id(client))
    print(f"tips to submit: {len(pending)}")
    return pending


def iap_submit(client, platform: str, version_string: str, build_number: str, sells=None, wait=time.sleep):
    """deliver's submission (fastlane deliver/submit_for_review.rb) with the pending tips added to the same review
    submission: Apple reviews a first In-App Purchase together with an app version."""
    if platform not in PLATFORMS or not version_string or not build_number.isdigit():
        raise SystemExit("Usage: asset_library.py iap_submit IOS|MAC_OS VERSION BUILD_NUMBER")
    pending = iap_pending(client, platform, sells)
    app = app_id(client)
    versions = client.all(f"/v1/apps/{app}/appStoreVersions", query={
        "filter[platform]": platform, "filter[versionString]": version_string, "fields[appStoreVersions]": "versionString,appStoreState,appVersionState"})
    editable = [item for item in versions if item["attributes"].get("appStoreState") in EDITABLE]
    if not editable:
        raise SystemExit(f"{platform} {version_string}: no editable App Store version, nothing submitted")
    version = editable[0]
    builds = client.all("/v1/builds", query={"filter[app]": app, "filter[version]": build_number,
                                             "filter[preReleaseVersion.version]": version_string,
                                             "filter[preReleaseVersion.platform]": platform, "limit": "1"})
    if not builds:
        raise SystemExit(f"{platform} build {build_number} of {version_string} not found, nothing submitted")
    build = builds[0]
    if build["attributes"].get("usesNonExemptEncryption") is None:
        raise SystemExit(f"Build {build_number}: export compliance not answered (ITSAppUsesNonExemptEncryption), nothing submitted")
    client.call("PATCH", f"/v1/appStoreVersions/{version['id']}/relationships/build", {"data": {"type": "builds", "id": build["id"]}})
    print(f"{platform} {version_string}: build {build_number} selected")
    submissions = client.all("/v1/reviewSubmissions", query={"filter[app]": app, "filter[platform]": platform,
                                                            "fields[reviewSubmissions]": "state,platform", "limit": "20"})
    if any(item["attributes"].get("state") in SUBMISSION_IN_REVIEW for item in submissions):
        raise SystemExit(f"A {platform} review submission is already in progress: nothing submitted")
    drafts = [item for item in submissions if item["attributes"].get("state") == "READY_FOR_REVIEW"]
    for draft in drafts:
        if client.all(f"/v1/reviewSubmissions/{draft['id']}/items"):
            raise SystemExit(f"A {platform} review submission draft already holds items: remove them in App Store Connect first")
    submission = drafts[0] if drafts else client.call("POST", "/v1/reviewSubmissions", {"data": {
        "type": "reviewSubmissions", "attributes": {"platform": platform},
        "relationships": {"app": {"data": {"type": "apps", "id": app}}}}})["data"]

    def item(relationship: str, kind: str, ident: str):
        client.call("POST", "/v1/reviewSubmissionItems", {"data": {"type": "reviewSubmissionItems", "relationships": {
            "reviewSubmission": {"data": {"type": "reviewSubmissions", "id": submission["id"]}},
            relationship: {"data": {"type": kind, "id": ident}}}}})

    item("appStoreVersion", "appStoreVersions", version["id"])
    print(f"{platform} {version_string}: added to the review submission")
    for product in pending:
        iap_version = editable_iap_version(client, product)
        if iap_version is None:
            raise SystemExit(f"{product['attributes'].get('productId')}: no version to submit")
        item("inAppPurchaseVersion", "inAppPurchaseVersions", iap_version["id"])
        print(f"{product['attributes'].get('productId')}: added to the review submission")
    # As deliver does: the version must be READY_FOR_REVIEW before the submission is sent.
    for _ in range(10):
        state = client.call("GET", f"/v1/appStoreVersions/{version['id']}",
                            query={"fields[appStoreVersions]": "appVersionState"})["data"]["attributes"].get("appVersionState")
        if state == "READY_FOR_REVIEW":
            break
        print(f"Waiting for the version to be READY_FOR_REVIEW ({state})")
        wait(15)
    sent = client.call("PATCH", f"/v1/reviewSubmissions/{submission['id']}",
                       {"data": {"type": "reviewSubmissions", "id": submission["id"], "attributes": {"submitted": True}}})["data"]
    print(f"Review submission: {sent['attributes'].get('state')}, with {len(pending)} tip(s)")


def main() -> None:
    # Each command and how many arguments follow it.
    commands = {"status": (status, 0), "upload": (upload, 0), "place": (place, 0), "submit": (submit, 0),
                "publish": (publish, 0), "iap": (iap, 0), "iap_create": (iap_create, 0, 1), "iap_screenshot": (iap_screenshot, 1),
                "iap_pending": (iap_pending, 1), "iap_submit": (iap_submit, 3)}
    if len(sys.argv) < 2 or sys.argv[1] not in commands or not (
            commands[sys.argv[1]][1] <= len(sys.argv) - 2 <= commands[sys.argv[1]][-1]):
        raise SystemExit(f"Usage: asset_library.py {'|'.join(commands)} (iap_create [PNG], iap_screenshot PNG, "
                         "iap_pending PLATFORM, iap_submit PLATFORM VERSION BUILD)")
    command = commands[sys.argv[1]][0]
    client = Client(os.environ["ASC_KEY_ID"], os.environ["ASC_ISSUER_ID"], key_pem(os.environ["ASC_KEY_P8"]))
    try:
        command(client, *sys.argv[2:])
    finally:
        client.close()


if __name__ == "__main__":
    main()
