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

Nothing here submits anything for review (CLAUDE.md: never without the user's go). Prints no secret and no contact
detail. Standard library and the openssl command only.
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


def place(client: Client):
    app = app_id(client)
    library = library_id(client, app)
    versions = [version for version in ios_versions(client, app) if version["attributes"]["appStoreState"] in EDITABLE]
    if not versions:
        raise SystemExit("No iOS version being prepared: a version in review or approved cannot change. Nothing placed.")
    version = versions[0]
    groups = slot_groups(client)["groups"]
    images = {image["attributes"].get("referenceName"): image for image in creative_images(client, library)}
    localizations = {item["attributes"]["locale"]: item for item in client.all(
        f"/v1/appStoreVersions/{version['id']}/appStoreVersionLocalizations", query={"fields[appStoreVersionLocalizations]": "locale"})}
    for locale in LOCALES:
        localization = localizations.get(locale)
        if not localization:
            print(f"iOS {version['attributes']['versionString']} has no {locale} listing: skipped")
            continue
        current = client.all(f"/v1/appStoreVersionLocalizations/{localization['id']}/placements", query={"include": "image"})
        for kind, slot in SLOTS.items():
            name = reference_name(kind, locale, (CREATIVE / locale / FILES[kind]).read_bytes())
            image = images.get(name)
            if not image or image["attributes"]["state"] in ("FAILED", "ARCHIVED"):
                raise SystemExit(f"{name} is not in the library: run upload first")
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


def main() -> None:
    commands = {"status": status, "upload": upload, "place": place}
    if len(sys.argv) != 2 or sys.argv[1] not in commands:
        raise SystemExit(f"Usage: asset_library.py {'|'.join(commands)}")
    client = Client(os.environ["ASC_KEY_ID"], os.environ["ASC_ISSUER_ID"], key_pem(os.environ["ASC_KEY_P8"]))
    try:
        commands[sys.argv[1]](client)
    finally:
        client.close()


if __name__ == "__main__":
    main()
