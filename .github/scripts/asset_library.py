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

Prints no secret and no contact detail. Standard library and the openssl command only.
"""
import base64
import hashlib
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
CREATIVE = Path(__file__).resolve().parents[2] / "fastlane" / "creative"
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
            raise SystemExit(f"{method} {path}: HTTP {error.code} {message}") from None

    def all(self, path: str, query=None):
        """Every item of a list, following the pages."""
        items, page = [], self.call("GET", path, query=query)
        while True:
            items += page.get("data", [])
            following = page.get("links", {}).get("next")
            if not following:
                return items
            page = self.call("GET", following)


def app_id(client: Client) -> str:
    apps = client.call("GET", "/v1/apps", query={"filter[bundleId]": BUNDLE_ID, "fields[apps]": "bundleId"})["data"]
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
    for version in versions[:3]:
        for localization in client.all(f"/v1/appStoreVersions/{version['id']}/appStoreVersionLocalizations",
                                       query={"fields[appStoreVersionLocalizations]": "locale"}):
            placements = client.all(f"/v1/appStoreVersionLocalizations/{localization['id']}/placements")
            slots = [p["attributes"].get("placementType") for p in placements if p["attributes"].get("placementType") in SLOTS.values()]
            print(f"iOS {version['attributes']['versionString']} {localization['attributes']['locale']}: {slots or 'no header or search results asset'}")


def upload_file(client: Client, library: str, path: Path, name: str) -> dict:
    content = path.read_bytes()
    reserved = client.call("POST", "/v1/appAssetLibraryImages", {"data": {
        "type": "appAssetLibraryImages",
        "attributes": {"category": "CREATIVE_ASSETS", "fileName": path.name, "fileSize": len(content), "referenceName": name},
        "relationships": {"assetLibrary": {"data": {"type": "appAssetLibraries", "id": library}}},
    }})["data"]
    for operation in reserved["attributes"].get("uploadOperations") or []:
        start, length = operation["offset"], operation["length"]
        headers = {header["name"]: header["value"] for header in operation.get("requestHeaders") or []}
        request = urllib.request.Request(operation["url"], data=content[start:start + length], method=operation["method"], headers=headers)
        with urllib.request.urlopen(request, timeout=300):
            pass
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


def main() -> None:
    commands = {"status": status, "upload": upload, "place": place, "submit": submit, "publish": publish}
    if len(sys.argv) != 2 or sys.argv[1] not in commands:
        raise SystemExit(f"Usage: asset_library.py {'|'.join(commands)}")
    client = Client(os.environ["ASC_KEY_ID"], os.environ["ASC_ISSUER_ID"], key_pem(os.environ["ASC_KEY_P8"]))
    try:
        commands[sys.argv[1]](client)
    finally:
        client.close()


if __name__ == "__main__":
    main()
